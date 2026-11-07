package pl.pilottv.atv

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import pl.pilottv.data.AtvConfig
import pl.pilottv.net.PinMismatchException
import pl.pilottv.net.TofuTrustManager
import pl.pilottv.net.hasCause
import pl.pilottv.remote.ImeEvent
import pl.pilottv.samsung.SessionEnd
import pl.pilottv.samsung.SessionResult
import pl.pilottv.samsung.TvState
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import kotlin.concurrent.thread
import kotlin.coroutines.resume

/**
 * Klient Android TV Remote v2 (port 6466, TLS z certyfikatem klienta, ramki protobuf).
 * Tak samo jak w SamsungRemote: jedna sesja = jedno połączenie, ponawianie robi wywołujący.
 */
class AtvRemote(private val identity: () -> ClientIdentity, private val clientName: String) {

    private val _state = MutableStateFlow<TvState>(TvState.Idle)
    val state: StateFlow<TvState> = _state.asStateFlow()

    private val _ime = MutableSharedFlow<ImeEvent>(extraBufferCapacity = 8)
    val ime: SharedFlow<ImeEvent> = _ime.asSharedFlow()

    private val sessionIds = AtomicInteger()

    @Volatile
    private var activeId = 0

    @Volatile
    private var output: OutputStream? = null

    private val writeLock = Any()

    // Zapis do gniazda nie może iść z wątku UI.
    private val writer = Executors.newSingleThreadExecutor { r -> Thread(r, "atv-writer").apply { isDaemon = true } }

    @Volatile
    private var imeCounter = 0

    @Volatile
    private var imeFieldCounter = 0

    /**
     * Tekst w polu na dekoderze, tak jak go znamy. Dekoder dopisuje tekst z IME_BATCH_EDIT
     * na końcu pola (nie zastępuje go), więc wysyłamy tylko różnicę.
     */
    @Volatile
    private var fieldText = ""

    @Volatile
    private var lastTextSentAt = 0L

    fun markIdle() {
        activeId = sessionIds.incrementAndGet()
        output = null
        _state.value = TvState.Idle
    }

    /** [onPinSeen] dostaje skrót certyfikatu dekodera przy pierwszym połączeniu po parowaniu. */
    suspend fun session(config: AtvConfig, onPinSeen: (String) -> Unit): SessionResult {
        val id = sessionIds.incrementAndGet()
        activeId = id
        fun update(newState: TvState) {
            if (activeId == id) _state.value = newState
        }
        update(TvState.Connecting)
        fieldText = ""

        return suspendCancellableCoroutine { cont ->
            val socketRef = AtomicReference<Socket?>()
            // Blokujący odczyt nie reaguje na anulowanie korutyny, więc zamykamy gniazdo.
            cont.invokeOnCancellation { runCatching { socketRef.get()?.close() } }

            thread(name = "atv-remote", isDaemon = true) {
                var active = false
                val trustManager = TofuTrustManager(config.certPin)
                val result = try {
                    val socket = identity().sslContext(trustManager).socketFactory.createSocket() as SSLSocket
                    socketRef.set(socket)
                    if (!cont.isActive) socket.close()
                    socket.soTimeout = READ_TIMEOUT_MS
                    socket.connect(InetSocketAddress(config.ip, REMOTE_PORT), CONNECT_TIMEOUT_MS)
                    socket.startHandshake()
                    if (config.certPin == null) trustManager.seenPin?.let(onPinSeen)

                    val input = BufferedInputStream(socket.inputStream)
                    val out = socket.outputStream
                    if (activeId == id) output = out
                    while (true) {
                        val frame = ProtoIo.read(input) ?: break
                        if (handle(ProtoMessage.parse(frame), out) && !active) {
                            active = true
                            update(TvState.Connected)
                        }
                    }
                    SessionResult(SessionEnd.CLOSED, active)
                } catch (e: Throwable) {
                    val end = when {
                        e.hasCause<PinMismatchException>() -> SessionEnd.CERT_CHANGED
                        // Dekoder nie zna certyfikatu klienta: odrzuca TLS przed aktywacją sesji.
                        e.hasCause<SSLException>() && !active -> SessionEnd.NOT_PAIRED
                        else -> SessionEnd.UNREACHABLE
                    }
                    SessionResult(end, active)
                } finally {
                    runCatching { socketRef.get()?.close() }
                    if (activeId == id) output = null
                }
                update(TvState.Failed(result.end))
                if (cont.isActive) cont.resume(result)
            }
        }
    }

