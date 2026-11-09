package cc.fplj.magpiemobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KeyPoolViewModelTest {

    @Test
    fun addKeyMasksSecretAndAddsToPool() {
        val vm = KeyPoolViewModel()
        vm.addKey("provider-1", "backup-key-1", "sk-abcdef1234567890")

        val state = vm.state.value
        assertEquals("provider-1", state.providerId)
        assertEquals(1, state.keys.size)
        assertEquals("backup-key-1", state.keys[0].id)
        assertEquals("sk-••••7890", state.keys[0].maskedSecret)
        assertFalse(state.keys[0].maskedSecret.contains("abcdef123456"))
        assertTrue(state.notice.contains("backup-key-1"))
    }

    @Test
    fun addBlankKeySetsError() {
        val vm = KeyPoolViewModel()
        vm.addKey("provider-1", "", "")

        val state = vm.state.value
        assertTrue(state.keys.isEmpty())
        assertTrue(state.error.contains("不能为空"))
    }

    @Test
    fun toggleAndRemoveKey() {
        val vm = KeyPoolViewModel()
        vm.addKey("provider-1", "k1", "sk-1234")

        vm.toggleKey("k1")
        assertFalse(vm.state.value.keys[0].enabled)

        vm.removeKey("k1")
        assertTrue(vm.state.value.keys.isEmpty())
    }
}
