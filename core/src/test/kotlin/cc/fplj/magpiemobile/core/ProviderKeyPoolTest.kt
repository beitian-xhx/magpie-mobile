package cc.fplj.magpiemobile.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ProviderKeyPoolTest {

    @Test
    fun roundRobinSelectsKeysInSequence() {
        val pool = ProviderKeyPool(
            providerId = "p1",
            keys = listOf(
                ProviderPooledKey("k1", "secret-1"),
                ProviderPooledKey("k2", "secret-2"),
                ProviderPooledKey("k3", "secret-3")
            )
        )

        assertEquals(3, pool.availableKeysCount())
        assertEquals("k1", pool.nextKey()?.id)
        assertEquals("k2", pool.nextKey()?.id)
        assertEquals("k3", pool.nextKey()?.id)
        assertEquals("k1", pool.nextKey()?.id)
    }

    @Test
    fun disabledKeyIsSkippedInRoundRobin() {
        val pool = ProviderKeyPool(
            providerId = "p1",
            keys = listOf(
                ProviderPooledKey("k1", "secret-1", enabled = false),
                ProviderPooledKey("k2", "secret-2", enabled = true)
            )
        )

        assertEquals(1, pool.availableKeysCount())
        assertEquals("k2", pool.nextKey()?.id)
        assertEquals("k2", pool.nextKey()?.id)
    }

    @Test
    fun emptyOrAllDisabledPoolReturnsNull() {
        val emptyPool = ProviderKeyPool("p1")
        assertNull(emptyPool.nextKey())

        val disabledPool = ProviderKeyPool(
            providerId = "p1",
            keys = listOf(ProviderPooledKey("k1", "secret-1", enabled = false))
        )
        assertNull(disabledPool.nextKey())
    }

    @Test
    fun secretIsRedactedInToString() {
        val key = ProviderPooledKey("k1", "super-secret-key-12345")
        assertFalse(key.toString().contains("super-secret-key-12345"))
        assertTrue(key.toString().contains("<redacted>"))
    }
}
