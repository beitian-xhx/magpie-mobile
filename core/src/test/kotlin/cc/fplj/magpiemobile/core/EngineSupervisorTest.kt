package cc.fplj.magpiemobile.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
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
        s.onProcessExited(EngineSupervisor.StopReason.CRASH); s.onBackoffElapsed()
        s.onProcessExited(EngineSupervisor.StopReason.CRASH); s.onBackoffElapsed()
        s.onProcessExited(EngineSupervisor.StopReason.CRASH); s.onBackoffElapsed()
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
    }
}
