package com.tightxu.hermesremote

import com.tightxu.hermesremote.data.ApiClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** PTY WebSocket URL construction: scheme, params, encoding, resume. */
class BuildPtyUrlTest {

    private val ticket = "t1+t2/t3=t4"
    private val attach = "0123456789abcdef0123456789abcdef"
    private val channel = "chat-11111111-2222-3333-4444-555555555555"

    @Test
    fun `https becomes wss`() {
        val url = ApiClient.buildPtyUrl(
            baseUrl = "https://hermes.example.com/",
            sessionId = null,
            channel = channel,
            attach = attach,
            ticket = "plain",
        )
        assertTrue(url.startsWith("wss://hermes.example.com/api/pty?"))
    }

    @Test
    fun `http becomes ws`() {
        val url = ApiClient.buildPtyUrl(
            baseUrl = "http://192.168.1.10:9119",
            sessionId = null,
            channel = channel,
            attach = attach,
            ticket = "plain",
        )
        assertTrue(url.startsWith("ws://192.168.1.10:9119/api/pty?"))
    }

    @Test
    fun `resume param present for existing session`() {
        val url = ApiClient.buildPtyUrl(
            baseUrl = "https://hermes.example.com",
            sessionId = "20261108_abcdef",
            channel = channel,
            attach = attach,
            ticket = "plain",
        )
        assertTrue(url.contains("resume=20261108_abcdef"))
    }

    @Test
    fun `resume param absent for new session`() {
        val url = ApiClient.buildPtyUrl(
            baseUrl = "https://hermes.example.com",
            sessionId = null,
            channel = channel,
            attach = attach,
            ticket = "plain",
        )
        assertFalse(url.contains("resume="))
    }

    @Test
    fun `query values are url-encoded`() {
        val url = ApiClient.buildPtyUrl(
            baseUrl = "https://hermes.example.com",
            sessionId = "id with space+plus",
            channel = channel,
            attach = attach,
            ticket = ticket,
        )
        // '+' must not survive as a literal (it would decode to a space).
        assertFalse(url.contains("t1+t2"))
        assertTrue(url.contains("ticket=t1%2Bt2%2Ft3%3Dt4"))
        assertTrue(url.contains("resume=id%20with%20space%2Bplus"))
        assertTrue(url.contains("attach=$attach"))
        // Hyphens are unreserved in query encoding and pass through.
        assertTrue(url.contains("channel=$channel"))
    }

    @Test
    fun `full url shape`() {
        val url = ApiClient.buildPtyUrl(
            baseUrl = "https://gateway.example.com",
            sessionId = "s1",
            channel = "chat-fresh-x",
            attach = attach,
            ticket = "tk",
        )
        assertEquals(
            "wss://gateway.example.com/api/pty?channel=chat-fresh-x" +
                "&attach=0123456789abcdef0123456789abcdef&ticket=tk&resume=s1",
            url,
        )
    }

    @Test
    fun `fresh channel prefix versus resume channel prefix`() {
        // Mirrors the dashboard convention: chat-fresh-<uuid> for a brand-new
        // chat, chat-<uuid> when resuming.
        val fresh = "chat-fresh-" + java.util.UUID.randomUUID()
        val resume = "chat-" + java.util.UUID.randomUUID()
        assertTrue(fresh.startsWith("chat-fresh-"))
        assertFalse(resume.startsWith("chat-fresh-"))
    }
}
