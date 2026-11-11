package com.tightxu.hermesremote.data

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.encodeToString
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

/**
 * HTTP client for the Hermes gateway. All request bodies travel as JSON via
 * kotlinx-serialization; cookies (the auth credential) are handled entirely by
 * the injected [PersistentCookieStore].
 *
 * Pure helpers ([normalizeBaseUrl], [buildPtyUrl], [ApiExceptions]) live on the
 * companion so unit tests can exercise them without Android or network.
 */
class ApiClient(private val cookieStore: PersistentCookieStore) {

    /** Normalized server origin, e.g. "https://gateway.example.com". */
    var baseUrl: String = DEFAULT_SERVER
        private set

    private val client: OkHttpClient = OkHttpClient.Builder()
        .cookieJar(cookieStore)
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .callTimeout(60, TimeUnit.SECONDS)
        .followRedirects(true)
        .build()

    fun hasSessionCookies(): Boolean = cookieStore.hasSessionCookies()

    /** Point the client at a server without logging in (startup cookie probe). */
    fun setBaseUrl(server: String) {
        baseUrl = normalizeBaseUrl(server)
    }

    /** POST /auth/password-login — on 200 the server Set-Cookies the session. */
    suspend fun login(server: String, username: String, password: String): Result<Unit> =
        withContext(Dispatchers.IO) {
            runCatching {
                baseUrl = normalizeBaseUrl(server)
                val body = hermesJson.encodeToString(
                    LoginRequest(username = username, password = password)
                ).toRequestBody("application/json; charset=utf-8".toMediaType())
                val request = Request.Builder()
                    .url(baseUrl + "/auth/password-login")
                    .post(body)
                    .build()
                client.newCall(request).execute().use { response ->
                    if (!response.isSuccessful) throw ApiExceptions.fromHttp(response.code, response.body?.string().orEmpty())
                }
            }
        }

    /** POST /auth/logout (best effort — 302 → /login counts as success), then
     *  always drop the local session cookies. */
    suspend fun logout() {
        withContext(Dispatchers.IO) {
            runCatching {
                val request = Request.Builder()
                    .url(baseUrl + "/auth/logout")
                    .post(EMPTY_JSON_BODY)
                    .build()
                client.newCall(request).execute().use { /* any outcome: cookies die below */ }
            }
        }
        cookieStore.clear()
    }

    suspend fun getSessions(limit: Int = 50, order: String = "recent"): List<SessionInfo> = withContext(Dispatchers.IO) {
        val safeOrder = if (order == "created" || order == "recent") order else "recent"
        val request = Request.Builder()
            .url(baseUrl + "/api/sessions?limit=$limit&offset=0&order=$safeOrder")
            .get()
            .build()
        executeFor(request) { body -> hermesJson.decodeFromString<SessionsResponse>(body).sessions }
    }

