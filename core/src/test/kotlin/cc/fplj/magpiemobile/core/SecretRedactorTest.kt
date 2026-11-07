package cc.fplj.magpiemobile.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SecretRedactorTest {

    @Test
    fun gatewayKeyNoLongerPresent() {
        val key = "sk-magpie-key-0123456789abcdef0123456789abcdef"
        val out = SecretRedactor.redact("used key " + key + " for call")
        assertTrue(!out.contains(key), "完整 key 必须消失, got: " + out)
        assertTrue(out.contains("gw:**cdef"), "保留末4位线索, got: " + out)
        assertTrue(!out.contains("sk-magpie"), "不应残留 sk-magpie 触发词, got: " + out)
    }

    @Test
    fun bearerTokenNoLongerPresent() {
        val out = SecretRedactor.redact("Authorization: Bearer abcdef1234567890")
        assertTrue(!out.contains("abcdef1234567890"), "token 必须消失, got: " + out)
        assertTrue(out.contains("Bearer:**7890"), "got: " + out)
    }

    @Test
    fun querySecretsNoLongerPresent() {
        val out = SecretRedactor.redact("POST /api?password=supersecret123&x=1")
        assertTrue(!out.contains("supersecret123"), "got: " + out)
        assertTrue(out.contains("password=**"), "got: " + out)
        assertTrue(out.contains("x=1"), "无害参数不动, got: " + out)
    }

    @Test
    fun genericSkKeyMasked() {
        val out = SecretRedactor.redact("key sk-proj-abcdefgh1234 used")
        assertTrue(!out.contains("abcdefgh1234"), "got: " + out)
        assertTrue(out.contains("sk:**"), "got: " + out)
    }

    @Test
    fun nativeAdminUrlKeyIsMasked() {
        val out = SecretRedactor.redact("magpie web on http://127.0.0.1:3430/?k=secretAdminLinkValue&x=1")
        assertTrue(!out.contains("secretAdminLinkValue"))
        assertTrue(out.contains("x=1"))
    }

    @Test
    fun cleanTextUnchanged() {
        val clean = "engine exited with code 0, nothing sensitive"
        assertEquals(clean, SecretRedactor.redact(clean))
    }
}
