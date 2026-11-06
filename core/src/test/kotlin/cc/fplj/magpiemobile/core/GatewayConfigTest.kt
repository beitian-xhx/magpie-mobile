package cc.fplj.magpiemobile.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GatewayConfigTest {

    @Test
    fun rejectsBlankGatewayKey() {
        val e = assertFailsWith<IllegalArgumentException> {
            GatewayConfig(gatewayKey = "  ")
        }
        assertTrue(e.message!!.contains("loopback 也必须鉴权"))
    }

    @Test
    fun rejectsNonLoopbackAdminUrl() {
        val e = assertFailsWith<IllegalArgumentException> {
            GatewayConfig(gatewayKey = "k", adminBaseUrl = "http://192.168.1.5:3430")
        }
        assertTrue(e.message!!.contains("只允许 loopback"))
    }

    @Test
    fun acceptsLoopbackAdminVariants() {
        // 127.0.0.1 与 localhost 都是允许的管理面绑定
        GatewayConfig(gatewayKey = "k", adminBaseUrl = "http://127.0.0.1:3430")
        GatewayConfig(gatewayKey = "k", adminBaseUrl = "http://localhost:3430")
    }

    @Test
    fun endpointPathsFollowContract() {
        val c = GatewayConfig(gatewayKey = "k")
        assertEquals("http://127.0.0.1:3425/v1/models", c.modelsPath())
        assertEquals("http://127.0.0.1:3425/v1/chat/completions", c.chatCompletionsPath())
    }
}
