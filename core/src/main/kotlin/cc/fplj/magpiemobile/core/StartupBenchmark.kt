package cc.fplj.magpiemobile.core

import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

data class StartupMetrics(
    val processInitMs: Long = 0,
    val serviceBindMs: Long = 0,
    val gatewayReadyMs: Long = 0,
    val totalToReadyMs: Long = -1,
    val isReadyWithinBudget: Boolean = false,
)

/**
 * Startup latency and cold-start benchmark recorder (P4-2).
 * Verifies that the gateway achieves ready state within the 800ms threshold from plan.md §6.
 */
object StartupBenchmark {
    const val COLD_START_BUDGET_MS = 800L

    private val processInitTimestamp = AtomicLong(0)
    private val serviceBindTimestamp = AtomicLong(0)
    private val gatewayReadyTimestamp = AtomicLong(0)
    private val cachedMetrics = AtomicReference(StartupMetrics())

    fun recordProcessInit(nowMs: Long = System.currentTimeMillis()) {
        processInitTimestamp.compareAndSet(0, nowMs)
        recomputeMetrics()
    }

    fun recordServiceBind(nowMs: Long = System.currentTimeMillis()) {
        serviceBindTimestamp.set(nowMs)
        recomputeMetrics()
    }

    fun recordGatewayReady(nowMs: Long = System.currentTimeMillis()) {
        gatewayReadyTimestamp.set(nowMs)
        recomputeMetrics()
    }

    fun getMetrics(): StartupMetrics = cachedMetrics.get()

    fun reset() {
        processInitTimestamp.set(0)
        serviceBindTimestamp.set(0)
        gatewayReadyTimestamp.set(0)
        cachedMetrics.set(StartupMetrics())
    }

    private fun recomputeMetrics() {
        val pInit = processInitTimestamp.get()
        val sBind = serviceBindTimestamp.get()
        val gReady = gatewayReadyTimestamp.get()

        val total = if (pInit > 0 && gReady >= pInit) {
            gReady - pInit
        } else {
            -1L
        }

        val withinBudget = total in 0..COLD_START_BUDGET_MS

        cachedMetrics.set(
            StartupMetrics(
                processInitMs = pInit,
                serviceBindMs = sBind,
                gatewayReadyMs = gReady,
                totalToReadyMs = total,
                isReadyWithinBudget = withinBudget,
            )
        )
    }
}
