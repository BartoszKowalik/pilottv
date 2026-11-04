package pl.pilottv.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import org.json.JSONArray
import org.json.JSONObject
import pl.pilottv.remote.AppShortcut
import pl.pilottv.remote.Target

private val Context.dataStore by preferencesDataStore(name = "settings")

enum class TvBrand { SAMSUNG, LG }

/**
 * Zapisany telewizor. [token] i [certPin] pojawiają się po sparowaniu.
 * [token]: Samsung – token z TV, LG – client-key.
 */
data class TvConfig(
    val ip: String,
    val name: String?,
    val mac: String?,
    val token: String?,
    val certPin: String?,
    val brand: TvBrand = TvBrand.SAMSUNG,
)

/**
 * Sparowany dekoder Android TV. [certPin] zapisujemy przy pierwszym połączeniu po parowaniu.
 * [mac] wpisuje użytkownik (do Wake-on-LAN). Protokół pilota nie podaje MAC.
 */
data class AtvConfig(
    val ip: String,
    val name: String?,
    val certPin: String?,
    val mac: String? = null,
)

enum class HapticLevel { OFF, LIGHT, MEDIUM, STRONG }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class LayoutMode { MINIMAL, FULL }

data class AppPrefs(
    val haptics: HapticLevel = HapticLevel.MEDIUM,
    val volumeKeys: Boolean = true,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val layout: LayoutMode = LayoutMode.MINIMAL,
    val touchpad: Boolean = false,
    val target: Target = Target.TV,
    /** Głośność i wyciszenie zawsze do Samsunga, także gdy wybrany jest dekoder. */
    val volumeToTv: Boolean = true,
)

class SettingsStore(private val context: Context) {

    private object Keys {
        val TV_IP = stringPreferencesKey("tv_ip")
        val TV_NAME = stringPreferencesKey("tv_name")
        val TV_MAC = stringPreferencesKey("tv_mac")
        val TV_TOKEN = stringPreferencesKey("tv_token")
        val TV_CERT_PIN = stringPreferencesKey("tv_cert_pin")
        val TV_BRAND = stringPreferencesKey("tv_brand")
        /** Stary przełącznik wł./wył. z wersji 0.1.0. Czytany tylko, gdy brak [HAPTIC_LEVEL]. */
        val HAPTICS_LEGACY = booleanPreferencesKey("haptics")
        val HAPTIC_LEVEL = stringPreferencesKey("haptic_level")
        val VOLUME_KEYS = booleanPreferencesKey("volume_keys")
        val THEME = stringPreferencesKey("theme")
        val LAYOUT = stringPreferencesKey("layout")
        val TOUCHPAD = booleanPreferencesKey("touchpad")
        val TARGET = stringPreferencesKey("target")
        val VOLUME_TO_TV = booleanPreferencesKey("volume_to_tv")
        val ATV_IP = stringPreferencesKey("atv_ip")
        val ATV_NAME = stringPreferencesKey("atv_name")
        val ATV_CERT_PIN = stringPreferencesKey("atv_cert_pin")
        val ATV_MAC = stringPreferencesKey("atv_mac")
        val SHORTCUTS = stringPreferencesKey("shortcuts")
    }

    val tvConfig: Flow<TvConfig?> = context.dataStore.data.map { p ->
        p[Keys.TV_IP]?.let { ip ->
            TvConfig(
                ip = ip,
                name = p[Keys.TV_NAME],
                mac = p[Keys.TV_MAC],
                token = p[Keys.TV_TOKEN],
                certPin = p[Keys.TV_CERT_PIN],
                brand = p[Keys.TV_BRAND].toEnum(TvBrand.SAMSUNG),
            )
        }
    }

    val prefs: Flow<AppPrefs> = context.dataStore.data.map { p ->
        val legacyHaptics = if (p[Keys.HAPTICS_LEGACY] == false) HapticLevel.OFF else HapticLevel.MEDIUM
        AppPrefs(
            haptics = p[Keys.HAPTIC_LEVEL].toEnum(legacyHaptics),
            volumeKeys = p[Keys.VOLUME_KEYS] ?: true,
            theme = p[Keys.THEME].toEnum(ThemeMode.SYSTEM),
            layout = p[Keys.LAYOUT].toEnum(LayoutMode.MINIMAL),
            touchpad = p[Keys.TOUCHPAD] ?: false,
            target = p[Keys.TARGET].toEnum(Target.TV),
            volumeToTv = p[Keys.VOLUME_TO_TV] ?: true,
        )
    }

    val atvConfig: Flow<AtvConfig?> = context.dataStore.data.map { p ->
        p[Keys.ATV_IP]?.let { ip ->
            AtvConfig(ip = ip, name = p[Keys.ATV_NAME], certPin = p[Keys.ATV_CERT_PIN], mac = p[Keys.ATV_MAC])
        }
    }

