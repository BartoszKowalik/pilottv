package pl.pilottv

import android.app.Application
import android.os.SystemClock
import android.widget.Toast
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import pl.pilottv.data.AppPrefs
import pl.pilottv.data.HapticLevel
import pl.pilottv.data.LayoutMode
import pl.pilottv.data.ThemeMode
import pl.pilottv.data.SettingsStore
import pl.pilottv.data.TvConfig
import pl.pilottv.net.NetUtils
import pl.pilottv.net.WakeOnLan
import pl.pilottv.samsung.FoundTv
import pl.pilottv.samsung.SamsungDiscovery
import pl.pilottv.samsung.SamsungKeys
import pl.pilottv.samsung.SamsungRemote
import pl.pilottv.samsung.SessionEnd
import pl.pilottv.samsung.TvState

/** [loaded] = false, dopóki DataStore nie odczyta ustawień (unikamy mignięcia ekranu konfiguracji). */
data class ConfigState(val loaded: Boolean, val config: TvConfig?)

class RemoteViewModel(app: Application) : AndroidViewModel(app) {

    private val settings = SettingsStore(app)
    private val remote = SamsungRemote(TV_CLIENT_NAME)

    val tvState: StateFlow<TvState> = remote.state
    val keyboardOnTv: SharedFlow<Boolean> = remote.keyboardOnTv

    val config: StateFlow<ConfigState> = settings.tvConfig
        .map { ConfigState(loaded = true, config = it) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ConfigState(loaded = false, config = null))

    val prefs: StateFlow<AppPrefs> = settings.prefs
        .stateIn(viewModelScope, SharingStarted.Eagerly, AppPrefs())

    private val _scanning = MutableStateFlow(false)
    val scanning: StateFlow<Boolean> = _scanning.asStateFlow()

    private val _found = MutableStateFlow<List<FoundTv>>(emptyList())
    val found: StateFlow<List<FoundTv>> = _found.asStateFlow()

    private var started = false
    private var connectionJob: Job? = null
    private var fastRetryUntil = 0L

    fun onStart() {
        started = true
        restartConnection()
    }

    fun onStop() {
        started = false
        connectionJob?.cancel()
        connectionJob = null
        remote.markIdle()
    }

    /** Pętla połączenia: łączy, a po zerwaniu próbuje ponownie z rosnącym odstępem. */
    fun restartConnection() {
        connectionJob?.cancel()
        if (!started) return
        connectionJob = viewModelScope.launch {
            var backoff = MIN_BACKOFF_MS
            while (isActive) {
                val cfg = settings.tvConfig.first()
                if (cfg == null) {
                    remote.markIdle()
                    return@launch
                }
                val result = remote.session(cfg) { token, pin ->
                    viewModelScope.launch { settings.savePairing(token, pin) }
                }
                // Te przypadki wymagają decyzji użytkownika, więc nie ponawiamy.
                if (result.end == SessionEnd.DENIED || result.end == SessionEnd.CERT_CHANGED) return@launch
                if (result.wasConnected) backoff = MIN_BACKOFF_MS
                val fastRetry = SystemClock.elapsedRealtime() < fastRetryUntil
                delay(if (fastRetry) MIN_BACKOFF_MS else backoff)
                backoff = (backoff * 2).coerceAtMost(MAX_BACKOFF_MS)
            }
        }
    }

    fun send(key: String) {
        remote.sendKey(key)
    }

    fun sendText(text: String) {
        if (!remote.sendText(text)) toast("Brak połączenia z TV.")
    }

    fun endInput() {
        remote.endInput()
    }

    /** Gdy TV jest połączony: KEY_POWER. Gdy nie: Wake-on-LAN i szybkie ponawianie połączenia. */
    fun power() {
        if (remote.state.value == TvState.Connected) {
            remote.sendKey(SamsungKeys.POWER)
            return
        }
        val mac = config.value.config?.mac
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
            restartConnection()
        }
    }

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
            restartConnection()
        }
    }

    fun selectManualIp(input: String) {
        val ip = input.trim()
        viewModelScope.launch {
            val info = SamsungDiscovery.fetchInfo(ip)
            if (info == null) toast("Nie odczytano danych TV. Zapisano tylko adres IP.")
            settings.saveTv(ip, info?.name, info?.mac?.let(WakeOnLan::normalizeMac))
            restartConnection()
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
            restartConnection()
        }
    }

    fun forgetTv() {
        viewModelScope.launch {
            settings.forgetTv()
            _found.value = emptyList()
            restartConnection()
        }
    }

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

    private fun toast(message: String) {
        Toast.makeText(getApplication(), message, Toast.LENGTH_SHORT).show()
    }

    private companion object {
        /** Nazwa widoczna na TV w oknie „Zezwól”. Zmiana wymaga ponownej zgody na TV. */
        const val TV_CLIENT_NAME = "Pilot TV"
        const val MIN_BACKOFF_MS = 1_000L
        const val MAX_BACKOFF_MS = 15_000L
        const val WAKE_RETRY_WINDOW_MS = 30_000L
    }
}
