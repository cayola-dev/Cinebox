package com.ashe.cinemabox.telegram

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import org.drinkless.tdlib.TdApi
import org.junit.Assert.*
import org.junit.Test

class TdClientTest {
    private class FakeConnection(val update: (TdApi.Object) -> Unit) : TdTransport {
        val sent = LinkedBlockingQueue<Pair<TdApi.Function<*>, (TdApi.Object) -> Unit>>()
        override fun send(request: TdApi.Function<*>, result: (TdApi.Object) -> Unit) {
            sent.put(request to result)
        }
        fun next() = checkNotNull(sent.poll(5, TimeUnit.SECONDS)) { "No TDLib request received" }
        fun state(state: TdApi.AuthorizationState) = update(TdApi.UpdateAuthorizationState(state))
    }

    @Test fun repeatedStartCreatesOneClientAndHandlesImmediateCallback() {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val connections = CopyOnWriteArrayList<FakeConnection>()
            val client = TdClient({ TdApi.SetTdlibParameters() }, { update, _ ->
                FakeConnection(update).also {
                    connections.add(it)
                    it.state(TdApi.AuthorizationStateWaitTdlibParameters())
                }
            }, dispatcher, {})
            repeat(100) { client.start() }
            runBlocking { withContext(dispatcher) {} }
            assertEquals(1, connections.size)
            assertTrue(connections.single().next().first is TdApi.SetTdlibParameters)
        }
    }

    @Test fun rapidReturnWaitsForClosedAndIgnoresOldCallbacks() {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val connections = CopyOnWriteArrayList<FakeConnection>()
            val client = TdClient({ TdApi.SetTdlibParameters() }, { update, _ ->
                FakeConnection(update).also { connections.add(it) }
            }, dispatcher, {})
            client.start()
            runBlocking { withContext(dispatcher) {} }
            val old = connections.single()
            client.stop()
            assertTrue(old.next().first is TdApi.Close)
            client.start()
            runBlocking { withContext(dispatcher) {} }
            assertEquals(1, connections.size)
            old.state(TdApi.AuthorizationStateClosed())
            runBlocking { withContext(dispatcher) {} }
            assertEquals(2, connections.size)
            val active = connections.last()
            active.state(TdApi.AuthorizationStateReady())
            runBlocking { withTimeout(5000) { client.state.first { it == AuthState.Ready } } }
            old.state(TdApi.AuthorizationStateClosed())
            runBlocking { withContext(dispatcher) {} }
            assertEquals(AuthState.Ready, client.state.value)
            client.stop()
            assertTrue(active.next().first is TdApi.Close)
        }
    }

    @Test fun stopDoesNotLogoutOrReopenInBackground() {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val connections = CopyOnWriteArrayList<FakeConnection>()
            val client = TdClient({ TdApi.SetTdlibParameters() }, { update, _ ->
                FakeConnection(update).also { connections.add(it) }
            }, dispatcher, {})
            client.start()
            runBlocking { withContext(dispatcher) {} }
            client.stop()
            client.stop()
            val connection = connections.single()
            assertTrue(connection.next().first is TdApi.Close)
            connection.state(TdApi.AuthorizationStateClosed())
            runBlocking { withContext(dispatcher) {} }
            assertEquals(1, connections.size)
            assertTrue(connection.sent.isEmpty())
        }
    }

    @Test fun nativeLoadFailureCanBeRetried() {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            var attempts = 0
            val client = TdClient({ TdApi.SetTdlibParameters() }, { _, _ ->
                attempts++
                throw UnsatisfiedLinkError("test")
            }, dispatcher, {})
            client.start()
            runBlocking { withContext(dispatcher) {} }
            client.start()
            runBlocking { withContext(dispatcher) {} }
            assertEquals(2, attempts)
            assertTrue(message(client.state.value).contains("No se pudo cargar TDLib"))
        }
    }

    @Test fun rateLimitFormatsAreRecognizedWithoutLeakingRawErrors() {
        assertTrue(tdErrorMessage(429, "FLOOD_WAIT_30").contains("30 segundos"))
        assertTrue(tdErrorMessage(429, "Too Many Requests: retry after 17").contains("17 segundos"))
        assertFalse(tdErrorMessage(400, "private-data").contains("private-data"))
    }

    @Test fun returningDuringFloodWaitDoesNotReconnectUntilDeadline() {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            var now = 0L
            val connections = CopyOnWriteArrayList<FakeConnection>()
            val client = TdClient({ TdApi.SetTdlibParameters() }, { update, _ ->
                FakeConnection(update).also {
                    connections.add(it)
                    it.state(TdApi.AuthorizationStateWaitTdlibParameters())
                }
            }, dispatcher, {}, { now })
            client.start()
            runBlocking { withContext(dispatcher) {} }
            val connection = connections.single()
            connection.next().second(TdApi.Error(429, "Too Many Requests: retry after 30"))
            runBlocking { withContext(dispatcher) {} }
            client.stop()
            assertTrue(connection.next().first is TdApi.Close)
            connection.state(TdApi.AuthorizationStateClosed())
            client.start()
            runBlocking { withContext(dispatcher) {} }
            assertEquals(1, connections.size)
            assertTrue(message(client.state.value).contains("30 segundos"))
            now = 30_000L
            client.start()
            runBlocking { withContext(dispatcher) {} }
            assertEquals(2, connections.size)
        }
    }

    @Test fun waitPhoneNumberRequestsQrAndShowsRefreshedLinks() {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val connections = CopyOnWriteArrayList<FakeConnection>()
            val client = TdClient({ TdApi.SetTdlibParameters() }, { update, _ ->
                FakeConnection(update).also { connections.add(it) }
            }, dispatcher, {})
            client.start()
            runBlocking { withContext(dispatcher) {} }
            val connection = connections.single()
            connection.state(TdApi.AuthorizationStateWaitPhoneNumber())
            val request = connection.next().first
            assertTrue(request is TdApi.RequestQrCodeAuthentication)
            assertEquals(0, (request as TdApi.RequestQrCodeAuthentication).otherUserIds.size)
            connection.state(TdApi.AuthorizationStateWaitOtherDeviceConfirmation("tg://login?token=a"))
            runBlocking { withContext(dispatcher) {} }
            assertEquals(AuthState.Qr("tg://login?token=a"), client.state.value)
            connection.state(TdApi.AuthorizationStateWaitOtherDeviceConfirmation("tg://login?token=b"))
            runBlocking { withContext(dispatcher) {} }
            assertEquals(AuthState.Qr("tg://login?token=b"), client.state.value)
        }
    }

    @Test fun wrongPasswordKeepsPasswordScreenWithError() {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val connections = CopyOnWriteArrayList<FakeConnection>()
            val client = TdClient({ TdApi.SetTdlibParameters() }, { update, _ ->
                FakeConnection(update).also { connections.add(it) }
            }, dispatcher, {})
            client.start()
            runBlocking { withContext(dispatcher) {} }
            val connection = connections.single()
            connection.state(TdApi.AuthorizationStateWaitPassword("pista", false, false, ""))
            runBlocking { withContext(dispatcher) {} }
            assertEquals(AuthState.Password("pista"), client.state.value)
            client.submitPassword("")
            runBlocking { withContext(dispatcher) {} }
            assertTrue(connection.sent.isEmpty())
            client.submitPassword("mala")
            val (request, reply) = connection.next()
            assertEquals("mala", (request as TdApi.CheckAuthenticationPassword).password)
            client.submitPassword("mala")
            runBlocking { withContext(dispatcher) {} }
            assertTrue(connection.sent.isEmpty())
            reply(TdApi.Error(400, "PASSWORD_HASH_INVALID"))
            runBlocking { withContext(dispatcher) {} }
            val state = client.state.value as AuthState.Password
            assertEquals("pista", state.hint)
            assertFalse(state.checking)
            assertTrue(state.error!!.contains("incorrecta"))
            connection.state(TdApi.AuthorizationStateReady())
            runBlocking { withContext(dispatcher) {} }
            assertEquals(AuthState.Ready, client.state.value)
        }
    }

    @Test fun sessionEndedFromPhoneReturnsToQr() {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val connections = CopyOnWriteArrayList<FakeConnection>()
            val client = TdClient({ TdApi.SetTdlibParameters() }, { update, _ ->
                FakeConnection(update).also { connections.add(it) }
            }, dispatcher, {})
            client.start()
            runBlocking { withContext(dispatcher) {} }
            val old = connections.single()
            old.state(TdApi.AuthorizationStateReady())
            old.state(TdApi.AuthorizationStateLoggingOut())
            old.state(TdApi.AuthorizationStateClosing())
            old.state(TdApi.AuthorizationStateClosed())
            runBlocking { withContext(dispatcher) {} }
            assertEquals(2, connections.size)
            val fresh = connections.last()
            fresh.state(TdApi.AuthorizationStateWaitPhoneNumber())
            assertTrue(fresh.next().first is TdApi.RequestQrCodeAuthentication)
        }
    }

    @Test fun backFromPasswordLogsOutAndReturnsToQr() {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val connections = CopyOnWriteArrayList<FakeConnection>()
            val client = TdClient({ TdApi.SetTdlibParameters() }, { update, _ ->
                FakeConnection(update).also { connections.add(it) }
            }, dispatcher, {})
            client.start()
            runBlocking { withContext(dispatcher) {} }
            val old = connections.single()
            client.cancelLogin()
            runBlocking { withContext(dispatcher) {} }
            assertTrue(old.sent.isEmpty())
            old.state(TdApi.AuthorizationStateWaitPassword("", false, false, ""))
            client.cancelLogin()
            assertTrue(old.next().first is TdApi.LogOut)
            old.state(TdApi.AuthorizationStateLoggingOut())
            old.state(TdApi.AuthorizationStateClosed())
            runBlocking { withContext(dispatcher) {} }
            assertEquals(2, connections.size)
            connections.last().state(TdApi.AuthorizationStateWaitPhoneNumber())
            assertTrue(connections.last().next().first is TdApi.RequestQrCodeAuthentication)
        }
    }

    @Test fun retryClosesAndReopensOnlyInForeground() {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val connections = CopyOnWriteArrayList<FakeConnection>()
            val client = TdClient({ TdApi.SetTdlibParameters() }, { update, _ ->
                FakeConnection(update).also { connections.add(it) }
            }, dispatcher, {})
            client.retry()
            runBlocking { withContext(dispatcher) {} }
            assertEquals(0, connections.size)
            client.start()
            runBlocking { withContext(dispatcher) {} }
            val old = connections.single()
            client.retry()
            client.retry()
            assertTrue(old.next().first is TdApi.Close)
            runBlocking { withContext(dispatcher) {} }
            assertTrue(old.sent.isEmpty())
            old.state(TdApi.AuthorizationStateClosed())
            runBlocking { withContext(dispatcher) {} }
            assertEquals(2, connections.size)
        }
    }

    @Test fun callFailsBeforeReadyAndReturnsResultsOrErrorsAfter() {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val connections = CopyOnWriteArrayList<FakeConnection>()
            val client = TdClient({ TdApi.SetTdlibParameters() }, { update, _ ->
                FakeConnection(update).also { connections.add(it) }
            }, dispatcher, {})
            client.start()
            runBlocking { withContext(dispatcher) {} }
            val connection = connections.single()
            val early = runCatching { runBlocking { client.call(TdApi.GetMe()) } }.exceptionOrNull()
            assertEquals("NOT_READY", (early as TdException).message)
            connection.state(TdApi.AuthorizationStateReady())
            runBlocking { withContext(dispatcher) {} }
            val ok = Thread {
                val (request, reply) = connection.next()
                assertTrue(request is TdApi.GetMe)
                reply(TdApi.User().apply { id = 42 })
            }.apply { start() }
            assertEquals(42L, runBlocking { withTimeout(5000) { client.call(TdApi.GetMe()) } }.id)
            ok.join()
            Thread { connection.next().second(TdApi.Error(400, "CHAT_NOT_FOUND")) }.start()
            val failure = runCatching { runBlocking { withTimeout(5000) { client.call(TdApi.GetMe()) } } }.exceptionOrNull()
            assertEquals(400, (failure as TdException).code)
        }
    }

    private fun message(state: AuthState) = when (state) {
        is AuthState.Working -> state.message
        is AuthState.Failed -> state.message
        else -> ""
    }
}
