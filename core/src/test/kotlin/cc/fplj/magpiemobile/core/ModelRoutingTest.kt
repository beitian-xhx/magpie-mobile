package cc.fplj.magpiemobile.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ModelRoutingTest {

    @Test
    fun validRoutingRuleResolvesChainInOrder() {
        val rule = RoutingRule(
            alias = "fast-gpt",
            targetModel = "openai/gpt-4o-mini",
            fallbackModels = listOf("anthropic/claude-3-haiku", "local/mock-fast")
        )
        val table = ModelRoutingTable(listOf(rule))

        assertEquals(
            listOf("openai/gpt-4o-mini", "anthropic/claude-3-haiku", "local/mock-fast"),
            table.resolve("fast-gpt")
        )
        // Non-alias models pass through unchanged
        assertEquals(listOf("custom/raw-model"), table.resolve("custom/raw-model"))
    }

    @Test
    fun disabledRuleDoesNotApply() {
        val rule = RoutingRule(
            alias = "fast-gpt",
            targetModel = "openai/gpt-4o-mini",
            enabled = false
        )
        val table = ModelRoutingTable(listOf(rule))
        assertEquals(listOf("fast-gpt"), table.resolve("fast-gpt"))
    }

    @Test
    fun duplicateTargetInFallbackIsDeduplicated() {
        val rule = RoutingRule(
            alias = "my-model",
            targetModel = "m1",
            fallbackModels = listOf("m1", "m2")
        )
        assertEquals(listOf("m1", "m2"), rule.resolutionChain())
    }

    @Test
    fun invalidAliasOrTargetFailsValidation() {
        assertFailsWith<IllegalArgumentException> {
            RoutingRule(alias = "", targetModel = "target")
        }
        assertFailsWith<IllegalArgumentException> {
            RoutingRule(alias = "alias", targetModel = "invalid model with spaces")
        }
        assertFailsWith<IllegalArgumentException> {
            RoutingRule(alias = "alias", targetModel = "target", fallbackModels = listOf("bad id!"))
        }
    }
}