    /**
     * Paged session messages. `order=latest` pages walk backwards from the
     * newest message; within a page the server returns oldest → newest.
     * Pagination.returned counts RAW messages (tool messages included), so
     * the next page's offset must accumulate by returned, not by the number
     * of visible chat bubbles.
     */
    suspend fun getMessagesPage(
        sessionId: String,
        limit: Int = 30,
        offset: Int = 0,
        order: String = "latest",
    ): SessionMessagesResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(baseUrl + "/api/sessions/${urlEncode(sessionId)}/messages?limit=$limit&offset=$offset&order=$order")
            .get()
            .build()
        executeFor(request) { body -> hermesJson.decodeFromString<SessionMessagesResponse>(body) }
    }

    suspend fun getStatus(): StatusResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(baseUrl + "/api/status").get().build()
        executeFor(request) { body -> hermesJson.decodeFromString<StatusResponse>(body) }
    }

    /**
     * Mint a single-use WebSocket ticket (TTL 30s). Every connect/reconnect of
     * the PTY socket must call this again — the ticket dies with the first use.
     */
    suspend fun getWsTicket(): String = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(baseUrl + "/api/auth/ws-ticket").post(EMPTY_JSON_BODY).build()
        executeFor(request) { body -> hermesJson.decodeFromString<WsTicketResponse>(body).ticket }
    }

    // ── session-lock plugin routes ────────────────────────────────────────
    // The plugin is OPTIONAL: 404 means "not installed/enabled", surfaced as
    // [ApiException.PluginUnavailable] so the UI can hide the entry instead of
    // interrupting the session list. 401 still propagates as Unauthorized.

    /** GET /api/plugins/session-lock/leases */
    suspend fun getLeases(): LeaseResponse = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url(baseUrl + SESSION_LOCK_BASE + "leases")
            .get()
            .build()
        executeForPlugin(request) { body -> hermesJson.decodeFromString<LeaseResponse>(body) }
    }

    /**
     * POST /api/plugins/session-lock/release.
     * @param scope "session" (needs [sessionId]), "dead" (only provably-gone
     *   owners) or "all". scope=session/all require [confirm] == true.
     */
    suspend fun releaseLease(
        scope: String,
        sessionId: String? = null,
        confirm: Boolean = false,
    ): ReleaseResponse = withContext(Dispatchers.IO) {
        val body = hermesJson.encodeToString(
            LeaseReleaseRequest(scope = scope, sessionId = sessionId, confirm = confirm)
        ).toRequestBody("application/json; charset=utf-8".toMediaType())
        val request = Request.Builder()
            .url(baseUrl + SESSION_LOCK_BASE + "release")
            .post(body)
            .build()
        executeForPlugin(request) { b -> hermesJson.decodeFromString<ReleaseResponse>(b) }
    }

    private inline fun <T> executeFor(request: Request, parse: (String) -> T): T {
        try {
            client.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (!response.isSuccessful) throw ApiExceptions.fromHttp(response.code, body)
                return parse(body)
            }
        } catch (e: ApiException) {
            throw e
        } catch (e: IOException) {
            throw ApiException.Network()
        } catch (e: kotlinx.serialization.SerializationException) {
            throw ApiException.Network("响应解析失败")
        }
    }

    /** Like [executeFor], but 404 becomes [ApiException.PluginUnavailable]. */
    private inline fun <T> executeForPlugin(request: Request, parse: (String) -> T): T =
        try {
            executeFor(request, parse)
        } catch (e: ApiException.Http) {
            if (e.code == 404) throw ApiException.PluginUnavailable(e.code) else throw e
        }

    companion object {
        /** No built-in gateway: the user types their own on first launch. */
        const val DEFAULT_SERVER = ""

        /** Input-field hint only; never used as a request target. */
        const val SERVER_PLACEHOLDER = "https://your-hermes.example.com"

        /** URL prefix of the (optional) session-lock plugin routes. */
        private const val SESSION_LOCK_BASE = "/api/plugins/session-lock/"

        private val EMPTY_JSON_BODY = "{}".toRequestBody("application/json".toMediaType())

        /**
         * Canonicalize a user-entered server origin: trims whitespace, drops
         * trailing slashes, requires an http/https scheme (case-insensitive).
         * @throws ApiException.InvalidServer on anything else.
         */
        fun normalizeBaseUrl(server: String): String {
            val trimmed = server.trim().trimEnd('/')
            val lower = trimmed.lowercase()
            val schemeStripped = when {
                lower.startsWith("https://") -> "https://" to trimmed.substring(8)
                lower.startsWith("http://") -> "http://" to trimmed.substring(7)
                else -> throw ApiException.InvalidServer()
            }
            val (scheme, host) = schemeStripped
            if (host.isBlank() || host.contains(' ')) throw ApiException.InvalidServer()
            return scheme + host
        }

        /**
         * Build the PTY WebSocket URL:
         *   wss://<host>/api/pty?channel=..&attach=..&ticket=..[&resume=..]
         * https→wss, http→ws; every value is URL-encoded. Pure & unit-tested.
         */
        fun buildPtyUrl(
            baseUrl: String,
            sessionId: String?,
            channel: String,
            attach: String,
            ticket: String,
        ): String {
            val normalized = normalizeBaseUrl(baseUrl)
            val wsBase = when {
                normalized.startsWith("https://") -> "wss://" + normalized.removePrefix("https://")
                else -> "ws://" + normalized.removePrefix("http://")
            }
            val params = StringBuilder()
                .append("channel=").append(urlEncode(channel))
                .append("&attach=").append(urlEncode(attach))
                .append("&ticket=").append(urlEncode(ticket))
            if (!sessionId.isNullOrBlank()) {
                params.append("&resume=").append(urlEncode(sessionId))
            }
            return "$wsBase/api/pty?$params"
        }

        fun urlEncode(value: String): String =
            URLEncoder.encode(value, "UTF-8").replace("+", "%20")
    }
}
