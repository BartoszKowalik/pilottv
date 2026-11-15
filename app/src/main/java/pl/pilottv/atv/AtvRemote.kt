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
import pl.pilottv.remote.SessionEnd
import pl.pilottv.remote.SessionResult
import pl.pilottv.remote.TvState
import pl.pilottv.remote.textEdit
import java.io.BufferedInputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
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

    /** Pakiet aplikacji na pierwszym planie. Dekoder zgłasza go przy połączeniu i po każdej zmianie. */
    private val _currentApp = MutableStateFlow<String?>(null)
    val currentApp: StateFlow<String?> = _currentApp.asStateFlow()

    /**
     * Link, po którym dekoder zerwał połączenie. Usługa pilota zamyka połączenie,
     * gdy żadna aplikacja nie obsługuje linku (ActivityNotFoundException w logach dekodera).
     */
    private val _linkFailed = MutableSharedFlow<String>(extraBufferCapacity = 1)
    val linkFailed: SharedFlow<String> = _linkFailed.asSharedFlow()

    @Volatile
    private var lastLink: String? = null

    @Volatile
    private var lastLinkAt = 0L

    private val sessionIds = AtomicInteger()

    @Volatile
    private var activeId = 0

    @Volatile
    private var output: OutputStream? = null

    private val writeLock = Any()

    // Zapis do gniazda nie może iść z wątku UI.
    private val writer = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "atv-writer").apply { isDaemon = true } }

    @Volatile
    private var imeCounter = 0

    @Volatile
    private var imeFieldCounter = 0

    /** Liczniki przyszły z IME_KEY_INJECT lub IME_SHOW_REQUEST (pewne źródło w tej sesji). */
    @Volatile
    private var countersFromField = false

    /**
     * Tekst w polu na dekoderze, tak jak go znamy. Dekoder dopisuje tekst z IME_BATCH_EDIT
     * na końcu pola (nie zastępuje go), więc wysyłamy tylko różnicę.
     */
    @Volatile
    private var fieldText = ""

    @Volatile
    private var lastTextSentAt = 0L

    /*
     * Po każdej zmianie w polu dekoder zmienia licznik pola (IME_SHOW_REQUEST) i odrzuca wstawienie
     * ze starym licznikiem. Dlatego następne wstawienie czeka, aż kursor dojdzie do [expectedCursor]
     * (wszystkie wysłane zmiany są wykonane), najpóźniej PENDING_TIMEOUT_MS.
     * Pola chroni [pendingLock].
     */
    private val pendingLock = Any()

    /** Pozycja kursora po wykonaniu wszystkich wysłanych zmian. null = nic nie czeka na potwierdzenie. */
    private var expectedCursor: Int? = null
    private var pendingInsert: String? = null
    private var enterAfterInsert = false
    private var pendingId = 0

    /** Czas ostatniego potwierdzenia zmiany (IME_SHOW_REQUEST) lub wysłania tekstu. */
    private var lastProgressAt = 0L

    fun markIdle() {
        activeId = sessionIds.incrementAndGet()
        output = null
        _state.value = TvState.Idle
        _currentApp.value = null
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
        countersFromField = false
        synchronized(pendingLock) { clearPending() }

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
                val link = lastLink
                if (active && link != null && System.currentTimeMillis() - lastLinkAt < LINK_FAIL_WINDOW_MS) {
                    lastLink = null
                    _linkFailed.tryEmit(link)
                }
                if (activeId == id) _currentApp.value = null
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
                    // Dekoder Canal+ wysyła tu stałe liczniki (1, 0) i odrzuca zmiany z nimi.
                    // Prawidłowe liczniki są w IME_KEY_INJECT i IME_SHOW_REQUEST, więc te tylko jako zapas.
                    if (!countersFromField) {
                        imeCounter = edit.int(1)?.toInt() ?: imeCounter
                        imeFieldCounter = edit.int(2)?.toInt() ?: imeFieldCounter
                    }
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
                val inject = runCatching { msg.message(IME_KEY_INJECT) }.getOrNull()
                // app_info(1) → counter(1): licznik IME. text_field_status(2) → counter_field(1): licznik pola.
                // Dekoder przyjmuje IME_BATCH_EDIT tylko z tymi wartościami („Ignoring edit” w logach).
                runCatching { inject?.message(1)?.int(1) }.getOrNull()?.let {
                    imeCounter = it.toInt()
                    countersFromField = true
                }
                runCatching { inject?.message(2)?.int(1) }.getOrNull()?.let {
                    imeFieldCounter = it.toInt()
                    countersFromField = true
                    // Nowe pole: czekające zmiany dotyczyły poprzedniego.
                    synchronized(pendingLock) { clearPending() }
                }
                // app_info(1) → app_package(12): aplikacja na pierwszym planie.
                val pkg = runCatching { inject?.message(1)?.string(12) }.getOrNull()
                if (pkg != null) {
                    _currentApp.value = pkg.ifEmpty { null }
                    // Aplikacja się zmieniła, więc wysłany link zadziałał.
                    lastLink = null
                }
                // text_field_status(2) → value(2): zawartość pola po przejściu do niego.
                val text = runCatching { inject?.message(2)?.string(2) }.getOrNull()
                if (text != null) {
                    fieldText = text
                    _ime.tryEmit(ImeEvent.Updated(text))
                }
            }
            msg.has(IME_SHOW_REQUEST) -> {
                // Dekoder wysyła to po otwarciu pola i po każdej zmianie tekstu (też naszej).
                // text_field_status(2) → counter_field(1): nowy licznik pola po każdej zmianie.
                val status = runCatching { msg.message(IME_SHOW_REQUEST)?.message(2) }.getOrNull()
                runCatching { status?.int(1) }.getOrNull()?.let {
                    imeFieldCounter = it.toInt()
                    countersFromField = true
                }
                // start(3): pozycja kursora. Gdy wysłane zmiany są wykonane, wysyłamy czekające wstawienie.
                runCatching { status?.int(3) }.getOrNull()?.let { cursor ->
                    synchronized(pendingLock) {
                        lastProgressAt = System.currentTimeMillis()
                        if (expectedCursor == cursor.toInt()) {
                            expectedCursor = null
                            flushPending()
                        }
                    }
                }
                val text = runCatching { status?.string(2) }.getOrNull()
                // Stan po naszej starszej zmianie mógłby nadpisać nowszy tekst i zdublować znaki.
                if (System.currentTimeMillis() - lastTextSentAt > ECHO_GUARD_MS) fieldText = text ?: ""
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
        val link = if ("://" in linkOrPackage) linkOrPackage else "$HELPER_LINK$linkOrPackage"
        val sent = enqueue(proto { message(APP_LINK_LAUNCH) { string(1, link) } })
        if (sent) {
            lastLink = link
            lastLinkAt = System.currentTimeMillis()
        }
        return sent
    }

    /**
     * Ustawia tekst w polu na dekoderze (pusty tekst czyści pole).
     * Wysyła tylko różnicę: Backspace dla usuniętych znaków i wstawienie nowych.
     */
    fun sendText(text: String): Boolean {
        if (output == null) return false
        synchronized(pendingLock) {
            // Czekające wstawienie jest na końcu fieldText i jeszcze nie trafiło do pola, więc liczymy bez niego.
            val base = pendingInsert?.let { fieldText.dropLast(it.length) } ?: fieldText
            pendingInsert = null
            val edit = textEdit(base, text)
            repeat(edit.deleteCodePoints) { if (!sendKey(KEYCODE_DEL)) return false }
            if (edit.deleteCodePoints > 0) expectedCursor = text.length - edit.insert.length
            fieldText = text
            lastTextSentAt = System.currentTimeMillis()
            if (edit.insert.isNotEmpty()) {
                pendingInsert = edit.insert
                if (expectedCursor == null) {
                    flushPending()
                } else {
                    lastProgressAt = System.currentTimeMillis()
                    schedulePendingTimeout()
                }
            }
        }
        return true
    }

    /**
     * Gdy dekoder przez PENDING_TIMEOUT_MS nie potwierdza zmian (np. kursor przesunięty na TV),
     * wysyłamy wstawienie z najnowszym licznikiem. Dłuższe kasowanie trwa, dopóki przychodzą potwierdzenia.
     */
    private fun schedulePendingTimeout() {
        val id = ++pendingId
        writer.schedule(Runnable {
            synchronized(pendingLock) {
                if (pendingId == id && pendingInsert != null) {
                    if (System.currentTimeMillis() - lastProgressAt < PENDING_TIMEOUT_MS) {
                        schedulePendingTimeout()
                    } else {
                        expectedCursor = null
                        flushPending()
                    }
                }
            }
        }, PENDING_TIMEOUT_MS, TimeUnit.MILLISECONDS)
    }

    /** Zatwierdza tekst (Enter). Gdy wstawienie czeka na potwierdzenie, Enter idzie po nim. */
    fun submitText(): Boolean = synchronized(pendingLock) {
        if (pendingInsert != null) {
            enterAfterInsert = true
            true
        } else {
            sendKey(KEYCODE_ENTER)
        }
    }

    /** Wywołanie tylko pod [pendingLock]. */
    private fun flushPending() {
        val insert = pendingInsert ?: return
        pendingInsert = null
        enqueue(insertMessage(insert))
        expectedCursor = fieldText.length
        lastTextSentAt = System.currentTimeMillis()
        if (enterAfterInsert) {
            enterAfterInsert = false
            sendKey(KEYCODE_ENTER)
        }
    }

    /** Wywołanie tylko pod [pendingLock]. */
    private fun clearPending() {
        pendingInsert = null
        expectedCursor = null
        enterAfterInsert = false
        pendingId++
    }

    /** Wstawienie tekstu w miejscu kursora. Liczniki muszą być aktualne w chwili budowy wiadomości. */
    private fun insertMessage(insert: String): ByteArray = proto {
        message(IME_BATCH_EDIT) {
            int(1, imeCounter)
            int(2, imeFieldCounter)
            message(3) {
                int(1, 1)
                message(2) {
                    int(1, insert.length - 1)
                    int(2, insert.length - 1)
                    string(3, insert)
                }
            }
        }
    }

    companion object {
        /** Link obsługiwany przez aplikację pomocniczą (moduł tvhelper). */
        const val HELPER_LINK = "pilottv://launch?app="

        private const val LINK_FAIL_WINDOW_MS = 3_000L
        private const val REMOTE_PORT = 6466
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
        const val KEYCODE_ENTER = 66
        const val ECHO_GUARD_MS = 1_500L
        const val PENDING_TIMEOUT_MS = 1_000L
    }
}
