package com.ashe.cinemabox.telegram

import android.content.Context
import android.os.Build
import android.util.Log
import com.ashe.cinemabox.BuildConfig
import java.io.File
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException
import org.drinkless.tdlib.Client
import org.drinkless.tdlib.TdApi

/** Small JNI boundary so lifecycle races can be tested without loading Android binaries. */
internal fun interface TdTransport {
    fun send(request: TdApi.Function<*>, result: (TdApi.Object) -> Unit)
}

sealed interface AuthState {
    data class Working(val message: String) : AuthState
    data class Qr(val link: String) : AuthState
    data class Password(val hint: String, val error: String? = null, val checking: Boolean = false) : AuthState
    data object Ready : AuthState
    data class Failed(val message: String) : AuthState
}

/** One owner; all state mutations and sends run in order on the same dispatcher. */
class TdClient internal constructor(
    private val parameters: () -> TdApi.SetTdlibParameters,
    private val connect: ((TdApi.Object) -> Unit, () -> Unit) -> TdTransport,
    private val dispatcher: CoroutineDispatcher,
    private val logState: (String) -> Unit,
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000 },
) {
    constructor(context: Context) : this(
        parameters = {
            require(BuildConfig.TG_API_ID > 0 && BuildConfig.TG_API_HASH.isNotBlank())
            val app = context.applicationContext
            TdApi.SetTdlibParameters().apply {
                apiId = BuildConfig.TG_API_ID
                apiHash = BuildConfig.TG_API_HASH
                databaseEncryptionKey = byteArrayOf()
                databaseDirectory = File(app.noBackupFilesDir, "tdlib").absolutePath
                filesDirectory = File(app.filesDir, "tdlib-files").absolutePath
                useFileDatabase = true
                useChatInfoDatabase = true
                // The library will search channels on demand; no persistent message cache yet.
                useMessageDatabase = false
                useSecretChats = false
                systemLanguageCode = "es"
                deviceModel = "Cinebox TV"
                systemVersion = "Android ${Build.VERSION.RELEASE}"
                applicationVersion = BuildConfig.VERSION_NAME
            }
        },
        connect = { update, failure ->
            System.loadLibrary("tdjni")
            Client.execute(TdApi.SetLogVerbosityLevel(0))
            val native = Client.create(
                { update(it) }, { failure() }, { failure() }
            )
            TdTransport { request, result -> native.send(request, { result(it) }) }
        },
        dispatcher = Dispatchers.IO.limitedParallelism(1),
        logState = { Log.i("Cinebox.TdClient", it) },
    )

    private val scope = CoroutineScope(SupervisorJob() + dispatcher)
    private val mutableState = MutableStateFlow<AuthState>(AuthState.Working("Preparando la conexión…"))
    val state = mutableState.asStateFlow()
    private var client: TdTransport? = null
    private var foreground = false
    private var closing = false
    private var generation = 0L
    private var retryAtMillis: Long? = null
    private val mutableFileUpdates = MutableSharedFlow<TdApi.File>(
        extraBufferCapacity = 64, onBufferOverflow = BufferOverflow.DROP_OLDEST,
    )
    /** Download progress; listeners must tolerate dropped events and re-query when needed. */
    val fileUpdates = mutableFileUpdates.asSharedFlow()

    fun start() {
        scope.launch {
            foreground = true
            if (client == null) open()
            // If Close is pending, wait for Closed before opening the same database again.
        }
    }

    fun stop() {
        scope.launch {
            foreground = false
            if (client != null && !closing) {
                closing = true
                send(TdApi.Close())
            }
        }
    }

    /** Single entry point for library queries; fails fast unless the session is ready. */
    suspend fun <T : TdApi.Object> call(request: TdApi.Function<T>): T = withContext(dispatcher) {
        val transport = client.takeIf { !closing && mutableState.value == AuthState.Ready }
            ?: throw TdException(0, "NOT_READY")
        val remaining = (retryAtMillis ?: 0L) - nowMillis()
        if (remaining > 0 && request !is TdApi.CancelDownloadFile && request !is TdApi.CloseChat)
            throw TdException(429, "FLOOD_WAIT_${(remaining - 1) / 1000 + 1}")
        val current = generation
        suspendCancellableCoroutine { continuation ->
            transport.send(request) { result ->
                scope.launch {
                    if (current != generation || closing) {
                        if (continuation.isActive) continuation.resumeWithException(TdException(0, "NOT_READY"))
                    } else if (result is TdApi.Error) {
                        rememberFloodWait(result)
                        if (continuation.isActive) continuation.resumeWithException(TdException(result.code, result.message))
                    } else if (continuation.isActive) {
                        @Suppress("UNCHECKED_CAST")
                        continuation.resume(result as T)
                    }
                }
            }
        }
    }

    /** Abandons a half-finished login; TDLib closes itself and [handleState] reopens with a new QR. */
    fun cancelLogin() {
        scope.launch {
            if (mutableState.value !is AuthState.Password || client == null || closing) return@launch
            mutableState.value = AuthState.Working("Volviendo al código QR…")
            send(TdApi.LogOut())
        }
    }

    fun retry() {
        scope.launch {
            if (!foreground) return@launch
            when {
                client == null -> open()
                !closing -> {
                    closing = true
                    mutableState.value = AuthState.Working("Reintentando…")
                    send(TdApi.Close())
                }
            }
        }
    }

    /** Ends the session on this TV; TDLib closes itself and [handleState] reopens with a new QR. */
    fun logOut() {
        scope.launch {
            if (mutableState.value != AuthState.Ready || client == null || closing) return@launch
            mutableState.value = AuthState.Working("Cerrando sesión…")
            send(TdApi.LogOut())
        }
    }

    fun submitPassword(password: String) {
        scope.launch {
            val current = mutableState.value
            if (current !is AuthState.Password || current.checking || client == null || closing) return@launch
            val remaining = (retryAtMillis ?: 0L) - nowMillis()
            if (remaining > 0) {
                mutableState.value = current.copy(error = tdErrorMessage(429, "FLOOD_WAIT_${(remaining - 1) / 1000 + 1}"))
                return@launch
            }
            if (password.isEmpty()) {
                mutableState.value = current.copy(error = "Escribe la contraseña.")
                return@launch
            }
            mutableState.value = current.copy(error = null, checking = true)
            send(TdApi.CheckAuthenticationPassword(password)) { error ->
                val message = if (error.message == "PASSWORD_HASH_INVALID") {
                    "Contraseña incorrecta. Inténtalo de nuevo."
                } else {
                    tdErrorMessage(error.code, error.message)
                }
                mutableState.value = AuthState.Password(current.hint, message)
            }
        }
    }

    private fun open() {
        val remaining = retryAtMillis?.let { it - nowMillis() } ?: 0L
        if (remaining > 0) {
            mutableState.value = AuthState.Failed(tdErrorMessage(429, "FLOOD_WAIT_${(remaining - 1) / 1000 + 1}"))
            return
        }
        retryAtMillis = null
        val config = try {
            parameters()
        } catch (_: IllegalArgumentException) {
            mutableState.value = AuthState.Failed(
                "Faltan las credenciales. Configura TG_API_ID y TG_API_HASH en .env y recompila."
            )
            return
        }
        val current = ++generation
        mutableState.value = AuthState.Working("Conectando con tu biblioteca…")
        try {
            client = connect({ update ->
                // Reject irrelevant traffic before allocating a coroutine.
                when (update) {
                    is TdApi.UpdateAuthorizationState -> scope.launch {
                        if (current == generation && client != null) handleState(update.authorizationState, config)
                    }
                    is TdApi.UpdateFile -> scope.launch {
                        if (current == generation && !closing) mutableFileUpdates.tryEmit(update.file)
                    }
                }
            }, {
                scope.launch {
                    if (current == generation && client != null) {
                        mutableState.value = AuthState.Failed(
                            "Se perdió la conexión con el servicio. Reintenta en unos segundos."
                        )
                    }
                }
            })
        } catch (_: LinkageError) {
            mutableState.value = AuthState.Failed(
                "No se pudo cargar TDLib en este dispositivo. Revisa la arquitectura instalada."
            )
        } catch (_: Exception) {
            mutableState.value = AuthState.Failed("No se pudo iniciar la conexión. Reintenta en unos segundos.")
        }
    }

    private fun handleState(state: TdApi.AuthorizationState, config: TdApi.SetTdlibParameters) {
        logState(state.javaClass.simpleName) // No credentials, QR links or user data.
        if (state is TdApi.AuthorizationStateClosed) {
            client = null
            closing = false
            mutableState.value = AuthState.Working("Conexión cerrada.")
            // Also covers a session ended from the phone: TDLib closes itself and we return to the QR.
            if (foreground) open()
            return
        }
        if (closing) return
        mutableState.value = when (state) {
            is TdApi.AuthorizationStateWaitTdlibParameters -> {
                send(config)
                AuthState.Working("Conectando con tu biblioteca…")
            }
            is TdApi.AuthorizationStateWaitPhoneNumber -> {
                send(TdApi.RequestQrCodeAuthentication(longArrayOf()))
                AuthState.Working("Generando el código QR…")
            }
            // TDLib emits this state again with a fresh link whenever the previous one expires.
            is TdApi.AuthorizationStateWaitOtherDeviceConfirmation -> AuthState.Qr(state.link)
            is TdApi.AuthorizationStateWaitPassword -> AuthState.Password(state.passwordHint.orEmpty())
            is TdApi.AuthorizationStateReady -> AuthState.Ready
            is TdApi.AuthorizationStateLoggingOut -> AuthState.Working("La sesión se cerró. Preparando un código nuevo…")
            is TdApi.AuthorizationStateClosing -> AuthState.Working("Cerrando la conexión…")
            else -> AuthState.Failed(
                "Esta cuenta necesita completar un paso que Cinebox no admite. " +
                    "Inicia sesión primero en tu celular y vuelve a intentarlo."
            )
        }
    }

    private fun send(request: TdApi.Function<*>, onError: ((TdApi.Error) -> Unit)? = null) {
        val current = generation
        client?.send(request) { result ->
            if (result is TdApi.Error) scope.launch {
                if (current == generation && client != null && (!closing || request is TdApi.Close)) {
                    rememberFloodWait(result)
                    if (onError != null) {
                        onError(result)
                        return@launch
                    }
                    floodWaitSeconds(result.message)?.let { seconds ->
                        val delay = seconds.coerceAtMost(Long.MAX_VALUE / 1000) * 1000
                        val now = nowMillis()
                        retryAtMillis = if (now > Long.MAX_VALUE - delay) Long.MAX_VALUE else now + delay
                    }
                    mutableState.value = AuthState.Failed(tdErrorMessage(result.code, result.message))
                }
            }
        }
    }

    private fun rememberFloodWait(error: TdApi.Error) {
        floodWaitSeconds(error.message)?.let { seconds ->
            val delay = seconds.coerceAtMost(Long.MAX_VALUE / 1000) * 1000
            val now = nowMillis()
            retryAtMillis = maxOf(retryAtMillis ?: 0, if (now > Long.MAX_VALUE - delay) Long.MAX_VALUE else now + delay)
        }
    }
}

class TdException(val code: Int, override val message: String) : Exception(message)

internal fun tdErrorMessage(code: Int, message: String): String {
    val seconds = floodWaitSeconds(message)
    return if (seconds != null) {
        "Demasiados intentos. Espera $seconds segundos antes de volver a intentarlo."
    } else {
        "No se pudo completar la conexión (error $code). Revisa la red y las credenciales."
    }
}

private val retryPattern = Regex("(?:FLOOD_WAIT_?|retry after )(\\d+)", RegexOption.IGNORE_CASE)
/** Seconds Telegram asks us to wait, from FLOOD_WAIT_X or "retry after X" errors. */
fun floodWaitSeconds(message: String): Long? =
    retryPattern.find(message)?.groupValues?.get(1)?.toLongOrNull()
