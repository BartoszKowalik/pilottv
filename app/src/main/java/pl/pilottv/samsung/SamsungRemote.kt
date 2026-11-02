package pl.pilottv.samsung

import android.util.Base64
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONObject
import pl.pilottv.data.TvConfig
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager

sealed interface TvState {
    data object Idle : TvState
    data object Connecting : TvState
    data object AwaitingApproval : TvState
    data object Connected : TvState
    data class Failed(val reason: SessionEnd) : TvState
}

enum class SessionEnd { CLOSED, UNREACHABLE, TIMEOUT, DENIED, CERT_CHANGED }

data class SessionResult(val end: SessionEnd, val wasConnected: Boolean)

/**
 * Klient WebSocket dla Samsung Tizen (2016+), port 8002 (wss).
 * Jedna sesja = jedno połączenie. Ponowne łączenie robi wywołujący.
 */
class SamsungRemote(clientName: String) {

    private val nameParam = Base64.encodeToString(clientName.toByteArray(), Base64.NO_WRAP)

    private val baseClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val _state = MutableStateFlow<TvState>(TvState.Idle)
    val state: StateFlow<TvState> = _state.asStateFlow()

    private val _keyboardOnTv = MutableSharedFlow<Boolean>(extraBufferCapacity = 4)

    /** true, gdy TV otworzył klawiaturę ekranową (pole tekstowe), false, gdy ją zamknął. */
    val keyboardOnTv: SharedFlow<Boolean> = _keyboardOnTv.asSharedFlow()

    private val sessionIds = AtomicInteger()

    @Volatile
    private var activeId = 0

    @Volatile
    private var socket: WebSocket? = null

    /** Unieważnia bieżącą sesję. Zdarzenia ze starego gniazda nie zmieniają już stanu. */
    fun markIdle() {
        activeId = sessionIds.incrementAndGet()
        socket = null
        _state.value = TvState.Idle
    }

    /**
     * Otwiera połączenie i czeka, aż się zakończy. Anulowanie korutyny zamyka gniazdo.
     * [onPaired] dostaje nowy token i skrót certyfikatu, gdy trzeba je zapisać.
     */
    suspend fun session(config: TvConfig, onPaired: (token: String?, certPin: String) -> Unit): SessionResult {
        val id = sessionIds.incrementAndGet()
        activeId = id
        fun update(newState: TvState) {
            if (activeId == id) _state.value = newState
        }

        val trustManager = TofuTrustManager(config.certPin)
        val sslContext = SSLContext.getInstance("TLS").apply {
            init(null, arrayOf<TrustManager>(trustManager), null)
        }
        val client = baseClient.newBuilder()
            .sslSocketFactory(sslContext.socketFactory, trustManager)
            // Certyfikat jest przypięty przez TofuTrustManager, nazwa hosta to IP.
            .hostnameVerifier { _, _ -> true }
            .build()

        val url = buildString {
            append("wss://").append(config.ip)
            append(":8002/api/v2/channels/samsung.remote.control?name=").append(nameParam)
            config.token?.let { append("&token=").append(it) }
        }

        val done = CompletableDeferred<SessionResult>()
        val connected = AtomicBoolean(false)
        update(TvState.Connecting)

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                if (config.token == null) update(TvState.AwaitingApproval)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                when (json.optString("event")) {
                    "ms.channel.connect" -> {
                        connected.set(true)
                        val token = json.optJSONObject("data")?.optString("token")?.takeIf { it.isNotEmpty() }
                        val pin = trustManager.seenPin
                        val tokenChanged = token != null && token != config.token
                        if (pin != null && (pin != config.certPin || tokenChanged)) {
                            onPaired(token ?: config.token, pin)
                        }
                        if (activeId == id) socket = webSocket
                        update(TvState.Connected)
                    }
                    "ms.remote.imeStart" -> if (activeId == id) _keyboardOnTv.tryEmit(true)
                    "ms.remote.imeEnd" -> if (activeId == id) _keyboardOnTv.tryEmit(false)
                    "ms.channel.unauthorized" -> finish(webSocket, SessionEnd.DENIED)
                    "ms.channel.timeOut" -> finish(webSocket, SessionEnd.TIMEOUT)
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) = Unit

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                done.complete(SessionResult(SessionEnd.CLOSED, connected.get()))
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val end = if (t.hasCause<PinMismatchException>()) SessionEnd.CERT_CHANGED else SessionEnd.UNREACHABLE
                done.complete(SessionResult(end, connected.get()))
            }

            private fun finish(webSocket: WebSocket, end: SessionEnd) {
                done.complete(SessionResult(end, connected.get()))
                webSocket.close(1000, null)
            }
        }

        val ws = client.newWebSocket(Request.Builder().url(url).build(), listener)
        try {
            val result = done.await()
            update(TvState.Failed(result.end))
            return result
        } finally {
            if (socket === ws) socket = null
            ws.cancel()
        }
    }

    fun sendKey(key: String): Boolean = sendControl(
        JSONObject()
            .put("Cmd", "Click")
            .put("DataOfCmd", key)
            .put("Option", "false")
            .put("TypeOfRemote", "SendRemoteKey"),
    )

    /** Wpisuje tekst w aktywne pole tekstowe na TV. Działa tylko, gdy TV pokazuje klawiaturę. */
    fun sendText(text: String): Boolean = sendControl(
        JSONObject()
            .put("Cmd", Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP))
            .put("DataOfCmd", "base64")
            .put("TypeOfRemote", "SendInputString"),
    )

    /** Zatwierdza tekst i zamyka klawiaturę na TV. */
    fun endInput(): Boolean = sendControl(JSONObject().put("TypeOfRemote", "SendInputEnd"))

    private fun sendControl(params: JSONObject): Boolean {
        val ws = socket ?: return false
        val payload = JSONObject()
            .put("method", "ms.remote.control")
            .put("params", params)
        return ws.send(payload.toString())
    }
}

private inline fun <reified T : Throwable> Throwable.hasCause(): Boolean =
    generateSequence(this) { it.cause }.any { it is T }
