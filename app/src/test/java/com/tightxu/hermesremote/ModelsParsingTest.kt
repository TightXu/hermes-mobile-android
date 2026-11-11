package com.tightxu.hermesremote

import com.tightxu.hermesremote.data.StatusResponse
import com.tightxu.hermesremote.data.SessionsResponse
import com.tightxu.hermesremote.data.WsTicketResponse
import com.tightxu.hermesremote.data.hermesJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** JSON parsing: unknown fields dropped, optional fields null-safe. */
class ModelsParsingTest {

    @Test
    fun `sessions response with unknown fields parses`() {
        // Mirrors the live shape plus a few future/unknown keys.
        val json = """
        {
          "sessions": [{
            "id": "20261108_demo",
            "source": "qqbot",
            "model": "deepseek-flash",
            "title": "标题",
            "started_at": 1790000000.0,
            "ended_at": null,
            "last_active": 1790000000.0,
            "is_active": true,
            "message_count": 131,
            "tool_call_count": 20,
            "input_tokens": 0,
            "output_tokens": 0,
            "preview": "最后一条预览",
            "parent_session_id": null,
            "profile": "default",
            "future_field": {"nested": [1, 2, 3]}
          }],
          "total": 1,
          "limit": 50,
          "offset": 0,
          "new_top_level": true
        }
        """.trimIndent()

        val parsed = hermesJson.decodeFromString<SessionsResponse>(json)
        assertEquals(1, parsed.sessions.size)
        val session = parsed.sessions[0]
        assertEquals("20261108_demo", session.id)
        assertEquals("标题", session.title)
        assertEquals("qqbot", session.source)
        assertEquals(131, session.messageCount)
        assertEquals(true, session.isActive)
        assertEquals(1790000000.0, session.lastActive!!, 0.001)
        assertEquals("最后一条预览", session.preview)
    }

    @Test
    fun `null title and empty preview parse to null and empty`() {
        val json = """
        {
          "sessions": [{
            "id": "s2",
            "title": null,
            "preview": "",
            "last_active": 1790000000.0,
            "message_count": 0
          }],
          "total": 1
        }
        """.trimIndent()

        val parsed = hermesJson.decodeFromString<SessionsResponse>(json)
        assertNull(parsed.sessions[0].title)
        assertEquals("", parsed.sessions[0].preview)
    }

    @Test
    fun `missing optional fields default to null`() {
        val parsed = hermesJson.decodeFromString<SessionsResponse>(
            """{"sessions": [{"id": "only-id"}], "total": 5}""",
        )
        assertEquals("only-id", parsed.sessions[0].id)
        assertNull(parsed.sessions[0].title)
        assertNull(parsed.sessions[0].lastActive)
        assertNull(parsed.sessions[0].messageCount)
        assertNull(parsed.sessions[0].source)
        assertEquals(5, parsed.total)
    }

    @Test
    fun `empty session list parses`() {
        val parsed = hermesJson.decodeFromString<SessionsResponse>(
            """{"sessions": [], "total": 0}""",
        )
        assertTrue(parsed.sessions.isEmpty())
    }

    @Test
    fun `status response with platforms map parses`() {
        val json = """
        {
          "version": "1.2.3",
          "gateway_running": true,
          "gateway_state": "running",
          "gateway_platforms": {"qqbot": {"state": "connected"}},
          "something_new": 42
        }
        """.trimIndent()

        val parsed = hermesJson.decodeFromString<StatusResponse>(json)
        assertEquals("1.2.3", parsed.version)
        assertEquals(true, parsed.gatewayRunning)
        assertEquals("running", parsed.gatewayState)
        assertEquals(1, parsed.gatewayPlatforms?.size)
        val qqbot = parsed.gatewayPlatforms?.get("qqbot")
        assertEquals(
            "connected",
            (qqbot as kotlinx.serialization.json.JsonObject)["state"]
                ?.let { it as kotlinx.serialization.json.JsonPrimitive }?.content,
        )
    }

    @Test
    fun `ws ticket response parses`() {
        val parsed = hermesJson.decodeFromString<WsTicketResponse>(
            """{"ticket": "abc123", "ttl_seconds": 30}""",
        )
        assertEquals("abc123", parsed.ticket)
        assertEquals(30, parsed.ttlSeconds)
    }

    @Test
    fun `ws ticket with extra fields parses`() {
        val parsed = hermesJson.decodeFromString<WsTicketResponse>(
            """{"ticket": "t", "ttl_seconds": 30, "issued_at": 1790000000}""",
        )
        assertEquals("t", parsed.ticket)
    }
}
