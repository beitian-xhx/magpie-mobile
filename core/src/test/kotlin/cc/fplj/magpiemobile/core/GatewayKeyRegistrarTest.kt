package cc.fplj.magpiemobile.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GatewayKeyRegistrarTest {

    private val exe = "/data/app/x/lib/arm64/libmagpie_exec.so"

    @Test
    fun listCmdShape() {
        assertEquals("$exe gateway-key list", GatewayKeyRegistrar.listKeysCmd(exe))
    }

    @Test
    fun addCmdQuotesName() {
        assertEquals("$exe gateway-key add \"phone-app\"", GatewayKeyRegistrar.addCmd(exe, "phone-app"))
    }

    @Test
    fun invalidNameRejected() {
        assertFailsWith<IllegalArgumentException> { GatewayKeyRegistrar.addCmd(exe, "bad name") }
        assertFailsWith<IllegalArgumentException> { GatewayKeyRegistrar.addCmd(exe, "") }
        assertFailsWith<IllegalArgumentException> { GatewayKeyRegistrar.addCmd(exe, "a;rm") } // 注入拒绝
    }

    @Test
    fun alignmentCheckWorks() {
        val output = "sk-magpie-key-abc123 (phone-app)\nsk-magpie-key-def456 (laptop)"
        assertTrue(GatewayKeyRegistrar.appKeyRegistered(output, "sk-magpie-key-abc123"))
        assertFalse(GatewayKeyRegistrar.appKeyRegistered(output, "sk-magpie-key-xyz"))
    }
}
