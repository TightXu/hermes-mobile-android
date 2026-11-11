package com.tightxu.hermesremote.data

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import okhttp3.Cookie
import okhttp3.CookieJar
import okhttp3.HttpUrl
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * OkHttp CookieJar backed by an in-memory snapshot plus an
 * EncryptedSharedPreferences copy, so the hermes_session_* cookies survive
 * process death without being readable in a plain XML file. If the encrypted
 * store cannot be opened (KeyStore corrupted, vendor quirks) it degrades to
 * memory-only rather than crashing login.
 */
class PersistentCookieStore(context: Context) : CookieJar {

    private val json = Json { ignoreUnknownKeys = true }

    @Serializable
    private data class StoredCookie(
        val name: String,
        val value: String,
        val domain: String,
        val path: String,
        val expiresAt: Long,
        val secure: Boolean,
        val hostOnly: Boolean,
    )

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

    private val lock = ReentrantLock()
    private var cookies: MutableList<StoredCookie> = mutableListOf()

    init {
        prefs?.getString(KEY_COOKIES, null)?.let { encoded ->
            runCatching { cookies = json.decodeFromString<List<StoredCookie>>(encoded).toMutableList() }
        }
    }

    /** True when a session cookie from a previous login is present. */
    fun hasSessionCookies(): Boolean = lock.withLock { cookies.isNotEmpty() }

    fun clear() {
        lock.withLock { cookies.clear() }
        persist()
    }

    override fun saveFromResponse(url: HttpUrl, cookies: List<Cookie>) {
        if (cookies.isEmpty()) return
        lock.withLock {
            for (cookie in cookies) {
                // Replace same name+domain+path, keep newest.
                this.cookies.removeAll {
                    it.name == cookie.name && it.domain == cookie.domain && it.path == cookie.path
                }
                this.cookies.add(
                    StoredCookie(
                        name = cookie.name,
                        value = cookie.value,
                        domain = cookie.domain,
                        path = cookie.path,
                        expiresAt = cookie.expiresAt,
                        secure = cookie.secure,
                        hostOnly = cookie.hostOnly,
                    )
                )
            }
        }
        persist()
    }

    override fun loadForRequest(url: HttpUrl): List<Cookie> {
        val now = System.currentTimeMillis()
        return lock.withLock {
            val fresh = cookies.filter { it.expiresAt > now || it.expiresAt <= 0L }
            fresh.filter { matches(it, url) }.mapNotNull { stored ->
                runCatching {
                    val builder = Cookie.Builder()
                        .name(stored.name)
                        .value(stored.value)
                        .path(stored.path)
                        .expiresAt(stored.expiresAt)
                    if (stored.hostOnly) builder.hostOnlyDomain(stored.domain)
                    else builder.domain(stored.domain)
                    if (stored.secure) builder.secure()
                    builder.build()
                }.getOrNull()
            }
        }
    }

    private fun matches(cookie: StoredCookie, url: HttpUrl): Boolean {
        val host = url.host
        val domainOk =
            if (cookie.hostOnly) host == cookie.domain
            else host == cookie.domain || host.endsWith(".${cookie.domain}")
        return domainOk && url.encodedPath.startsWith(cookie.path)
    }

    private fun persist() {
        val snapshot = lock.withLock { cookies.toList() }
        prefs?.let {
            runCatching { it.edit().putString(KEY_COOKIES, json.encodeToString(snapshot)).apply() }
        }
    }

    private companion object {
        const val PREFS_NAME = "hermes_remote_cookies"
        const val KEY_COOKIES = "cookies"
    }
}
