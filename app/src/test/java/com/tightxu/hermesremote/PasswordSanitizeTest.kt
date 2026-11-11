package com.tightxu.hermesremote

import com.tightxu.hermesremote.ui.login.sanitizePasswordInput
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Login password single-line defense (pure part): some IMEs insert \n / \r
 * via their "enter" key; the sanitizer strips them so the password can never
 * become two lines.
 */
class PasswordSanitizeTest {

    @Test
    fun `strips trailing newline`() {
        assertEquals("s3cret", sanitizePasswordInput("s3cret\n"))
    }

    @Test
    fun `strips carriage return`() {
        assertEquals("s3cret", sanitizePasswordInput("s3cret\r"))
    }

    @Test
    fun `strips crlf and inner newlines`() {
        assertEquals("s3cret", sanitizePasswordInput("s3cret\r\n"))
        assertEquals("ab", sanitizePasswordInput("a\nb"))
    }

    @Test
    fun `keeps other characters including spaces and unicode`() {
        assertEquals("pa ss word 你好!@#", sanitizePasswordInput("pa ss word 你好!@#"))
    }

    @Test
    fun `empty stays empty`() {
        assertEquals("", sanitizePasswordInput(""))
        assertEquals("", sanitizePasswordInput("\n\r\n"))
    }
}
