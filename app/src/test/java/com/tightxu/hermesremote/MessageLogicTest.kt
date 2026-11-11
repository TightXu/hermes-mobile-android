package com.tightxu.hermesremote

import com.tightxu.hermesremote.data.Pagination
import com.tightxu.hermesremote.data.SessionMessage
import com.tightxu.hermesremote.data.SessionMessagesResponse
import com.tightxu.hermesremote.data.displayText
import com.tightxu.hermesremote.data.filterDisplayMessages
import com.tightxu.hermesremote.data.hasMorePages
import com.tightxu.hermesremote.data.hermesJson
import com.tightxu.hermesremote.data.mergeMessages
import com.tightxu.hermesremote.data.sortMessagesAscending
import com.tightxu.hermesremote.data.toDisplayMessages
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Pure message-list logic: filtering (only readable chat bubbles), pagination
 * cursor arithmetic (offset accumulates by pagination.returned, the RAW count,
 * not by the filtered count), merge/dedupe and ordering.
 */
class MessageLogicTest {

    private fun user(id: Int, text: String, ts: Double = 1.0) =
        SessionMessage(role = "user", content = text, timestamp = ts, id = id)

    private fun assistant(id: Int, text: String, ts: Double = 2.0, displayContent: String? = null, displayKind: String? = null) =
        SessionMessage(
            role = "assistant", content = text, timestamp = ts, id = id,
            displayContent = displayContent, displayKind = displayKind,
        )

    private fun tool(id: Int, ts: Double = 3.0) =
        SessionMessage(role = "tool", content = """{"result": 42}""", timestamp = ts, id = id)

    // ── filtering ──────────────────────────────────────────────────────────

    @Test
    fun `user messages with text are shown`() {
        assertEquals(listOf("hi"), toDisplayMessages(listOf(user(1, "hi"))).map { it.text })
    }

    @Test
    fun `blank user messages are hidden`() {
        assertEquals(0, toDisplayMessages(listOf(user(1, "   "), user(2, ""))).size)
    }

    @Test
    fun `assistant messages prefer displayContent over content`() {
        val shown = toDisplayMessages(
            listOf(assistant(1, "raw tool noise", displayContent = "Hi. What do you need?")),
        )
        assertEquals(1, shown.size)
        assertEquals("Hi. What do you need?", shown[0].text)
    }

    @Test
    fun `assistant falls back to content when displayContent is empty`() {
        val shown = toDisplayMessages(listOf(assistant(1, "answer", displayContent = "")))
        assertEquals(listOf("answer"), shown.map { it.text })
    }

    @Test
    fun `assistant tool-call stubs and hidden wrappers are not shown`() {
        val messages = listOf(
            assistant(1, ""),                       // empty tool-call stub
            assistant(2, "compact", displayKind = "hidden"), // compaction wrapper
            assistant(3, "visible"),
        )
        assertEquals(listOf("visible"), toDisplayMessages(messages).map { it.text })
    }

    @Test
    fun `tool and system roles are never shown`() {
        assertEquals(0, toDisplayMessages(listOf(tool(1), SessionMessage(role = "system", content = "sys", id = 2))).size)
    }

    @Test
    fun `filterDisplayMessages keeps only displayable messages`() {
        val messages = listOf(tool(1), user(2, "hi"), assistant(3, ""))
        assertEquals(listOf(2), filterDisplayMessages(messages).map { it.id })
    }

    // ── pagination cursor ──────────────────────────────────────────────────

    @Test
    fun `offset accumulates by returned raw count not visible count`() {
        // Page 1: 30 raw messages, only 2 of them readable chat.
        val rawPage1 = buildList {
            add(user(1, "hi", 1.0))
            add(assistant(2, "hello", 2.0))
            repeat(28) { add(tool(100 + it)) }
        }
        val page1 = SessionMessagesResponse(
            messages = rawPage1,
            pagination = Pagination(limit = 30, offset = 0, order = "latest", returned = rawPage1.size),
        )
        // ChatScreen does rawOffset += pagination.returned — 30, NOT the 2
        // visible bubbles. Mirrors that arithmetic:
        var rawOffset = 0
        rawOffset += page1.pagination!!.returned
        assertEquals(30, rawOffset)
        assertTrue(hasMorePages(page1.pagination!!.returned, 30))

        // Page 2 (offset 30): short page → no more.
        val page2 = SessionMessagesResponse(
            messages = listOf(user(3, "old", 0.5)),
            pagination = Pagination(limit = 30, offset = 30, order = "latest", returned = 1),
        )
        rawOffset += page2.pagination!!.returned
        assertEquals(31, rawOffset)
        assertEquals(false, hasMorePages(page2.pagination!!.returned, 30))
    }

    @Test
    fun `empty page means no more pages`() {
        assertEquals(false, hasMorePages(0, 30))
        assertEquals(false, hasMorePages(29, 30))
        assertEquals(true, hasMorePages(30, 30))
    }

    @Test
    fun `pagination parses from live server shape`() {
        val json = """
        {
          "session_id": "20261108_200921_db78de",
          "messages": [
            {"role":"user","content":"hi","id":115424,"timestamp":1794139763.26528},
            {"role":"assistant","content":"Hi. What do you need?","id":115425,"timestamp":1794139767.826382}
          ],
          "pagination": {"limit":3,"offset":0,"order":"latest","returned":2}
        }
        """.trimIndent()
        val parsed = hermesJson.decodeFromString<SessionMessagesResponse>(json)
        assertEquals(2, parsed.pagination?.returned)
        assertEquals(3, parsed.pagination?.limit)
        assertEquals(115424, parsed.messages[0].id)
        // oldest → newest within the page, as the server sends it
        assertTrue(parsed.messages[0].timestamp!! < parsed.messages[1].timestamp!!)
    }

    // ── merge / ordering ───────────────────────────────────────────────────

    @Test
    fun `merge replaces same id and sorts ascending`() {
        val existing = listOf(user(2, "two", 2.0), user(1, "one", 1.0))
        val incoming = listOf(user(3, "three", 3.0), user(2, "two EDITED", 2.0))
        val merged = mergeMessages(existing, incoming)
        assertEquals(listOf(1, 2, 3), merged.map { it.id })
        assertEquals("two EDITED", merged[1].content)
    }

    @Test
    fun `optimistic echo is superseded by real message with same content`() {
        val optimistic = user(-1, "hi", 99.0)
        val real = listOf(assistant(1, "Hi. What do you need?", 100.0), user(2, "hi", 98.0))
        val merged = mergeMessages(listOf(optimistic), real)
        assertEquals(listOf(2, 1), merged.map { it.id }) // -1 dropped, sorted by ts
    }

    @Test
    fun `optimistic echo survives while server has not echoed it yet`() {
        val optimistic = user(-1, "hi", 99.0)
        val incoming = listOf(assistant(1, "still thinking", 100.0))
        val merged = mergeMessages(listOf(optimistic), incoming)
        assertTrue(merged.any { it.id == -1 })
    }

    @Test
    fun `sort orders by timestamp then id`() {
        val shuffled = listOf(user(5, "same-ts-high-id", 1.0), user(4, "same-ts-low-id", 1.0), user(1, "older", 0.0))
        assertEquals(listOf(1, 4, 5), sortMessagesAscending(shuffled).map { it.id })
    }

    @Test
    fun `displayText returns null for tool messages`() {
        assertNull(tool(1).displayText())
    }
}
