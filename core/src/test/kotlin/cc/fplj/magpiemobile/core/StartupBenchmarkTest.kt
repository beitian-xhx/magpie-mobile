package cc.fplj.magpiemobile.core

import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class StartupBenchmarkTest {

    @BeforeEach
    fun setup() {
        StartupBenchmark.reset()
    }

    @Test
    fun initiallyHasNegativeDuration() {
        val metrics = StartupBenchmark.getMetrics()
        assertEquals(0L, metrics.processInitMs)
        assertEquals(-1L, metrics.totalToReadyMs)
        assertFalse(metrics.isReadyWithinBudget)
    }

    @Test
    fun computesTotalDurationWithinBudget() {
        val t0 = 1000L
        StartupBenchmark.recordProcessInit(t0)
        StartupBenchmark.recordServiceBind(t0 + 150L)
        StartupBenchmark.recordGatewayReady(t0 + 450L)

        val metrics = StartupBenchmark.getMetrics()
        assertEquals(1000L, metrics.processInitMs)
        assertEquals(1150L, metrics.serviceBindMs)
        assertEquals(1450L, metrics.gatewayReadyMs)
        assertEquals(450L, metrics.totalToReadyMs)
        assertTrue(metrics.isReadyWithinBudget)
    }

    @Test
    fun flagsExceededBudget() {
        val t0 = 1000L
        StartupBenchmark.recordProcessInit(t0)
        StartupBenchmark.recordGatewayReady(t0 + 950L)

        val metrics = StartupBenchmark.getMetrics()
        assertEquals(950L, metrics.totalToReadyMs)
        assertFalse(metrics.isReadyWithinBudget)
    }

    @Test
    fun resetClearsAllTimestamps() {
        StartupBenchmark.recordProcessInit(1000L)
        StartupBenchmark.recordGatewayReady(1200L)
        assertTrue(StartupBenchmark.getMetrics().totalToReadyMs > 0)

        StartupBenchmark.reset()
        assertEquals(-1L, StartupBenchmark.getMetrics().totalToReadyMs)
    }
}
