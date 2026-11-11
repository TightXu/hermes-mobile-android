package com.tightxu.hermesremote

import com.tightxu.hermesremote.data.ApiException
import com.tightxu.hermesremote.data.ApiExceptions
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/** HTTP status / throwable → typed ApiException mapping. Pure functions. */
class ErrorMappingTest {

    @Test
    fun `401 maps to Unauthorized`() {
        val e = ApiExceptions.fromHttp(401, "nope")
        assertTrue(e is ApiException.Unauthorized)
    }

    @Test
    fun `429 maps to RateLimited`() {
        val e = ApiExceptions.fromHttp(429, "slow down")
        assertTrue(e is ApiException.RateLimited)
    }

    @Test
    fun `500 maps to Http with code and truncated body`() {
        val body = "x".repeat(2000)
        val e = ApiExceptions.fromHttp(500, body)
        assertTrue(e is ApiException.Http)
        assertEquals(500, (e as ApiException.Http).code)
        assertEquals(500, e.body.length) // truncated to 500 chars
        assertTrue(e.message!!.contains("500"))
    }

    @Test
    fun `404 maps to Http`() {
        val e = ApiExceptions.fromHttp(404, "not found")
        assertTrue(e is ApiException.Http)
        assertEquals(404, (e as ApiException.Http).code)
        assertEquals("not found", e.body)
    }

    @Test
    fun `io exceptions map to Network`() {
        assertTrue(ApiExceptions.fromThrowable(IOException("boom")) is ApiException.Network)
        assertTrue(ApiExceptions.fromThrowable(UnknownHostException("dns")) is ApiException.Network)
        assertTrue(ApiExceptions.fromThrowable(ConnectException()) is ApiException.Network)
        assertTrue(ApiExceptions.fromThrowable(SocketTimeoutException()) is ApiException.Network)
    }

    @Test
    fun `api exception passes through unchanged`() {
        val original = ApiException.RateLimited()
        assertTrue(ApiExceptions.fromThrowable(original) === original)
    }

    @Test
    fun `serialization failure maps to Network with parse hint`() {
        val e = ApiExceptions.fromThrowable(
            kotlinx.serialization.SerializationException("bad json")
        )
        assertTrue(e is ApiException.Network)
        assertTrue(e.message!!.contains("解析"))
    }

    @Test
    fun `messages are user presentable`() {
        assertTrue(ApiExceptions.fromHttp(401, "").message!!.contains("401"))
        assertTrue(ApiException.Network().message!!.contains("无法连接"))
        assertTrue(ApiException.InvalidServer().message!!.contains("http"))
    }
}
