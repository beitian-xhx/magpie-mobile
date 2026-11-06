package cc.fplj.magpiemobile.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class UpdateGuardTest {

    @Test
    fun apkSourceAllowed() {
        val d = UpdateGuard.decideNativeUpdate("apk")
        assertTrue(d is UpdateGuard.NativeDecision.AllowedViaApkOnly)
    }

    @Test
    fun runtimeDownloadCacheRejected() {
        for (src in listOf("download", "cache", "runtime")) {
            val d = UpdateGuard.decideNativeUpdate(src)
            assertTrue(d is UpdateGuard.NativeDecision.Rejected && d.reason.contains("W^X"))
        }
        // 未知来源同样拒绝
        assertTrue(UpdateGuard.decideNativeUpdate("weird") is UpdateGuard.NativeDecision.Rejected)
    }

    @Test
    fun pinnedSpecFormats() {
        assertTrue(UpdateGuard.isPinnedPluginSpec("zcode@0.1.51"))
        assertTrue(UpdateGuard.isPinnedPluginSpec("acme@1"))
        assertTrue(UpdateGuard.isPinnedPluginSpec("acme@1.2"))
        assertTrue(!UpdateGuard.isPinnedPluginSpec("zcode"))           // 未固定
        assertTrue(!UpdateGuard.isPinnedPluginSpec("zcode@latest"))    // 非数字版本
        assertTrue(!UpdateGuard.isPinnedPluginSpec("@1.2.3"))          // 无名
    }
}
