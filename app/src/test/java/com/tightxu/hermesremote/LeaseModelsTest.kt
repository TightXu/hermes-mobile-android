package com.tightxu.hermesremote

import com.tightxu.hermesremote.data.LeaseInfo
import com.tightxu.hermesremote.data.LeaseReleaseRequest
import com.tightxu.hermesremote.data.LeaseResponse
import com.tightxu.hermesremote.data.ReleaseResponse
import com.tightxu.hermesremote.data.hermesJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Session-lock plugin contract: GET /leases and POST /release shapes as the
 * plugin (hermes-session-lock plugin_api.py) really emits them.
 */
class LeaseModelsTest {

    @Test
    fun `leases response parses the live payload`() {
        // Mirrors the 200 body measured against the running gateway
        // (path/count/entries with age_seconds + live_session_id).
        val json = """
        {
          "path": "C:\\Users\\Tight\\AppData\\Local\\hermes\\runtime\\active_sessions.json",
          "count": 2,
          "entries": [
            {
              "lease_id": "0780051af71446968a751968c6901bb4",
              "session_id": "20261006_223450_ff4bdb",
              "title": "修复 hermes desktop 一键更新失败",
              "surface": "desktop",
              "pid": 28912,
              "alive": true,
              "age_seconds": 4350.8,
              "updated_at": 1794190199.27,
              "live_session_id": "416eb37a"
            },
            {
              "lease_id": "deadbeef",
              "session_id": "20261101_093001_aabbcc",
              "surface": "remote",
              "pid": 1234,
              "alive": false,
              "age_seconds": null,
              "updated_at": null,
              "live_session_id": null,
              "future_field": {"nested": [1, 2]}
            }
          ]
        }
        """.trimIndent()

        val resp = hermesJson.decodeFromString<LeaseResponse>(json)

        assertEquals(2, resp.count)
        assertEquals(2, resp.entries.size)
        val first = resp.entries[0]
        assertEquals("0780051af71446968a751968c6901bb4", first.leaseId)
        assertEquals("20261006_223450_ff4bdb", first.sessionId)
        assertEquals("修复 hermes desktop 一键更新失败", first.title)
        assertEquals("desktop", first.surface)
        assertEquals(28912, first.pid)
        assertTrue(first.alive)
        assertEquals(4350.8, first.ageSeconds!!, 1e-9)
        assertEquals(1794190199.27, first.updatedAt!!, 1e-9)
        assertEquals("416eb37a", first.liveSessionId)
        // Second entry: dead owner, null ages, unknown key ignored.
        val second = resp.entries[1]
        assertFalse(second.alive)
        assertNull(second.ageSeconds)
        assertNull(second.updatedAt)
        assertNull(second.title)
    }

    @Test
    fun `alive null coerces to false and missing session_id defaults empty`() {
        // The plugin's liveness probe is best-effort and can answer null;
        // coerceInputValues must fold that into the non-null default.
        val json = """
        {"count": 1, "entries": [{"lease_id": "x", "alive": null}]}
        """.trimIndent()

        val resp = hermesJson.decodeFromString<LeaseResponse>(json)

        assertEquals("", resp.entries[0].sessionId)
        assertFalse(resp.entries[0].alive)
    }

    @Test
    fun `release response parses removed and remaining lists`() {
        val json = """
        {
          "ok": true,
          "removed": [
            {"session_id": "20261006_223450_ff4bdb", "surface": "desktop", "pid": 28912, "alive": false}
          ],
          "remaining": [],
          "backup": "C:\\...\\session-lock-backups\\active_sessions-20261109-101500.json",
          "path": "C:\\...\\active_sessions.json"
        }
        """.trimIndent()

        val resp = hermesJson.decodeFromString<ReleaseResponse>(json)

        assertTrue(resp.ok)
        assertEquals(1, resp.removed.size)
        assertEquals("20261006_223450_ff4bdb", resp.removed[0].sessionId)
        assertTrue(resp.remaining.isEmpty())
        assertTrue(resp.backup!!.contains("backup"))
    }

    @Test
    fun `release request encodes scope session_id and confirm`() {
        // scope=session always posts session_id + confirm=true (the plugin
        // rejects the call otherwise, even for dead owners).
        val body = hermesJson.encodeToString(
            LeaseReleaseRequest(scope = "session", sessionId = "20261006_223450_ff4bdb", confirm = true)
        )

        assertTrue(body.contains("\"scope\":\"session\""))
        assertTrue(body.contains("\"session_id\":\"20261006_223450_ff4bdb\""))
        assertTrue(body.contains("\"confirm\":true"))
    }

    @Test
    fun `release request for dead scope carries confirm false and null session_id`() {
        // scope=dead needs neither session_id nor confirm; null session_id is
        // accepted by the plugin's Optional[str] field.
        val body = hermesJson.encodeToString(
            LeaseReleaseRequest(scope = "dead", sessionId = null, confirm = false)
        )

        assertTrue(body.contains("\"scope\":\"dead\""))
        assertTrue(body.contains("\"confirm\":false"))
        assertTrue(body.contains("\"session_id\":null"))
    }
}
