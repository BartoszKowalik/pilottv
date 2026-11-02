package pl.pilottv

import android.app.Application
import android.os.SystemClock
import android.view.KeyEvent
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
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
import pl.pilottv.data.TvConfig
import pl.pilottv.net.NetUtils
import pl.pilottv.net.WakeOnLan
import pl.pilottv.remote.Key
import pl.pilottv.remote.Target
import pl.pilottv.samsung.FoundTv
import pl.pilottv.samsung.SamsungDiscovery
import pl.pilottv.samsung.SamsungKeys
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
    private val atv = AtvRemote({ identity }, CLIENT_NAME)

    val tvState: StateFlow<TvState> = samsung.state
    val atvState: StateFlow<TvState> = atv.state
    val keyboardOnTv: Flow<Boolean> = merge(samsung.keyboardOnTv, atv.keyboardOnTv)

    val config: StateFlow<ConfigState> = combine(settings.tvConfig, settings.atvConfig) { tv, atvCfg ->
        ConfigState(loaded = true, tv = tv, atv = atvCfg)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ConfigState(loaded = false, tv = null, atv = null))

    val prefs: StateFlow<AppPrefs> = settings.prefs
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppPrefs())

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
            idle = samsung::markIdle,
        ) { cfg ->
            samsung.session(cfg) { token, pin -> viewModelScope.launch { settings.savePairing(token, pin) } }
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
            val result = session(cfg)
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
            Target.TV -> samsung.sendKey(SamsungKeys.of(key))
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

    fun sendText(text: String) {
        val sent = when (activeTarget()) {
            Target.TV -> samsung.sendText(text)
            Target.DECODER -> atv.sendText(text)
        }
        if (!sent) toast("Brak połączenia z urządzeniem.")
    }

    fun endInput() {
        when (activeTarget()) {
            Target.TV -> samsung.endInput()
            Target.DECODER -> atv.sendKey(KeyEvent.KEYCODE_ENTER)
        }
    }

    /**
     * Dekoder: KEYCODE_POWER. Samsung połączony: KEY_POWER.
     * Samsung niepołączony: Wake-on-LAN i szybkie ponawianie połączenia.
     */
    fun power() {
        if (activeTarget() == Target.DECODER) {
            if (!atv.sendKey(AtvKeys.of(Key.POWER))) toast("Dekoder nie odpowiada.")
            return
        }
        if (samsung.state.value == TvState.Connected) {
            samsung.sendKey(SamsungKeys.of(Key.POWER))
            return
        }
        val mac = config.value.tv?.mac
        if (mac == null) {
            toast("Brak adresu MAC. Nie można włączyć TV. Uzupełnij MAC w ustawieniach.")
            return
        }
        viewModelScope.launch {
            runCatching { WakeOnLan.send(mac, NetUtils.broadcastAddress(getApplication())) }
                .onFailure {
                    toast("Nie udało się wysłać sygnału Wake-on-LAN.")
                    return@launch
                }
            toast("Włączam TV…")
            fastRetryUntil = SystemClock.elapsedRealtime() + WAKE_RETRY_WINDOW_MS
            restartTv()
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
            settings.saveTv(tv.ip, tv.name, tv.mac?.let(WakeOnLan::normalizeMac))
            settings.setTarget(Target.TV)
            restartTv()
        }
    }

    fun selectManualIp(input: String) {
        val ip = input.trim()
        viewModelScope.launch {
            val info = SamsungDiscovery.fetchInfo(ip)
            if (info == null) toast("Nie odczytano danych TV. Zapisano tylko adres IP.")
            settings.saveTv(ip, info?.name, info?.mac?.let(WakeOnLan::normalizeMac))
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
        val ip = input.trim()
        if (ip.isEmpty()) return
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
        const val MIN_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 15_000L
        const val WAKE_RETRY_WINDOW_MS = 30_000L
        val STOP_ENDS = setOf(SessionEnd.DENIED, SessionEnd.CERT_CHANGED, SessionEnd.NOT_PAIRED)
    }
}
