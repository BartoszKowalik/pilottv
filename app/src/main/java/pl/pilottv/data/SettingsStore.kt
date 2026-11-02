package pl.pilottv.data

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "settings")

/** Zapisany telewizor. [token] i [certPin] pojawiają się po sparowaniu. */
data class TvConfig(
    val ip: String,
    val name: String?,
    val mac: String?,
    val token: String?,
    val certPin: String?,
)

enum class HapticLevel { OFF, LIGHT, MEDIUM, STRONG }

enum class ThemeMode { SYSTEM, LIGHT, DARK }

data class AppPrefs(
    val haptics: HapticLevel = HapticLevel.MEDIUM,
    val volumeKeys: Boolean = true,
    val theme: ThemeMode = ThemeMode.SYSTEM,
)

class SettingsStore(private val context: Context) {

    private object Keys {
        val TV_IP = stringPreferencesKey("tv_ip")
        val TV_NAME = stringPreferencesKey("tv_name")
        val TV_MAC = stringPreferencesKey("tv_mac")
        val TV_TOKEN = stringPreferencesKey("tv_token")
        val TV_CERT_PIN = stringPreferencesKey("tv_cert_pin")
        /** Stary przełącznik wł./wył. z wersji 0.1.0. Czytany tylko, gdy brak [HAPTIC_LEVEL]. */
        val HAPTICS_LEGACY = booleanPreferencesKey("haptics")
        val HAPTIC_LEVEL = stringPreferencesKey("haptic_level")
        val VOLUME_KEYS = booleanPreferencesKey("volume_keys")
        val THEME = stringPreferencesKey("theme")
    }

    val tvConfig: Flow<TvConfig?> = context.dataStore.data.map { p ->
        p[Keys.TV_IP]?.let { ip ->
            TvConfig(
                ip = ip,
                name = p[Keys.TV_NAME],
                mac = p[Keys.TV_MAC],
                token = p[Keys.TV_TOKEN],
                certPin = p[Keys.TV_CERT_PIN],
            )
        }
    }

    val prefs: Flow<AppPrefs> = context.dataStore.data.map { p ->
        val legacyHaptics = if (p[Keys.HAPTICS_LEGACY] == false) HapticLevel.OFF else HapticLevel.MEDIUM
        AppPrefs(
            haptics = p[Keys.HAPTIC_LEVEL].toEnum(legacyHaptics),
            volumeKeys = p[Keys.VOLUME_KEYS] ?: true,
            theme = p[Keys.THEME].toEnum(ThemeMode.SYSTEM),
        )
    }

    /** Zapisuje TV. Zmiana IP kasuje parowanie, bo to może być inne urządzenie. */
    suspend fun saveTv(ip: String, name: String?, mac: String?) {
        context.dataStore.edit { p ->
            if (p[Keys.TV_IP] != ip) {
                p.remove(Keys.TV_TOKEN)
                p.remove(Keys.TV_CERT_PIN)
                p.remove(Keys.TV_MAC)
            }
            p[Keys.TV_IP] = ip
            p.setOrRemove(Keys.TV_NAME, name)
            if (mac != null) p[Keys.TV_MAC] = mac
        }
    }

    suspend fun saveMac(mac: String?) {
        context.dataStore.edit { it.setOrRemove(Keys.TV_MAC, mac) }
    }

    suspend fun savePairing(token: String?, certPin: String) {
        context.dataStore.edit { p ->
            p.setOrRemove(Keys.TV_TOKEN, token)
            p[Keys.TV_CERT_PIN] = certPin
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
            listOf(Keys.TV_IP, Keys.TV_NAME, Keys.TV_MAC, Keys.TV_TOKEN, Keys.TV_CERT_PIN).forEach { p.remove(it) }
        }
    }

    suspend fun setHaptics(level: HapticLevel) {
        context.dataStore.edit { it[Keys.HAPTIC_LEVEL] = level.name }
    }

    suspend fun setTheme(mode: ThemeMode) {
        context.dataStore.edit { it[Keys.THEME] = mode.name }
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
