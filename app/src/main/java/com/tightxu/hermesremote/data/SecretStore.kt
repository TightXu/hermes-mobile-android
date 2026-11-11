package com.tightxu.hermesremote.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * Optional password memory, backed by EncryptedSharedPreferences with the
 * same MasterKey scheme (AES256_GCM, default alias) as [PersistentCookieStore]
 * so both live under one keystore key. If the encrypted store cannot be
 * opened (KeyStore corrupted, vendor quirks) it degrades to "don't save" —
 * reads return null and writes are dropped, never a crash. The password
 * never touches DataStore, logs, or plain XML.
 */
class SecretStore(context: Context) {

    private val prefs: SharedPreferences? = runCatching {
        val masterKey = MasterKey.Builder(context)
            .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
            .build()
        EncryptedSharedPreferences.create(
            context,
            PREFS_NAME,
            masterKey,
            EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
            EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
        )
    }.getOrNull()

    fun savePassword(password: String) {
        prefs?.let { runCatching { it.edit().putString(KEY_PASSWORD, password).apply() } }
    }

    fun readPassword(): String? =
        prefs?.let { runCatching { it.getString(KEY_PASSWORD, null) }.getOrNull() }

    fun clearPassword() {
        prefs?.let { runCatching { it.edit().remove(KEY_PASSWORD).apply() } }
    }

    private companion object {
        const val PREFS_NAME = "hermes_remote_secrets"
        const val KEY_PASSWORD = "password"
    }
}
