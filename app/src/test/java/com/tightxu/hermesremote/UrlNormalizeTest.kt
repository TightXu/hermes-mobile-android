package com.tightxu.hermesremote

import com.tightxu.hermesremote.data.ApiClient
import com.tightxu.hermesremote.data.ApiException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

/** URL normalization: user-typed server origins → canonical http(s) base. */
class UrlNormalizeTest {

    @Test
    fun `https without trailing slash is unchanged`() {
        assertEquals(
            "https://hermes.example.com",
            ApiClient.normalizeBaseUrl("https://hermes.example.com"),
        )
    }

    @Test
    fun `trailing slash is stripped`() {
        assertEquals(
            "https://hermes.example.com",
            ApiClient.normalizeBaseUrl("https://hermes.example.com/"),
        )
    }

    @Test
    fun `multiple trailing slashes are stripped`() {
        assertEquals(
            "http://192.168.1.10:9119",
            ApiClient.normalizeBaseUrl("http://192.168.1.10:9119///"),
        )
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals(
            "https://hermes.example.com",
            ApiClient.normalizeBaseUrl("  https://hermes.example.com/ "),
        )
    }

    @Test
    fun `uppercase scheme is accepted and lowercased`() {
        assertEquals(
            "https://hermes.example.com",
            ApiClient.normalizeBaseUrl("HTTPS://hermes.example.com"),
        )
    }

    @Test
    fun `http lan address is preserved`() {
        assertEquals(
            "http://192.168.1.10:9119",
            ApiClient.normalizeBaseUrl("http://192.168.1.10:9119/"),
        )
    }

    @Test
    fun `missing scheme is rejected`() {
        assertThrows(ApiException.InvalidServer::class.java) {
            ApiClient.normalizeBaseUrl("hermes.example.com")
        }
    }

    @Test
    fun `empty string is rejected`() {
        assertThrows(ApiException.InvalidServer::class.java) {
            ApiClient.normalizeBaseUrl("")
        }
    }

    @Test
    fun `blank string is rejected`() {
        assertThrows(ApiException.InvalidServer::class.java) {
            ApiClient.normalizeBaseUrl("   ")
        }
    }

    @Test
    fun `scheme-only is rejected`() {
        assertThrows(ApiException.InvalidServer::class.java) {
            ApiClient.normalizeBaseUrl("https://")
        }
    }

    @Test
    fun `non-http scheme is rejected`() {
        assertThrows(ApiException.InvalidServer::class.java) {
            ApiClient.normalizeBaseUrl("ftp://hermes.example.com")
        }
        assertThrows(ApiException.InvalidServer::class.java) {
            ApiClient.normalizeBaseUrl("ws://hermes.example.com")
        }
    }

    @Test
    fun `internal space in host is rejected`() {
        assertThrows(ApiException.InvalidServer::class.java) {
            ApiClient.normalizeBaseUrl("https://hermes example.com")
        }
    }
}
