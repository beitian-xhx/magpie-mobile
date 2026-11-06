package cc.fplj.magpiemobile.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ProvisioningEvaluatorTest {

    @TempDir
    lateinit var tmp: File

    private fun layoutWithPayloads(configReady: Boolean): EnginePaths.Layout {
        val lib = File(tmp, "lib").apply { mkdirs() }
        File(lib, EnginePaths.MAGPIE_LIB).writeText("elf")
        File(lib, EnginePaths.BUN_LIB).writeText("elf")
        if (configReady) File(tmp, "config").mkdirs()
        return EnginePaths.Layout(
            nativeLibraryDir = lib,
            homeDir = File(tmp, "home"),
            cacheDir = File(tmp, "cache"),
            configDir = File(tmp, "config"),
        )
    }

    @Test
    fun missingPayloadMeansNotInstalled() {
        val lib = File(tmp, "empty-lib").apply { mkdirs() }
        val l = EnginePaths.Layout(lib, tmp, tmp, File(tmp, "config"))
        val s = ProvisioningEvaluator().evaluate(l, EngineSupervisor())
        assertEquals(ProvisioningState.NotInstalled, s)
    }

    @Test
    fun missingConfigMeansNeedsSetup() {
        val l = layoutWithPayloads(configReady = false)
        val s = ProvisioningEvaluator().evaluate(l, EngineSupervisor())
        assertEquals(ProvisioningState.NeedsSetup, s)
    }

    @Test
    fun stoppedWithEverythingReadyMeansReady() {
        val s = ProvisioningEvaluator().evaluate(layoutWithPayloads(true), EngineSupervisor())
        assertEquals(ProvisioningState.Ready, s)
    }

    @Test
    fun supervisorStatesMapDirectly() {
        val l = layoutWithPayloads(true)
        val sup = EngineSupervisor()
        sup.onUserStart()
        assertEquals(ProvisioningState.Starting, ProvisioningEvaluator().evaluate(l, sup))
        sup.onProcessUp()
        val running = ProvisioningEvaluator().evaluate(l, sup)
        assertTrue(running is ProvisioningState.Running)
        // 崩溃封顶 → Failed 带原因
        sup.onProcessExited(EngineSupervisor.StopReason.CRASH); sup.onBackoffElapsed()
        sup.onProcessExited(EngineSupervisor.StopReason.CRASH); sup.onBackoffElapsed()
        sup.onProcessExited(EngineSupervisor.StopReason.CRASH); sup.onBackoffElapsed()
        sup.onProcessExited(EngineSupervisor.StopReason.CRASH)
        val failed = ProvisioningEvaluator().evaluate(l, sup)
        assertTrue(failed is ProvisioningState.Failed)
        assertTrue(failed.lastError.contains("崩溃"))
    }

    @Test
    fun nullLayoutMeansNotInstalled() {
        assertEquals(ProvisioningState.NotInstalled, ProvisioningEvaluator().evaluate(null, EngineSupervisor()))
    }
}