    suspend fun saveAtv(ip: String, name: String?) {
        context.dataStore.edit { p ->
            // Inny adres IP może oznaczać inny dekoder, więc stary MAC nie pasuje.
            if (p[Keys.ATV_IP] != ip) p.remove(Keys.ATV_MAC)
            p[Keys.ATV_IP] = ip
            p.setOrRemove(Keys.ATV_NAME, name)
            // Nowe parowanie: certyfikat dekodera zapiszemy przy pierwszym połączeniu.
            p.remove(Keys.ATV_CERT_PIN)
        }
    }

    suspend fun saveAtvPin(pin: String) {
        context.dataStore.edit { it[Keys.ATV_CERT_PIN] = pin }
    }

    suspend fun saveAtvMac(mac: String?) {
        context.dataStore.edit { it.setOrRemove(Keys.ATV_MAC, mac) }
    }

    suspend fun forgetAtv() {
        context.dataStore.edit { p ->
            listOf(Keys.ATV_IP, Keys.ATV_NAME, Keys.ATV_CERT_PIN, Keys.ATV_MAC).forEach { p.remove(it) }
        }
    }

    /** Skróty aplikacji. Brak zapisu = lista domyślna. */
    val shortcuts: Flow<List<AppShortcut>> = context.dataStore.data.map { p ->
        p[Keys.SHORTCUTS]?.let(::decodeShortcuts) ?: AppShortcut.DEFAULTS
    }

    suspend fun saveShortcuts(list: List<AppShortcut>?) {
        context.dataStore.edit { p ->
            if (list == null) {
                p.remove(Keys.SHORTCUTS)
            } else {
                p[Keys.SHORTCUTS] = encodeShortcuts(list)
            }
        }
    }

    private fun encodeShortcuts(list: List<AppShortcut>): String =
        JSONArray(list.map { JSONObject().put("name", it.name).put("app", it.app) }).toString()

    private fun decodeShortcuts(json: String): List<AppShortcut>? = runCatching {
        val array = JSONArray(json)
        (0 until array.length()).map { i ->
            val o = array.getJSONObject(i)
            AppShortcut(o.getString("name"), o.getString("app"))
        }
    }.getOrNull()

    suspend fun setTarget(target: Target) {
        context.dataStore.edit { it[Keys.TARGET] = target.name }
    }

    suspend fun setVolumeToTv(enabled: Boolean) {
        context.dataStore.edit { it[Keys.VOLUME_TO_TV] = enabled }
    }

    /** Zapisuje TV. Zmiana IP kasuje parowanie, bo to może być inne urządzenie. */
    suspend fun saveTv(ip: String, name: String?, mac: String?, brand: TvBrand) {
        context.dataStore.edit { p ->
            if (p[Keys.TV_IP] != ip || p[Keys.TV_BRAND].toEnum(TvBrand.SAMSUNG) != brand) {
                p.remove(Keys.TV_TOKEN)
                p.remove(Keys.TV_CERT_PIN)
                p.remove(Keys.TV_MAC)
            }
            p[Keys.TV_IP] = ip
            p[Keys.TV_BRAND] = brand.name
            p.setOrRemove(Keys.TV_NAME, name)
            if (mac != null) p[Keys.TV_MAC] = mac
        }
    }

    suspend fun saveMac(mac: String?) {
        context.dataStore.edit { it.setOrRemove(Keys.TV_MAC, mac) }
    }

    /** Zapisuje tylko pola różne od null. */
    suspend fun saveTvUpdate(token: String?, certPin: String?, mac: String?) {
        context.dataStore.edit { p ->
            if (token != null) p[Keys.TV_TOKEN] = token
            if (certPin != null) p[Keys.TV_CERT_PIN] = certPin
            if (mac != null) p[Keys.TV_MAC] = mac
        }
    }

    suspend fun clearPairing() {
        context.dataStore.edit { p ->
            p.remove(Keys.TV_TOKEN)
            p.remove(Keys.TV_CERT_PIN)
        }
    }

    suspend fun forgetTv() {
        context.dataStore.edit { p ->
            listOf(Keys.TV_IP, Keys.TV_NAME, Keys.TV_MAC, Keys.TV_TOKEN, Keys.TV_CERT_PIN, Keys.TV_BRAND)
                .forEach { p.remove(it) }
        }
    }

    suspend fun setHaptics(level: HapticLevel) {
        context.dataStore.edit { it[Keys.HAPTIC_LEVEL] = level.name }
    }

    suspend fun setTheme(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME] = mode.name }
    }

    suspend fun setLayout(mode: LayoutMode) {
        context.dataStore.edit { it[Keys.LAYOUT] = mode.name }
    }

    suspend fun setTouchpad(enabled: Boolean) {
        context.dataStore.edit { it[Keys.TOUCHPAD] = enabled }
    }

    suspend fun setVolumeKeys(enabled: Boolean) {
        context.dataStore.edit { it[Keys.VOLUME_KEYS] = enabled }
    }

    private fun androidx.datastore.preferences.core.MutablePreferences.setOrRemove(
        key: Preferences.Key<String>,
        value: String?,
    ) {
        if (value != null) this[key] = value else remove(key)
    }
}

private inline fun <reified T : Enum<T>> String?.toEnum(default: T): T =
    this?.let { name -> enumValues<T>().firstOrNull { it.name == name } } ?: default
