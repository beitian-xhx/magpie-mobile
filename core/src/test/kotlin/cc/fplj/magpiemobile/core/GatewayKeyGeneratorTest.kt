package cc.fplj.magpiemobile.core

import org.junit.jupiter.api.Test
import java.security.SecureRandom
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class GatewayKeyGeneratorTest {

    @Test
    fun generatesUpstreamFormat() {
        val k = GatewayKeyGenerator.generate(SecureRandom())
        assertTrue(k.startsWith("sk-magpie-key-"))
        assertEquals("sk-magpie-key-".length + 32, k.length)
        assertTrue(GatewayKeyGenerator.looksValid(k))
    }

    @Test
    fun generationIsRandomEnough() {
        val a = GatewayKeyGenerator.generate(SecureRandom())
        val b = GatewayKeyGenerator.generate(SecureRandom())
        assertTrue(a != b)
    }

    @Test
    fun validationRejectsMalformed() {
        assertFalse(GatewayKeyGenerator.looksValid("sk-magpie-key-short"))
        assertFalse(GatewayKeyGenerator.looksValid("sk-magpie-key-" + "g".repeat(32))) // 非 hex
        assertFalse(GatewayKeyGenerator.looksValid("sk-magpie-key-" + "a".repeat(31))) // 差一位
        assertFalse(GatewayKeyGenerator.looksValid(""))
    }

    @Test
    fun uppercaseHexRejected() {
        // 上游 hex 小写；大写视为无效（保守）
        assertFalse(GatewayKeyGenerator.looksValid("sk-magpie-key-" + "A".repeat(32)))
    }
}
