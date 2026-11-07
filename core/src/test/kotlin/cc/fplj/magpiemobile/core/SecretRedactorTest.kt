package cc.fplj.magpiemobile.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class SecretRedactorTest {

    @Test
    fun gatewayKeyMasked() {
        val key = "sk-magpie-key-0123456789abcdef0123456789abcdef"
        val out = SecretRedactor.redact("used key $key for call")
        assertTrue(!out.contains(key))
        assertTrue(out.contains("sk-m") && out.endsWith("…"))
    }

    @Test
    fun bearerTokenMasked() {
        val out = SecretRedactor.redact("Authorization: Bearer abcdef1234567890")
        assertTrue(!out.contains("abcdef1234567890"))
        assertTrue(out.contains("Bearer"))
    }

    @Test
    fun querySecretsMasked() {
        val out = SecretRedactor.redact("POST /api?password=supersecret123&x=1")
        assertTrue(!out.contains("supersecret123"))
        assertTrue(out.contains("password=sup…"))
        assertTrue(out.contains("x=1")) // 无害参数不动
    }

    @Test
    fun cleanTextUnchanged() {
        val clean = "engine exited with code 0, nothing sensitive"
        assertEquals(clean, SecretRedactor.redact(clean))
    }
}
