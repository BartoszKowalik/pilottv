package pl.pilottv.lg

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
import org.json.JSONArray
import org.json.JSONObject
import pl.pilottv.data.TvConfig
import pl.pilottv.net.PinMismatchException
import pl.pilottv.net.TofuTrustManager
import pl.pilottv.net.hasCause
import pl.pilottv.remote.ImeEvent
import pl.pilottv.remote.Key
import pl.pilottv.remote.TvClient
import pl.pilottv.remote.TvUpdate
import pl.pilottv.remote.textEdit
import pl.pilottv.samsung.SessionEnd
import pl.pilottv.samsung.SessionResult
import pl.pilottv.samsung.TvState
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLContext
import javax.net.ssl.TrustManager

/**
 * Klient LG webOS (protokół SSAP przez WebSocket).
 * Nowsze TV wymagają wss://:3001 (certyfikat self-signed), starsze ws://:3000.
 * Parowanie: TV pokazuje okno zgody i zwraca client-key. Przyciski idą osobnym gniazdem „pointer”.
 */
class LgRemote(private val clientName: String) : TvClient {

    private val baseClient = OkHttpClient.Builder()
        .connectTimeout(4, TimeUnit.SECONDS)
        .readTimeout(0, TimeUnit.MILLISECONDS)
        // Brak odpowiedzi na ping kończy sesję. Tak wykrywamy TV, który zniknął z sieci bez zamknięcia gniazda.
        .pingInterval(10, TimeUnit.SECONDS)
        .build()

    private val _state = MutableStateFlow<TvState>(TvState.Idle)
    override val state: StateFlow<TvState> = _state.asStateFlow()

    private val _ime = MutableSharedFlow<ImeEvent>(extraBufferCapacity = 8)
    override val ime: SharedFlow<ImeEvent> = _ime.asSharedFlow()

    private val sessionIds = AtomicInteger()
    private val requestIds = AtomicInteger()

    @Volatile
    private var activeId = 0

    @Volatile
    private var main: WebSocket? = null

    @Volatile
    private var pointer: WebSocket? = null

    /** Tekst ostatnio wysłany do pola na TV. LG edytuje pole przyrostowo (usuń N znaków, wstaw tekst). */
    @Volatile
    private var sentText = ""

    override fun markIdle() {
        activeId = sessionIds.incrementAndGet()
        main = null
        pointer?.cancel()
        pointer = null
        _state.value = TvState.Idle
    }

    override suspend fun session(config: TvConfig, onUpdate: (TvUpdate) -> Unit): SessionResult {
        val id = sessionIds.incrementAndGet()
        activeId = id
        _state.value = TvState.Connecting
        val secure = attempt(id, config, secure = true, onUpdate)
        // Port 3001 zamknięty: starszy TV, próbujemy bez szyfrowania.
        val result = if (!secure.opened && secure.result.end == SessionEnd.UNREACHABLE && activeId == id) {
            attempt(id, config, secure = false, onUpdate).result
        } else {
            secure.result
        }
        if (activeId == id) _state.value = TvState.Failed(result.end)
        return result
    }

    private class Attempt(val result: SessionResult, val opened: Boolean)

