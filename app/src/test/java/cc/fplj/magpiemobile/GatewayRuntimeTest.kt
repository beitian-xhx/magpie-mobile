package cc.fplj.magpiemobile

import cc.fplj.magpiemobile.core.GatewayEngineController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class GatewayRuntimeTest {
    @Test fun currentServiceUpdatesTheSameObservableState() {
        val owner = GatewayRuntime.attach()
        GatewayRuntime.publish(owner, GatewayEngineController.Status(GatewayEngineController.Phase.RUNNING, "ports ready"))
        assertEquals(GatewayEngineController.Phase.RUNNING, GatewayRuntime.status.value.phase)
        assertEquals("ports ready", GatewayRuntime.status.value.message)
    }

    @Test fun lateCancelledServiceCannotOverwriteNewRun() {
        val old = GatewayRuntime.attach()
        val current = GatewayRuntime.attach()
        GatewayRuntime.publish(current, GatewayEngineController.Status(GatewayEngineController.Phase.RUNNING, "new run"))
        GatewayRuntime.publish(old, GatewayEngineController.Status(GatewayEngineController.Phase.FAILED, "old failure"))
        assertEquals("new run", GatewayRuntime.status.value.message)
    }

    @Test fun failureRemainsVisibleWhenServiceCleansUp() {
        val owner = GatewayRuntime.attach()
        GatewayRuntime.publish(owner, GatewayEngineController.Status(GatewayEngineController.Phase.FAILED, "exit=1"))
        GatewayRuntime.publish(owner, GatewayEngineController.Status(GatewayEngineController.Phase.STOPPED, "cleanup"))
        assertEquals("exit=1", GatewayRuntime.status.value.message)
        GatewayRuntime.stoppedWithoutService()
        assertEquals(GatewayEngineController.Phase.STOPPED, GatewayRuntime.status.value.phase)
    }

    @Test fun diagnosticsRedactTheNativeWebLinkKey() {
        val owner = GatewayRuntime.attach()
        val key = "secretAdminLinkValue"
        GatewayRuntime.publish(owner, GatewayEngineController.Status(GatewayEngineController.Phase.FAILED,
            "http://127.0.0.1/?k=" + key, outputTail = "http://127.0.0.1/?k=" + key))
        assertFalse(GatewayRuntime.status.value.message.contains(key))
        assertFalse(GatewayRuntime.status.value.outputTail.contains(key))
    }
}
