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
    fun fromKeyBindsCanonicalCookieNameAndPort() {
        val default = ManagerCredential.fromKey(value)
        assertEquals(3430, default.port)
        assertEquals("magpie_web_3430", default.cookieName)
        val custom = ManagerCredential.fromKey(value, 45123)
        assertEquals(45123, custom.port)
        assertEquals("magpie_web_45123=$value", custom.cookieHeader())
        assertEquals(3425, ManagerCredential.create("magpie_web_3425", value).port) // legacy construction only
    }

    @Test
    fun invalidPortsAndForgedNamesAreRejectedWithoutEchoingInput() {
        for (port in listOf(-1, 0, 65536)) {
            assertFailsWith<IllegalArgumentException> { ManagerCredential.fromKey(value, port) }
        }
        for (name in listOf("magpie_web_0", "magpie_web_65536", "magpie_web_03430", "magpie_web_+3430", "magpie_web_3430;secret=$value")) {
            val error = assertFailsWith<IllegalArgumentException> { ManagerCredential.create(name, value) }
            assertTrue(!error.toString().contains(value))
        }
    }

    @Test
    fun nonAsciiAndHeaderInjectionValuesAreRejectedWithoutLeaking() {
        for (bad in listOf(value + "中文", value + ";evil", value + "\r\nheader")) {
            val error = assertFailsWith<IllegalArgumentException> { ManagerCredential.fromKey(bad) }
            assertTrue(!error.toString().contains(value))
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
