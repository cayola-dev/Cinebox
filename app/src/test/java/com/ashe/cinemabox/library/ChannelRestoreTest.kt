package com.ashe.cinemabox.library

import com.ashe.cinemabox.telegram.TdClient
import com.ashe.cinemabox.telegram.TdException
import com.ashe.cinemabox.telegram.TdTransport
import java.util.concurrent.Executors
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import com.ashe.cinemabox.telegram.AuthState
import org.drinkless.tdlib.TdApi
import org.junit.Assert.*
import org.junit.Test

class ChannelRestoreTest {
    private fun withRepository(reply: (TdApi.Function<*>) -> TdApi.Object, check: suspend (LibraryRepository) -> Unit) {
        Executors.newSingleThreadExecutor().asCoroutineDispatcher().use { dispatcher ->
            val client = TdClient({ TdApi.SetTdlibParameters() }, { update, _ ->
                update(TdApi.UpdateAuthorizationState(TdApi.AuthorizationStateReady()))
                object : TdTransport {
                    override fun send(request: TdApi.Function<*>, result: (TdApi.Object) -> Unit) { result(reply(request)) }
                }
            }, dispatcher, {})
            client.start()
            runBlocking {
                withTimeout(5000) { client.state.first { it == AuthState.Ready } }
                check(LibraryRepository(client))
            }
        }
    }

    @Test fun coldStartLoadsArchivedChannelAndItsPhoto() {
        var available = false
        var loads = 0
        withRepository({ request ->
            when (request) {
                is TdApi.GetChat -> if (!available) TdApi.Error(400, "Chat not found") else TdApi.Chat().apply {
                    id = -123; title = "Library"
                    type = TdApi.ChatTypeSupergroup(123, true)
                    photo = TdApi.ChatPhotoInfo().apply { big = TdApi.File().apply { id = 42 } }
                }
                is TdApi.LoadChats -> {
                    loads++
                    if (request.chatList is TdApi.ChatListMain) TdApi.Error(404, "Not Found")
                    else { available = true; TdApi.Ok() }
                }
                is TdApi.GetSupergroup -> TdApi.Supergroup().apply { memberCount = 5 }
                else -> error("Unexpected request")
            }
        }) { repository ->
            val channel = repository.channel(-123)
            assertEquals("Library", channel?.title)
            assertEquals(42, channel?.photoFileId)
            assertEquals(2, loads)
        }
    }

    @Test fun unavailableChannelStopsWhenBothListsAreExhausted() {
        var loads = 0
        withRepository({ request ->
            when (request) {
                is TdApi.GetChat -> TdApi.Error(400, "Chat not found")
                is TdApi.LoadChats -> { loads++; TdApi.Error(404, "Not Found") }
                else -> error("Unexpected request")
            }
        }) { repository ->
            try { repository.channel(-123); fail("Expected a visible restore error") }
            catch (error: TdException) { assertEquals(400, error.code) }
            assertEquals(2, loads)
        }
    }

    @Test fun serverErrorIsNotTreatedAsAnUnknownChat() {
        var loads = 0
        withRepository({ request ->
            if (request is TdApi.LoadChats) loads++
            TdApi.Error(500, "Server unavailable")
        }) { repository ->
            try { repository.channel(-123); fail("Expected server error") }
            catch (error: TdException) { assertEquals(500, error.code) }
            assertEquals(0, loads)
        }
    }
}
