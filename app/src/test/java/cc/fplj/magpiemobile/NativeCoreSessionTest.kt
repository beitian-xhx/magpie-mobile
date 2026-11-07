package cc.fplj.magpiemobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Test

class NativeCoreSessionTest {
    private val key = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef"
    @Test fun publicConnectionStateDoesNotExposeManagementCredential() {
        val owner = GatewayRuntime.attach()
        NativeCoreSession.attach(owner, key)
        try {
            assertFalse(NativeCoreSession.status.value.toString().contains(key))
            assertFalse(NativeCoreSession.status.value.ready)
            assertThrows(IllegalStateException::class.java) { NativeCoreSession.gatewayConfig() }
        } finally { NativeCoreSession.disconnect(owner) }
    }
    @Test fun lateOldServiceDisconnectDoesNotDestroyNewBinding() {
        val old = GatewayRuntime.attach()
        NativeCoreSession.attach(old, key)
        val current = GatewayRuntime.attach()
        NativeCoreSession.attach(current, key.reversed())
        val before = NativeCoreSession.status.value
        NativeCoreSession.disconnect(old)
        assertEquals(before, NativeCoreSession.status.value)
        NativeCoreSession.disconnect(current)
        assertEquals(NativeCoreSession.Phase.DISCONNECTED, NativeCoreSession.status.value.phase)
    }
}
