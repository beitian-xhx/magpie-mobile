package cc.fplj.magpiemobile.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ManagerCredentialTest {

    private val value = "0123456789abcdef0123456789abcdef"

    @Test
    fun createsHeaderForAllowedName() {
        val c = ManagerCredential.create("magpie_web_3430", value)
        assertEquals("magpie_web_3430=$value", c.cookieHeader())
    }

    @Test
    fun unknownCookieNameRejected() {
        assertFailsWith<IllegalArgumentException> {
            ManagerCredential.create("sid", value)
        }
    }

    @Test
    fun shortOrIllegalValueRejected() {
        assertFailsWith<IllegalArgumentException> {
            ManagerCredential.create("magpie_web_3430", "short")
        }
        assertFailsWith<IllegalArgumentException> {
            ManagerCredential.create("magpie_web_3430", value + " 空格") // 空格非法
        }
    }

    @Test
    fun toStringNeverLeaksFullValue() {
        val c = ManagerCredential.create("magpie_web_3430", value)
        val s = c.toString()
        assertTrue(s.contains("0123"))
        assertTrue(!s.contains(value)) // 完整值绝不出现
        // 任何意外序列化（如放进 map 打日志）都只看到 redacted
        val map = mapOf("cred" to c)
        assertTrue(!map.toString().contains(value))
    }
}
