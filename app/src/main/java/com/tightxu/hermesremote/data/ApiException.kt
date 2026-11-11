package com.tightxu.hermesremote.data

/**
 * Unified error taxonomy for gateway calls. Mapping from HTTP/IO failures is
 * a pure function ([ApiExceptions.fromHttp] / [ApiExceptions.fromThrowable]) so
 * it is unit-testable without a network stack.
 */
sealed class ApiException(message: String) : Exception(message) {
    /** Server URL missing or not http(s). */
    class InvalidServer(message: String = "服务器地址无效，需要 http:// 或 https:// 开头") :
        ApiException(message)

    /** 401 — cookie expired or credentials wrong. */
    class Unauthorized(message: String = "未授权（401），请重新登录") : ApiException(message)

    /** 429 — too many attempts. */
    class RateLimited(message: String = "尝试过多（429），请稍后再试") : ApiException(message)

    /** DNS / connect / timeout / IO. */
    class Network(message: String = "无法连接服务器") : ApiException(message)

    /** Any other non-2xx response. */
    class Http(val code: Int, val body: String) :
        ApiException("服务器错误（HTTP $code）")

    /** 404 from an OPTIONAL plugin route (e.g. session-lock) — the feature is
     *  simply not installed/enabled on this gateway; callers degrade quietly. */
    class PluginUnavailable(val code: Int = 404) :
        ApiException("插件不可用（HTTP $code）")
}

object ApiExceptions {
    /** Map an HTTP status + (truncated) body to the typed error. Pure. */
    fun fromHttp(code: Int, body: String): ApiException = when (code) {
        401, 403 -> ApiException.Unauthorized()
        429 -> ApiException.RateLimited()
        else -> ApiException.Http(code, body.take(500))
    }

    /** Wrap an arbitrary throwable; IOException becomes Network. Pure. */
    fun fromThrowable(t: Throwable): ApiException = when (t) {
        is ApiException -> t
        is java.io.IOException -> ApiException.Network()
        is kotlinx.serialization.SerializationException -> ApiException.Network("响应解析失败")
        else -> ApiException.Network(t.message ?: "未知错误")
    }
}
