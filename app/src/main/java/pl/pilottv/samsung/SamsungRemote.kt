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
import pl.pilottv.net.PinMismatchException
import pl.pilottv.net.TofuTrustManager
import pl.pilottv.net.hasCause
import pl.pilottv.remote.ImeEvent
import pl.pilottv.remote.Key
import pl.pilottv.remote.TvClient
import pl.pilottv.remote.TvUpdate
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

/** Stan i wynik sesji są wspólne dla Samsunga i dekodera Android TV. */
enum class SessionEnd { CLOSED, UNREACHABLE, TIMEOUT, DENIED, CERT_CHANGED, NOT_PAIRED, BAD_ADDRESS }

data class SessionResult(val end: SessionEnd, val wasConnected: Boolean)

/**
 * Klient WebSocket dla Samsung Tizen (2016+), port 8002 (wss).
 * Jedna sesja = jedno połączenie. Ponowne łączenie robi wywołujący.
 */
class SamsungRemote(clientName: String) : TvClient {

    private val nameParam = Base64.encodeToString(clientName.toByteArray(), Base64.NO_WRAP)

    private val baseClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        .build()

    private val _state = MutableStateFlow<TvState>(TvState.Idle)
    override val state: StateFlow<TvState> = _state.asStateFlow()

    private val _ime = MutableSharedFlow<ImeEvent>(extraBufferCapacity = 8)

    /** Pole tekstowe na TV: otwarcie, zmiana zawartości, zamknięcie. */
    override val ime: SharedFlow<ImeEvent> = _ime.asSharedFlow()

    private val sessionIds = AtomicInteger()

    @Volatile
    private var activeId = 0

    @Volatile
    private var socket: WebSocket? = null

    /** Unieważnia bieżącą sesję. Zdarzenia ze starego gniazda nie zmieniają już stanu. */
    override fun markIdle() {
        activeId = sessionIds.incrementAndGet()
        socket = null
        _state.value = TvState.Idle
    }

    /**
     * Otwiera połączenie i czeka, aż się zakończy. Anulowanie korutyny zamyka gniazdo.
     * [onUpdate] dostaje nowy token i skrót certyfikatu, gdy trzeba je zapisać.
     */
    override suspend fun session(config: TvConfig, onUpdate: (TvUpdate) -> Unit): SessionResult {
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
        // Adres zapisany przez starszą wersję może być niepoprawny. OkHttp odrzuca go wyjątkiem.
        val request = try {
            Request.Builder().url(url).build()
        } catch (e: IllegalArgumentException) {
            update(TvState.Failed(SessionEnd.BAD_ADDRESS))
            return SessionResult(SessionEnd.BAD_ADDRESS, false)
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
                            onUpdate(TvUpdate(token = token ?: config.token, certPin = pin))
                        }
                        if (activeId == id) socket = webSocket
                        update(TvState.Connected)
                    }
                    "ms.remote.imeStart" -> if (activeId == id) _ime.tryEmit(ImeEvent.Opened(imeText(json)))
                    "ms.remote.imeUpdate" -> {
                        val text = imeText(json)
                        if (text != null && activeId == id) _ime.tryEmit(ImeEvent.Updated(text))
                    }
                    "ms.remote.imeEnd" -> if (activeId == id) _ime.tryEmit(ImeEvent.Closed)
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

        val ws = client.newWebSocket(request, listener)
        try {
            val result = done.await()
            update(TvState.Failed(result.end))
            return result
        } finally {
            if (socket === ws) socket = null
            ws.cancel()
        }
    }

    override fun sendKey(key: Key): Boolean = sendRemoteKey(SamsungKeys.of(key))

    private fun sendRemoteKey(key: String): Boolean = sendControl(
        JSONObject()
            .put("Cmd", "Click")
            .put("DataOfCmd", key)
            .put("Option", "false")
            .put("TypeOfRemote", "SendRemoteKey"),
    )

    /** Zawartość pola tekstowego z TV (Base64 w polu "data"). */
    private fun imeText(json: JSONObject): String? {
        val data = json.opt("data") as? String ?: return null
        if (data.isEmpty()) return ""
        return runCatching { String(Base64.decode(data, Base64.DEFAULT), Charsets.UTF_8) }.getOrNull()
    }

    /** Zastępuje tekst w aktywnym polu tekstowym na TV. Działa tylko, gdy TV pokazuje klawiaturę. */
    override fun sendText(text: String): Boolean = sendControl(
        JSONObject()
            .put("Cmd", Base64.encodeToString(text.toByteArray(), Base64.NO_WRAP))
            .put("DataOfCmd", "base64")
            .put("TypeOfRemote", "SendInputString"),
    )

    /** Zatwierdza tekst i zamyka klawiaturę na TV. */
    override fun endInput(): Boolean = sendControl(JSONObject().put("TypeOfRemote", "SendInputEnd"))

    private fun sendControl(params: JSONObject): Boolean {
        val ws = socket ?: return false
        val payload = JSONObject()
            .put("method", "ms.remote.control")
            .put("params", params)
        return ws.send(payload.toString())
    }
}
