package cc.fplj.magpiemobile.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class EngineSupervisorTest {

    private fun fresh() = EngineSupervisor()

    @Test
    fun userStartGoesToStarting() {
        val s = fresh()
        val d = s.onUserStart()
        assertTrue(d is EngineSupervisor.Decision.StartProcess)
        assertEquals(EngineSupervisor.State.STARTING, s.snapshot().state)
    }

    @Test
    fun processUpGoesToRunning() {
        val s = fresh()
        s.onUserStart(); s.onProcessUp()
        assertEquals(EngineSupervisor.State.RUNNING, s.snapshot().state)
    }

    @Test
    fun crashSchedulesBackoffThenRestart() {
        val s = fresh()
        s.onUserStart(); s.onProcessUp()
        val d = s.onProcessExited(EngineSupervisor.StopReason.CRASH)
        assertTrue(d is EngineSupervisor.Decision.ScheduleRestart)
        assertEquals(1000L, (d as EngineSupervisor.Decision.ScheduleRestart).delayMs)
        assertEquals(1, s.snapshot().restartCount)
        val d2 = s.onBackoffElapsed()
        assertTrue(d2 is EngineSupervisor.Decision.StartProcess)
    }

    @Test
    fun crashBackoffEscalatesAndCapsAtFailed() {
        val s = fresh()
        s.onUserStart()
        // 1s → 2s → 4s，第 4 次 crash 达到 maxAutoRestart=3 → FAILED
        val delays = mutableListOf<Long>()
        repeat(3) {
            val decision = s.onProcessExited(EngineSupervisor.StopReason.CRASH)
            assertTrue(decision is EngineSupervisor.Decision.ScheduleRestart)
            delays += (decision as EngineSupervisor.Decision.ScheduleRestart).delayMs
            assertEquals(decision.delayMs, s.snapshot().nextRestartDelayMs)
            s.onBackoffElapsed()
            assertNull(s.snapshot().nextRestartDelayMs)
        }
        assertEquals(listOf(1000L, 2000L, 4000L), delays)
        val d = s.onProcessExited(EngineSupervisor.StopReason.CRASH)
        assertEquals(EngineSupervisor.State.FAILED, s.snapshot().state)
        assertTrue(d is EngineSupervisor.Decision.None)
    }

    @Test
    fun userStopNeverAutoRestarts() {
        val s = fresh()
        s.onUserStart(); s.onProcessUp()
        val d = s.onProcessExited(EngineSupervisor.StopReason.USER_STOP)
        assertTrue(d is EngineSupervisor.Decision.None)
        assertEquals(EngineSupervisor.State.STOPPED, s.snapshot().state)
    }

    @Test
    fun timeoutStopAlsoDoesNotRestart() {
        val s = fresh()
        s.onUserStart(); s.onProcessUp()
        val d = s.onProcessExited(EngineSupervisor.StopReason.TIMEOUT)
        assertTrue(d is EngineSupervisor.Decision.None)
        assertEquals(EngineSupervisor.State.STOPPED, s.snapshot().state)
    }

    @Test
    fun userStartResetsRestartCount() {
        val s = fresh()
        s.onUserStart(); s.onProcessUp()
        s.onProcessExited(EngineSupervisor.StopReason.CRASH); s.onBackoffElapsed()
        s.onProcessExited(EngineSupervisor.StopReason.CRASH) // restartCount=2
        // 用户显式重新启动：计数清零，回 STARTING
        val d = s.onUserStart()
        assertTrue(d is EngineSupervisor.Decision.StartProcess)
        assertEquals(0, s.snapshot().restartCount)
        assertNull(s.snapshot().nextRestartDelayMs)
    }

    @Test
    fun permanentFailureFromStartingRetainsFailedWithoutRecovery() {
        val s = fresh()
        s.onUserStart()
        assertEquals(EngineSupervisor.Decision.None, s.onPermanentFailure())
        assertEquals(EngineSupervisor.State.FAILED, s.snapshot().state)
        assertEquals(0, s.snapshot().restartCount)
        assertNull(s.snapshot().nextRestartDelayMs)
        assertEquals(EngineSupervisor.Decision.None, s.onBackoffElapsed())
        s.onProcessUp()
        s.onProcessExited(EngineSupervisor.StopReason.CRASH)
        assertEquals(EngineSupervisor.State.FAILED, s.snapshot().state)
    }

    @Test
    fun permanentFailureClearsAlreadyScheduledDelayAndUserStartResetsBudget() {
        val s = fresh()
        s.onUserStart()
        s.onProcessExited(EngineSupervisor.StopReason.CRASH)
        assertEquals(1000L, s.snapshot().nextRestartDelayMs)
        s.onPermanentFailure()
        assertEquals(EngineSupervisor.State.FAILED, s.snapshot().state)
        assertEquals(1, s.snapshot().restartCount)
        assertNull(s.snapshot().nextRestartDelayMs)
        assertEquals(EngineSupervisor.Decision.StartProcess(0), s.onUserStart())
        assertEquals(0, s.snapshot().restartCount)
        assertNull(s.snapshot().nextRestartDelayMs)
    }

    @Test
    fun stopCancelsBackoffAndIgnoresLateProcessEvents() {
        val s = fresh()
        s.onUserStart()
        s.onProcessExited(EngineSupervisor.StopReason.CRASH)
        s.onProcessExited(EngineSupervisor.StopReason.USER_STOP)
        assertNull(s.snapshot().nextRestartDelayMs)
        assertEquals(EngineSupervisor.Decision.None, s.onBackoffElapsed())
        assertEquals(EngineSupervisor.Decision.None, s.onProcessUp())
        assertEquals(EngineSupervisor.Decision.None, s.onProcessExited(EngineSupervisor.StopReason.CRASH))
        assertEquals(EngineSupervisor.Decision.None, s.onPermanentFailure())
        assertEquals(EngineSupervisor.State.STOPPED, s.snapshot().state)
    }

    @Test
    fun repeatedExitWhileBackoffDoesNotSpendBudgetTwice() {
        val s = fresh()
        s.onUserStart()
        s.onProcessExited(EngineSupervisor.StopReason.CRASH)
        s.onProcessExited(EngineSupervisor.StopReason.CRASH)
        s.onProcessUp() // A late readiness signal cannot bypass a pending timer.
        assertEquals(EngineSupervisor.State.BACKOFF, s.snapshot().state)
        assertEquals(1, s.snapshot().restartCount)
        assertEquals(1000L, s.snapshot().nextRestartDelayMs)
        assertEquals(EngineSupervisor.Decision.StartProcess(1), s.onBackoffElapsed())
        assertEquals(1, s.snapshot().restartCount)
        assertNull(s.snapshot().nextRestartDelayMs)
    }

    @Test
    fun zeroRecoveryBudgetFailsOnFirstCrash() {
        val s = EngineSupervisor(maxAutoRestart = 0)
        s.onUserStart()
        assertEquals(EngineSupervisor.Decision.None, s.onProcessExited(EngineSupervisor.StopReason.CRASH))
        assertEquals(EngineSupervisor.State.FAILED, s.snapshot().state)
        assertEquals(0, s.snapshot().restartCount)
        assertNull(s.snapshot().nextRestartDelayMs)
    }

    @Test
    fun negativeRecoveryBudgetRejected() {
        assertFailsWith<IllegalArgumentException> { EngineSupervisor(maxAutoRestart = -1) }
    }
}
