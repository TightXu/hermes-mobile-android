package com.tightxu.hermesremote

import com.tightxu.hermesremote.data.LoginRequest
import com.tightxu.hermesremote.data.hermesJson
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertTrue
import org.junit.Test

/** The gateway's password-login endpoint requires the provider id in the body. */
class LoginRequestTest {
    @Test
    fun loginRequestIncludesBasicProvider() {
        val json = hermesJson.encodeToString(LoginRequest(username = "u", password = "p"))
        assertTrue("provider missing from $json", json.contains("\"provider\":\"basic\""))
    }
}