    /** Zwraca true, gdy dekoder aktywował sesję (od tej chwili przyjmuje klawisze). */
    private fun handle(msg: ProtoMessage, out: OutputStream): Boolean {
        when {
            msg.has(CONFIGURE) -> write(out, proto {
                message(CONFIGURE) {
                    int(1, FEATURES)
                    message(2) {
                        string(1, clientName)
                        string(2, "Pilot TV")
                        int(3, 1)
                        string(4, "1")
                        string(5, "pl.pilottv")
                        string(6, "1.0.0")
                    }
                }
            })
            msg.has(SET_ACTIVE) -> {
                write(out, proto { message(SET_ACTIVE) { int(1, FEATURES) } })
                return true
            }
            msg.has(PING_REQUEST) -> {
                val value = msg.message(PING_REQUEST)?.int(1) ?: 0L
                write(out, proto { message(PING_RESPONSE) { int(1, value) } })
            }
            msg.has(IME_BATCH_EDIT) -> {
                val edit = msg.message(IME_BATCH_EDIT)
                if (edit != null) {
                    imeCounter = edit.int(1)?.toInt() ?: imeCounter
                    imeFieldCounter = edit.int(2)?.toInt() ?: imeFieldCounter
                    // edit_info(3) → text_field_status(2) → value(3)
                    val text = runCatching { edit.message(3)?.message(2)?.string(3) }.getOrNull()
                    if (text != null) {
                        // Echo naszych zmian może przyjść ze starszym stanem pola, więc tuż po wysłaniu go pomijamy.
                        if (System.currentTimeMillis() - lastTextSentAt > ECHO_GUARD_MS) fieldText = text
                        _ime.tryEmit(ImeEvent.Updated(text))
                    }
                }
            }
            msg.has(IME_KEY_INJECT) -> {
                // text_field_status(2) → value(2): zawartość pola po przejściu do niego.
                val text = runCatching { msg.message(IME_KEY_INJECT)?.message(2)?.string(2) }.getOrNull()
                if (text != null) {
                    fieldText = text
                    _ime.tryEmit(ImeEvent.Updated(text))
                }
            }
            msg.has(IME_SHOW_REQUEST) -> {
                val text = runCatching { msg.message(IME_SHOW_REQUEST)?.message(2)?.string(2) }.getOrNull()
                fieldText = text ?: ""
                _ime.tryEmit(ImeEvent.Opened(text))
            }
        }
        return false
    }

    private fun write(out: OutputStream, message: ByteArray) {
        synchronized(writeLock) { ProtoIo.write(out, message) }
    }

    private fun enqueue(message: ByteArray): Boolean {
        val out = output ?: return false
        writer.execute { runCatching { write(out, message) } }
        return true
    }

    fun sendKey(keyCode: Int): Boolean = enqueue(proto {
        message(KEY_INJECT) {
            int(1, keyCode)
            int(2, DIRECTION_SHORT)
        }
    })

    /**
     * Uruchamia aplikację: link (https://…) albo nazwa pakietu.
     * Pakiet uruchamia aplikacja pomocnicza na dekoderze (moduł tvhelper). Bez niej, tak jak przy
     * market://launch, dekoder nie ma obsługi linku i zrywa połączenie.
     */
    fun launchApp(linkOrPackage: String): Boolean {
        val link = if ("://" in linkOrPackage) linkOrPackage else "pilottv://launch?app=$linkOrPackage"
        return enqueue(proto { message(APP_LINK_LAUNCH) { string(1, link) } })
    }

    /**
     * Ustawia tekst w polu na dekoderze (pusty tekst czyści pole).
     * Wysyła tylko różnicę: Backspace dla usuniętych znaków i wstawienie nowych.
     */
    fun sendText(text: String): Boolean {
        if (output == null) return false
        val previous = fieldText
        val common = previous.commonPrefixWith(text).length
        val toDelete = previous.codePointCount(common, previous.length)
        val toInsert = text.substring(common)
        repeat(toDelete) { if (!sendKey(KEYCODE_DEL)) return false }
        if (toInsert.isNotEmpty()) {
            val sent = enqueue(proto {
                message(IME_BATCH_EDIT) {
                    int(1, imeCounter)
                    int(2, imeFieldCounter)
                    message(3) {
                        int(1, 1)
                        message(2) {
                            int(1, toInsert.length - 1)
                            int(2, toInsert.length - 1)
                            string(3, toInsert)
                        }
                    }
                }
            })
            if (!sent) return false
        }
        fieldText = text
        lastTextSentAt = System.currentTimeMillis()
        return true
    }

    private companion object {
        const val REMOTE_PORT = 6466
        const val CONNECT_TIMEOUT_MS = 4_000
        // Dekoder wysyła ping co kilka sekund. Brak danych przez ten czas = zerwane połączenie.
        const val READ_TIMEOUT_MS = 20_000

        // Obsługiwane funkcje: PING, KEY, IME, VOICE, POWER, VOLUME, APP_LINK.
        const val FEATURES = 622

        // Numery pól w RemoteMessage.
        const val CONFIGURE = 1
        const val SET_ACTIVE = 2
        const val PING_REQUEST = 8
        const val PING_RESPONSE = 9
        const val KEY_INJECT = 10
        const val IME_KEY_INJECT = 20
        const val IME_BATCH_EDIT = 21
        const val IME_SHOW_REQUEST = 22
        const val APP_LINK_LAUNCH = 90

        const val DIRECTION_SHORT = 3
        const val KEYCODE_DEL = 67
        const val ECHO_GUARD_MS = 1_500L
    }
}