    private suspend fun attempt(id: Int, config: TvConfig, secure: Boolean, onUpdate: (TvUpdate) -> Unit): Attempt {
        fun update(newState: TvState) {
            if (activeId == id) _state.value = newState
        }

        val trustManager = TofuTrustManager(config.certPin)
        val client = if (secure) {
            val sslContext = SSLContext.getInstance("TLS").apply { init(null, arrayOf<TrustManager>(trustManager), null) }
            baseClient.newBuilder()
                .sslSocketFactory(sslContext.socketFactory, trustManager)
                .hostnameVerifier { _, _ -> true }
                .build()
        } else {
            baseClient
        }
        val url = if (secure) "wss://${config.ip}:3001/" else "ws://${config.ip}:3000/"
        // Adres zapisany przez starszą wersję może być niepoprawny. OkHttp odrzuca go wyjątkiem.
        val request = try {
            Request.Builder().url(url).build()
        } catch (e: IllegalArgumentException) {
            update(TvState.Failed(SessionEnd.BAD_ADDRESS))
            return Attempt(SessionResult(SessionEnd.BAD_ADDRESS, false), opened = false)
        }

        val done = CompletableDeferred<SessionResult>()
        val opened = AtomicBoolean(false)
        val connected = AtomicBoolean(false)

        val listener = object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                opened.set(true)
                if (config.token == null) update(TvState.AwaitingApproval)
                webSocket.send(registerMessage(config.token).toString())
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                val json = runCatching { JSONObject(text) }.getOrNull() ?: return
                val type = json.optString("type")
                val payload = json.optJSONObject("payload")
                when (json.optString("id")) {
                    REGISTER_ID -> when (type) {
                        "registered" -> {
                            connected.set(true)
                            if (activeId == id) main = webSocket
                            update(TvState.Connected)
                            val key = payload?.optString("client-key")?.takeIf { it.isNotEmpty() }
                            onUpdate(TvUpdate(token = key, certPin = if (secure) trustManager.seenPin else null))
                            webSocket.send(request(POINTER_ID, "ssap://com.webos.service.networkinput/getPointerInputSocket"))
                            webSocket.send(request(NETWORK_ID, "ssap://com.webos.service.connectionmanager/getinfo"))
                            webSocket.send(subscribe(IME_ID, "ssap://com.webos.service.ime/registerRemoteKeyboard"))
                        }
                        "error" -> {
                            done.complete(SessionResult(SessionEnd.DENIED, connected.get()))
                            webSocket.close(1000, null)
                        }
                    }
                    POINTER_ID -> {
                        val path = payload?.optString("socketPath")?.takeIf { it.isNotEmpty() }
                        if (path != null && activeId == id) openPointer(client, path)
                    }
                    NETWORK_ID -> {
                        val mac = payload?.optJSONObject("wifiInfo")?.optString("macAddress")?.takeIf { it.isNotEmpty() }
                            ?: payload?.optJSONObject("wiredInfo")?.optString("macAddress")?.takeIf { it.isNotEmpty() }
                        if (mac != null) onUpdate(TvUpdate(mac = mac))
                    }
                    IME_ID -> {
                        val widget = payload?.optJSONObject("currentWidget") ?: return
                        if (activeId != id) return
                        if (widget.optBoolean("focus")) {
                            sentText = ""
                            _ime.tryEmit(ImeEvent.Opened(null))
                        } else {
                            _ime.tryEmit(ImeEvent.Closed)
                        }
                    }
                }
            }

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
        }

        val ws = client.newWebSocket(request, listener)
        try {
            return Attempt(done.await(), opened.get())
        } finally {
            if (main === ws) main = null
            if (activeId == id) {
                pointer?.cancel()
                pointer = null
            }
            ws.cancel()
        }
    }

    private fun openPointer(client: OkHttpClient, path: String) {
        pointer?.cancel()
        pointer = client.newWebSocket(Request.Builder().url(path).build(), object : WebSocketListener() {
            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                if (pointer === webSocket) pointer = null
            }
        })
    }

    override fun sendKey(key: Key): Boolean {
        val command = LgKeys.of(key)
        if (command is LgCommand.Button) {
            pointer?.let { return it.send("type:button\nname:${command.name}\n\n") }
            val fallback = LgKeys.fallback(key) ?: return false
            return sendSsap(fallback)
        }
        return sendSsap(command as LgCommand.Ssap)
    }

    private fun sendSsap(command: LgCommand.Ssap): Boolean {
        val ws = main ?: return false
        val payload = command.payload?.let { JSONObject(it) }
        return ws.send(request("req_${requestIds.incrementAndGet()}", command.uri, payload))
    }

    /** Uruchamia aplikację na TV po jej identyfikatorze webOS. */
    fun launchApp(appId: String): Boolean =
        sendSsap(LgCommand.Ssap("ssap://system.launcher/launch", mapOf("id" to appId)))

    override fun sendText(text: String): Boolean {
        val ws = main ?: return false
        val edit = textEdit(sentText, text)
        if (edit.deleteChars > 0) {
            ws.send(request("req_${requestIds.incrementAndGet()}", "ssap://com.webos.service.ime/deleteCharacters",
                JSONObject().put("count", edit.deleteChars)))
        }
        val toInsert = edit.insert
        if (toInsert.isNotEmpty()) {
            ws.send(request("req_${requestIds.incrementAndGet()}", "ssap://com.webos.service.ime/insertText",
                JSONObject().put("text", toInsert).put("replace", 0)))
        }
        sentText = text
        return true
    }

    override fun endInput(): Boolean {
        val ws = main ?: return false
        sentText = ""
        return ws.send(request("req_${requestIds.incrementAndGet()}", "ssap://com.webos.service.ime/sendEnterKey"))
    }

    private fun request(id: String, uri: String, payload: JSONObject? = null): String {
        val json = JSONObject().put("type", "request").put("id", id).put("uri", uri)
        if (payload != null) json.put("payload", payload)
        return json.toString()
    }

    private fun subscribe(id: String, uri: String): String =
        JSONObject().put("type", "subscribe").put("id", id).put("uri", uri).toString()

    private fun registerMessage(clientKey: String?): JSONObject {
        val permissions = JSONArray(PERMISSIONS)
        val manifest = JSONObject()
            .put("manifestVersion", 1)
            .put("appVersion", "1.0")
            .put(
                "signed",
                JSONObject()
                    .put("appId", "pl.pilottv")
                    .put("vendorId", "pl.pilottv")
                    .put("localizedAppNames", JSONObject().put("", clientName))
                    .put("localizedVendorNames", JSONObject().put("", clientName))
                    .put("permissions", permissions)
                    .put("serial", "pilottv"),
            )
            .put("permissions", permissions)
        val payload = JSONObject()
            .put("forcePairing", false)
            .put("pairingType", "PROMPT")
            .put("manifest", manifest)
        if (clientKey != null) payload.put("client-key", clientKey)
        return JSONObject().put("type", "register").put("id", REGISTER_ID).put("payload", payload)
    }

    private companion object {
        const val REGISTER_ID = "register_0"
        const val POINTER_ID = "pointer_0"
        const val NETWORK_ID = "network_0"
        const val IME_ID = "ime_0"

        val PERMISSIONS = listOf(
            "LAUNCH", "LAUNCH_WEBAPP", "APP_TO_APP", "CONTROL_AUDIO", "CONTROL_DISPLAY",
            "CONTROL_INPUT_JOYSTICK", "CONTROL_INPUT_MEDIA_RECORDING", "CONTROL_INPUT_MEDIA_PLAYBACK",
            "CONTROL_INPUT_TV", "CONTROL_POWER", "CONTROL_INPUT_TEXT", "CONTROL_MOUSE_AND_KEYBOARD",
            "READ_APP_STATUS", "READ_CURRENT_CHANNEL", "READ_INPUT_DEVICE_LIST", "READ_NETWORK_STATE",
            "READ_RUNNING_APPS", "READ_TV_CHANNEL_LIST", "READ_POWER_STATE", "READ_INSTALLED_APPS",
            "READ_SETTINGS", "WRITE_NOTIFICATION_TOAST",
        )
    }
}
