package cc.fplj.magpiemobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoutingViewModelTest {

    @Test
    fun addRuleSucceedsAndOverwritesExistingAlias() {
        val vm = RoutingViewModel()
        vm.addRule("fast-gpt", "openai/gpt-4o-mini", listOf("mock/backup"))

        val state = vm.state.value
        assertEquals(1, state.rules.size)
        assertEquals("fast-gpt", state.rules[0].alias)
        assertEquals("openai/gpt-4o-mini", state.rules[0].targetModel)
        assertEquals(listOf("mock/backup"), state.rules[0].fallbackModels)
        assertTrue(state.notice.contains("fast-gpt"))

        // Overwrite
        vm.addRule("fast-gpt", "anthropic/claude-3-haiku")
        val state2 = vm.state.value
        assertEquals(1, state2.rules.size)
        assertEquals("anthropic/claude-3-haiku", state2.rules[0].targetModel)
    }

    @Test
    fun addInvalidRuleSetsError() {
        val vm = RoutingViewModel()
        vm.addRule("bad alias with spaces", "target")

        val state = vm.state.value
        assertTrue(state.rules.isEmpty())
        assertTrue(state.error.contains("别名格式无效"))
    }

    @Test
    fun removeRuleAndToggleRule() {
        val vm = RoutingViewModel()
        vm.addRule("alias1", "model1")
        assertEquals(1, vm.state.value.rules.size)

        vm.toggleRule("alias1")
        assertFalse(vm.state.value.rules[0].enabled)

        vm.removeRule("alias1")
        assertTrue(vm.state.value.rules.isEmpty())
    }
}
