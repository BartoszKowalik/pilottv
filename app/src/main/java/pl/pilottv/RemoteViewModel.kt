package pl.pilottv

import android.app.Application
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import pl.pilottv.atv.AtvKeys
import pl.pilottv.atv.AtvPairing
import pl.pilottv.atv.AtvRemote
import pl.pilottv.atv.ClientIdentity
import pl.pilottv.atv.WrongCodeException
import pl.pilottv.data.AppPrefs
import pl.pilottv.data.AtvConfig
import pl.pilottv.data.HapticLevel
import pl.pilottv.data.LayoutMode
import pl.pilottv.data.SettingsStore
import pl.pilottv.data.ThemeMode
import pl.pilottv.data.TvBrand
import pl.pilottv.data.TvConfig
import pl.pilottv.lg.LgRemote
import pl.pilottv.remote.TvClient
import pl.pilottv.net.NetUtils
import pl.pilottv.net.WakeOnLan
import pl.pilottv.net.parseIpv4
import pl.pilottv.remote.AppShortcut
import pl.pilottv.remote.ImeEvent
import pl.pilottv.remote.Key
import pl.pilottv.remote.Target
import pl.pilottv.samsung.FoundTv
import pl.pilottv.samsung.SamsungDiscovery
import pl.pilottv.samsung.SamsungRemote
import pl.pilottv.samsung.SessionEnd
import pl.pilottv.samsung.SessionResult
import pl.pilottv.samsung.TvState

/** [loaded] = false, dopóki DataStore nie odczyta ustawień (unikamy mignięcia ekranu konfiguracji). */
data class ConfigState(val loaded: Boolean, val tv: TvConfig?, val atv: AtvConfig?) {
    val hasAnyDevice: Boolean get() = tv != null || atv != null
}

/** Wybrane urządzenie, z uwzględnieniem tego, co jest skonfigurowane. */
fun resolveTarget(preferred: Target, config: ConfigState): Target = when {
    preferred == Target.DECODER && config.atv != null -> Target.DECODER
    config.tv != null -> Target.TV
    config.atv != null -> Target.DECODER
    else -> Target.TV
}

sealed interface PairingUi {
    data object Idle : PairingUi
    data object Connecting : PairingUi
    data class EnterCode(val deviceName: String, val error: String? = null) : PairingUi
    data object Verifying : PairingUi
}

class RemoteViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsStore(app)
    private val identity by lazy { ClientIdentity.load(app) }
    private val samsung = SamsungRemote(CLIENT_NAME)
    private val lg = LgRemote(CLIENT_NAME)
    private val atv = AtvRemote({ identity }, CLIENT_NAME)

    val atvState: StateFlow<TvState> = atv.state

    /** Pakiet aplikacji otwartej na dekoderze (null, gdy nieznany). */
    val atvCurrentApp: StateFlow<String?> = atv.currentApp

    init {
        viewModelScope.launch {
            atv.linkFailed.collect { link ->
                toast(
                    if (link.startsWith(AtvRemote.HELPER_LINK)) {
                        // Brak samej aplikacji obsługuje pomocnik (komunikat na TV), więc tu brakuje pomocnika.
                        "Na dekoderze brak aplikacji „Pilot TV – pomocnik”. Zainstaluj pilot-tv-helper."
                    } else {
                        "Dekoder nie ma aplikacji, która otwiera ten link."
                    },
                )
            }
        }
    }
    val ime: Flow<ImeEvent> = merge(samsung.ime, lg.ime, atv.ime)

    val shortcuts: StateFlow<List<AppShortcut>> = settings.shortcuts
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppShortcut.DEFAULTS)

    val config: StateFlow<ConfigState> = combine(settings.tvConfig, settings.atvConfig) { tv, atvCfg ->
        ConfigState(loaded = true, tv = tv, atv = atvCfg)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ConfigState(loaded = false, tv = null, atv = null))

    val prefs: StateFlow<AppPrefs> = settings.prefs
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppPrefs())

    /** Stan klienta, który odpowiada marce zapisanego TV. */
    val tvState: StateFlow<TvState> = combine(samsung.state, lg.state, config) { s, l, c ->
        if (c.tv?.brand == TvBrand.LG) l else s
    }.stateIn(viewModelScope, SharingStarted.Eagerly, TvState.Idle)

    private fun clientFor(brand: TvBrand?): TvClient = if (brand == TvBrand.LG) lg else samsung

    private fun tvClient(): TvClient = clientFor(config.value.tv?.brand)

    private val _scanningLg = MutableStateFlow(false)
    val scanningLg: StateFlow<Boolean> = _scanningLg.asStateFlow()

    private val _foundLg = MutableStateFlow<List<String>>(emptyList())
    val foundLg: StateFlow<List<String>> = _foundLg.asStateFlow()

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _found = MutableStateFlow<List<FoundTv>>(emptyList())
    val found: StateFlow<List<FoundTv>> = _found.asStateFlow()

    private val _scanningAtv = MutableStateFlow(false)
    val scanningAtv: StateFlow<Boolean> = _scanningAtv.asStateFlow()

    private val _foundAtv = MutableStateFlow<List<String>>(emptyList())
    val foundAtv: StateFlow<List<String>> = _foundAtv.asStateFlow()

    private val _pairing = MutableStateFlow<PairingUi>(PairingUi.Idle)
    val pairing: StateFlow<PairingUi> = _pairing.asStateFlow()
    private var pairingSession: AtvPairing? = null
    private var pairingIp: String? = null
    private var pairingName: String? = null

    private var started = false
    private var tvJob: Job? = null
    private var atvJob: Job? = null
    private var fastRetryUntil = 0L

    fun onStart() {
        started = true
        restartConnection()
    }

    fun onStop() {
        started = false
        tvJob?.cancel()
        atvJob?.cancel()
        tvJob = null
        atvJob = null
        samsung.markIdle()
        lg.markIdle()
        atv.markIdle()
    }

    fun restartConnection() {
        restartTv()
        restartAtv()
    }

    private fun restartTv() {
        tvJob?.cancel()
        if (!started) return
        tvJob = connectionLoop(
            config = { settings.tvConfig.first() },
            idle = {
                samsung.markIdle()
                lg.markIdle()
            },
        ) { cfg ->
            clientFor(cfg.brand).session(cfg) { u ->
                viewModelScope.launch {
                    settings.saveTvUpdate(u.token, u.certPin, u.mac?.let(WakeOnLan::normalizeMac))
                }
            }
        }
    }

    private fun restartAtv() {
        atvJob?.cancel()
        if (!started) return
        atvJob = connectionLoop(
            config = { settings.atvConfig.first() },
            idle = atv::markIdle,
        ) { cfg ->
            atv.session(cfg) { pin -> viewModelScope.launch { settings.saveAtvPin(pin) } }
        }
    }

    /** Łączy, a po zerwaniu próbuje ponownie z rosnącym odstępem. */
    private fun <C : Any> connectionLoop(
        config: suspend () -> C?,
        idle: () -> Unit,
        session: suspend (C) -> SessionResult,
    ): Job = viewModelScope.launch {
        var backoff = MIN_BACKOFF_MS
        while (isActive) {
            val cfg = config()
            if (cfg == null) {
                idle()
                return@launch
            }
            // Siatka bezpieczeństwa: nieoczekiwany wyjątek klienta nie może zamknąć aplikacji.
            val result = try {
                session(cfg)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                idle()
                SessionResult(SessionEnd.UNREACHABLE, false)
            }
            // Te przypadki wymagają decyzji użytkownika, więc nie ponawiamy.
            if (result.end in STOP_ENDS) return@launch
            if (result.wasConnected) backoff = MIN_BACKOFF_MS
            val fastRetry = SystemClock.elapsedRealtime() < fastRetryUntil
            delay(if (fastRetry) MIN_BACKOFF_MS else backoff)
            backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
        }
    }

    private fun activeTarget(): Target = resolveTarget(prefs.value.target, config.value)

    /** Głośność idzie do Samsunga, gdy tak ustawiono. Reszta do wybranego urządzenia. */
    private fun targetFor(key: Key): Target =
        if (key in Key.VOLUME && prefs.value.volumeToTv && config.value.tv != null) Target.TV else activeTarget()

    fun send(key: Key) {
        when (targetFor(key)) {
            Target.TV -> tvClient().sendKey(key)
            Target.DECODER -> atv.sendKey(AtvKeys.of(key))
        }
    }

    /** Czy przyciski głośności telefonu mają teraz sterować urządzeniem. */
    fun canHandleVolumeKeys(): Boolean {
        if (!prefs.value.volumeKeys) return false
        val state = when (targetFor(Key.VOL_UP)) {
            Target.TV -> tvState.value
            Target.DECODER -> atvState.value
        }
        return state == TvState.Connected
    }

    fun setTarget(target: Target) {
        viewModelScope.launch { settings.setTarget(target) }
    }

    /** Zastępuje cały tekst w polu tekstowym na urządzeniu. */
    fun sendText(text: String): Boolean = when (activeTarget()) {
        Target.TV -> tvClient().sendText(text)
        Target.DECODER -> atv.sendText(text)
    }

    fun launchShortcut(shortcut: AppShortcut) {
        if (activeTarget() != Target.DECODER) {
            toast("Skróty aplikacji działają na dekoderze.")
            return
        }
        if (!atv.launchApp(shortcut.app)) toast("Dekoder nie odpowiada.")
    }

    fun addShortcut(name: String, app: String) {
        val n = name.trim()
        // Akceptujemy też cały link ze Sklepu Play: ...details?id=com.example.app
        val raw = app.trim()
        val a = if ("play.google.com" in raw || "market://" in raw) {
            raw.substringAfter("id=").substringBefore("&")
        } else {
            raw
        }
        if (n.isEmpty() || a.isEmpty()) return
        viewModelScope.launch { settings.saveShortcuts(shortcuts.value + AppShortcut(n, a)) }
    }

    fun removeShortcut(shortcut: AppShortcut) {
        viewModelScope.launch { settings.saveShortcuts(shortcuts.value - shortcut) }
    }

    /** Przesuwa skrót o [delta] pozycji (-1 = wyżej, 1 = niżej). */
    fun moveShortcut(shortcut: AppShortcut, delta: Int) {
        val list = shortcuts.value.toMutableList()
        val from = list.indexOf(shortcut)
        val to = from + delta
        if (from < 0 || to !in list.indices) return
        list.add(to, list.removeAt(from))
        viewModelScope.launch { settings.saveShortcuts(list) }
    }

    fun resetShortcuts() {
        viewModelScope.launch { settings.saveShortcuts(null) }
    }

    fun endInput() {
        when (activeTarget()) {
            Target.TV -> tvClient().endInput()
            Target.DECODER -> atv.sendKey(KeyEvent.KEYCODE_ENTER)
        }
    }

    /**
     * Urządzenie połączone: klawisz zasilania (TV wyłącza się, dekoder przełącza stan).
     * Urządzenie niepołączone: Wake-on-LAN i szybkie ponawianie połączenia.
     */
    fun power() {
        if (activeTarget() == Target.DECODER) {
            val mac = config.value.atv?.mac
            if (atvState.value == TvState.Connected && atv.sendKey(AtvKeys.of(Key.POWER))) {
                // Po przejściu w czuwanie połączenie wygląda na aktywne jeszcze do 20 s i klawisz ginie.
                // Pakiet WoL do włączonego dekodera nic nie zmienia, więc wysyłamy go zawsze.
                if (mac != null) {
                    viewModelScope.launch {
                        runCatching { WakeOnLan.send(mac, NetUtils.broadcastAddress(getApplication())) }
                    }
                }
                return
            }
            if (mac == null) {
                toast("Dekoder nie odpowiada. Aby go włączyć, wpisz MAC dekodera w ustawieniach.")
                return
            }
            wake(mac, "Włączam dekoder…", ::restartAtv)
            return
        }
        if (tvState.value == TvState.Connected) {
            tvClient().sendKey(Key.POWER)
            return
        }
        val mac = config.value.tv?.mac
        if (mac == null) {
            toast("Brak adresu MAC. Nie można włączyć TV. Uzupełnij MAC w ustawieniach.")
            return
        }
        wake(mac, "Włączam TV…", ::restartTv)
    }

    private fun wake(mac: String, message: String, reconnect: () -> Unit) {
        viewModelScope.launch {
            runCatching { WakeOnLan.send(mac, NetUtils.broadcastAddress(getApplication())) }
                .onFailure {
                    toast("Nie udało się wysłać sygnału Wake-on-LAN.")
                    return@launch
                }
            toast(message)
            fastRetryUntil = SystemClock.elapsedRealtime() + WAKE_RETRY_WINDOW_MS
            reconnect()
        }
    }

    // --- Samsung: konfiguracja ---

    fun scan() {
        if (_scanning.value) return
        viewModelScope.launch {
            _scanning.value = true
            val result = runCatching { SamsungDiscovery.scan(getApplication()) }.getOrDefault(emptyList())
            _found.value = result
            _scanning.value = false
            if (result.isEmpty()) toast("Nie znaleziono TV. Wpisz adres IP ręcznie.")
        }
    }

    fun selectTv(tv: FoundTv) {
        viewModelScope.launch {
            settings.saveTv(tv.ip, tv.name, tv.mac?.let(WakeOnLan::normalizeMac), TvBrand.SAMSUNG)
            settings.setTarget(Target.TV)
            restartTv()
        }
    }

    /** Zwraca false, gdy adres jest niepoprawny. Wtedy nic nie zapisuje. */
    fun selectManualIp(input: String, brand: TvBrand): Boolean {
        val ip = parseIpv4(input)
        if (ip == null) {
            toast(BAD_IP_MESSAGE)
            return false
        }
        if (brand == TvBrand.LG) {
            selectLg(ip)
            return true
        }
        viewModelScope.launch {
            val info = SamsungDiscovery.fetchInfo(ip)
            if (info == null) toast("Nie odczytano danych TV. Zapisano tylko adres IP.")
            settings.saveTv(ip, info?.name, info?.mac?.let(WakeOnLan::normalizeMac), TvBrand.SAMSUNG)
            restartTv()
        }
        return true
    }

    fun scanLg() {
        if (_scanningLg.value) return
        viewModelScope.launch {
            _scanningLg.value = true
            val result = runCatching {
                NetUtils.scanSubnet(getApplication(), LG_PORT) { it }
            }.getOrDefault(emptyList())
            _foundLg.value = result
            _scanningLg.value = false
            if (result.isEmpty()) toast("Nie znaleziono TV LG. Wpisz adres IP ręcznie.")
        }
    }

    /** MAC do Wake-on-LAN LG poda sam po pierwszym połączeniu. */
    fun selectLg(ip: String) {
        viewModelScope.launch {
            settings.saveTv(ip.trim(), "LG webOS TV", null, TvBrand.LG)
            settings.setTarget(Target.TV)
            restartTv()
        }
    }

    fun saveMac(input: String) {
        val mac = if (input.isBlank()) null else WakeOnLan.normalizeMac(input)
        if (input.isNotBlank() && mac == null) {
            toast("Niepoprawny format MAC. Przykład: AA:BB:CC:DD:EE:FF")
            return
        }
        viewModelScope.launch {
            settings.saveMac(mac)
            toast("Zapisano MAC.")
        }
    }

    fun resetPairing() {
        viewModelScope.launch {
            settings.clearPairing()
            restartTv()
        }
    }

    fun forgetTv() {
        viewModelScope.launch {
            settings.forgetTv()
            _found.value = emptyList()
            restartTv()
        }
    }

    // --- Dekoder Android TV: konfiguracja ---

    fun scanAtv() {
        if (_scanningAtv.value) return
        viewModelScope.launch {
            _scanningAtv.value = true
            val result = runCatching {
                NetUtils.scanSubnet(getApplication(), ATV_REMOTE_PORT) { it }
            }.getOrDefault(emptyList())
            _foundAtv.value = result
            _scanningAtv.value = false
            if (result.isEmpty()) toast("Nie znaleziono dekodera. Wpisz adres IP ręcznie.")
        }
    }

    /** Krok 1: połączenie z dekoderem. Dekoder pokazuje kod na ekranie. */
    fun startAtvPairing(input: String) {
        val ip = parseIpv4(input)
        if (ip == null) {
            toast(BAD_IP_MESSAGE)
            return
        }
        cancelPairing()
        _pairing.value = PairingUi.Connecting
        viewModelScope.launch {
            val session = AtvPairing({ identity }, ip, CLIENT_NAME)
            pairingSession = session
            try {
                val name = session.start()
                pairingIp = ip
                pairingName = name
                _pairing.value = PairingUi.EnterCode(name ?: ip)
            } catch (e: Exception) {
                session.close()
                if (pairingSession === session) pairingSession = null
                _pairing.value = PairingUi.Idle
                toast("Nie udało się połączyć z dekoderem.")
            }
        }
    }

    /** Krok 2: kod z ekranu dekodera. */
    fun submitPairingCode(code: String) {
        val session = pairingSession ?: return
        val ip = pairingIp ?: return
        val displayName = pairingName ?: ip
        viewModelScope.launch {
            _pairing.value = PairingUi.Verifying
            try {
                session.finish(code)
                session.close()
                pairingSession = null
                settings.saveAtv(ip, pairingName)
                settings.setTarget(Target.DECODER)
                _pairing.value = PairingUi.Idle
                toast("Sparowano dekoder.")
                restartAtv()
            } catch (e: WrongCodeException) {
                _pairing.value = PairingUi.EnterCode(displayName, "Błędny kod. Sprawdź kod na ekranie i spróbuj ponownie.")
            } catch (e: Exception) {
                session.close()
                pairingSession = null
                _pairing.value = PairingUi.Idle
                toast("Parowanie nie powiodło się. Spróbuj ponownie.")
            }
        }
    }

    fun cancelPairing() {
        pairingSession?.close()
        pairingSession = null
        _pairing.value = PairingUi.Idle
    }

    fun saveAtvMac(input: String) {
        val mac = if (input.isBlank()) null else WakeOnLan.normalizeMac(input)
        if (input.isNotBlank() && mac == null) {
            toast("Niepoprawny format MAC. Przykład: AA:BB:CC:DD:EE:FF")
            return
        }
        viewModelScope.launch {
            settings.saveAtvMac(mac)
            toast("Zapisano MAC dekodera.")
        }
    }

    fun forgetAtv() {
        viewModelScope.launch {
            settings.forgetAtv()
            _foundAtv.value = emptyList()
            restartAtv()
        }
    }

    fun setVolumeToTv(enabled: Boolean) {
        viewModelScope.launch { settings.setVolumeToTv(enabled) }
    }

    // --- Ustawienia pilota ---

    fun setHaptics(level: HapticLevel) {
        viewModelScope.launch { settings.setHaptics(level) }
    }

    fun setTheme(mode: ThemeMode) {
        viewModelScope.launch { settings.setTheme(mode) }
    }

    fun setLayout(mode: LayoutMode) {
        viewModelScope.launch { settings.setLayout(mode) }
    }

    fun setTouchpad(enabled: Boolean) {
        viewModelScope.launch { settings.setTouchpad(enabled) }
    }

    fun setVolumeKeys(enabled: Boolean) {
        viewModelScope.launch { settings.setVolumeKeys(enabled) }
    }

    override fun onCleared() {
        pairingSession?.close()
    }

    private fun toast(message: String) {
        Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        /** Nazwa widoczna na TV i dekoderze przy parowaniu. Zmiana wymaga ponownej zgody na TV. */
        const val CLIENT_NAME = "Pilot TV"
        const val ATV_REMOTE_PORT = 6466
        const val LG_PORT = 3000
        const val MIN_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 15_000L
        const val WAKE_RETRY_WINDOW_MS = 30_000L
        const val BAD_IP_MESSAGE = "Niepoprawny adres IP. Przykład: 192.168.1.50"
        val STOP_ENDS = setOf(SessionEnd.DENIED, SessionEnd.CERT_CHANGED, SessionEnd.NOT_PAIRED, SessionEnd.BAD_ADDRESS)
    }
}
