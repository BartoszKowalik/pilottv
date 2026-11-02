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

data class AppPrefs(
    val haptics: Boolean = true,
    val volumeKeys: Boolean = true,
)

class SettingsStore(private val context: Context) {

    private object Keys {
        val TV_IP = stringPreferencesKey("tv_ip")
        val TV_NAME = stringPreferencesKey("tv_name")
        val TV_MAC = stringPreferencesKey("tv_mac")
        val TV_TOKEN = stringPreferencesKey("tv_token")
        val TV_CERT_PIN = stringPreferencesKey("tv_cert_pin")
        val HAPTICS = booleanPreferencesKey("haptics")
        val VOLUME_KEYS = booleanPreferencesKey("volume_keys")
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
        AppPrefs(
            haptics = p[Keys.HAPTICS] ?: true,
            volumeKeys = p[Keys.VOLUME_KEYS] ?: true,
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

    suspend fun setHaptics(enabled: Boolean) {
        context.dataStore.edit { it[Keys.HAPTICS] = enabled }
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
