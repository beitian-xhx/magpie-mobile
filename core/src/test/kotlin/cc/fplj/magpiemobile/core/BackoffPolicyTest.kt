package cc.fplj.magpiemobile.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class BackoffPolicyTest {

    @Test
    fun defaultSequenceMatchesSupervisorBehavior() {
        val p = BackoffPolicy()
        assertEquals(listOf(1000L, 2000L, 4000L, 8000L), p.sequence(4))
        // 封顶后不再增长
        assertEquals(8000L, p.delayFor(10))
    }

    @Test
    fun invalidConstructorArgsRejected() {
        assertFailsWith<IllegalArgumentException> { BackoffPolicy(baseMs = 0) }
        assertFailsWith<IllegalArgumentException> { BackoffPolicy(growth = 0) }
        assertFailsWith<IllegalArgumentException> { BackoffPolicy(baseMs = 2000, capMs = 1000) }
    }

    @Test
    fun negativeAttemptRejected() {
        assertFailsWith<IllegalArgumentException> { BackoffPolicy().delayFor(-1) }
    }

    @Test
    fun linearGrowthVariantWorks() {
        val p = BackoffPolicy(baseMs = 500, growth = 1, capMs = 500)
        assertEquals(listOf(500L, 500L, 500L), p.sequence(3))
    }
}
