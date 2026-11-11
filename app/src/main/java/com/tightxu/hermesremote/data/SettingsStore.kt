package com.tightxu.hermesremote.data

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.settingsDataStore by preferencesDataStore(name = "hermes_settings")

data class AppSettings(
    val server: String = ApiClient.DEFAULT_SERVER,
    val username: String = "",
    val remember: Boolean = true,
    val savePassword: Boolean = true,
    val theme: String = "yellow",
)

/**
 * DataStore-backed persistence for the login form and app preferences. Only
 * server URL, username, the remember/save-password flags and the accent theme
 * live here — the password itself is only ever in [SecretStore].
 */
class SettingsStore(private val context: Context) {

    private object Keys {
        val SERVER = stringPreferencesKey("server_url")
        val USERNAME = stringPreferencesKey("username")
        val REMEMBER = booleanPreferencesKey("remember")
        val SAVE_PASSWORD = booleanPreferencesKey("save_password")
        val THEME = stringPreferencesKey("theme")
    }

    val settings: Flow<AppSettings> = context.settingsDataStore.data.map { prefs ->
        AppSettings(
            server = prefs[Keys.SERVER] ?: ApiClient.DEFAULT_SERVER,
            username = prefs[Keys.USERNAME] ?: "",
            remember = prefs[Keys.REMEMBER] ?: true,
            savePassword = prefs[Keys.SAVE_PASSWORD] ?: true,
            theme = prefs[Keys.THEME] ?: "yellow",
        )
    }

    suspend fun read(): AppSettings = settings.first()

    suspend fun save(
        server: String,
        username: String,
        remember: Boolean,
        savePassword: Boolean = true,
    ) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.SERVER] = if (remember) server else ""
            prefs[Keys.USERNAME] = if (remember) username else ""
            prefs[Keys.REMEMBER] = remember
            prefs[Keys.SAVE_PASSWORD] = savePassword
        }
    }

    suspend fun setTheme(key: String) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.THEME] = key
        }
    }

    suspend fun setSavePassword(value: Boolean) {
        context.settingsDataStore.edit { prefs ->
            prefs[Keys.SAVE_PASSWORD] = value
        }
    }
}
