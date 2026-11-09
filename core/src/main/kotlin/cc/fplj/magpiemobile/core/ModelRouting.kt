package cc.fplj.magpiemobile.core

import java.util.Collections
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * Model routing rule / alias entry for P2-2.
 * Resolves client model aliases (e.g. "gpt-4o") to specific target models or fallback chains.
 * Strict validation: only alphanumeric, dots, dashes, underscores, and slashes.
 */
@Serializable
data class RoutingRule(
    val alias: String,
    val targetModel: String,
    val fallbackModels: List<String> = emptyList(),
    val enabled: Boolean = true,
) {
    init {
        require(validModelId(alias)) { "别名格式无效: $alias" }
        require(validModelId(targetModel)) { "目标模型格式无效: $targetModel" }
        require(fallbackModels.all { validModelId(it) }) { "回退模型格式无效" }
        require(fallbackModels.size <= 20) { "回退模型数量超出上限" }
    }

    /**
     * Resolves the execution chain in order: targetModel first, followed by fallbacks.
     */
    fun resolutionChain(): List<String> = buildList {
        add(targetModel)
        addAll(fallbackModels.filter { it != targetModel })
    }

    companion object {
        private val MODEL_ID_REGEX = Regex("^[a-zA-Z0-9][a-zA-Z0-9._/:-]{0,127}$")

        fun validModelId(id: String): Boolean {
            val trimmed = id.trim()
            return trimmed.isNotEmpty() &&
                trimmed.codePointCount(0, trimmed.length) <= 128 &&
                trimmed.none { it.isISOControl() } &&
                MODEL_ID_REGEX.matches(trimmed)
        }
    }
}

/**
 * In-memory routing table for local gateway dispatch and chat fallback handling.
 */
class ModelRoutingTable(
    rules: List<RoutingRule> = emptyList(),
) {
    private val ruleMap = rules.filter { it.enabled }.associateBy { it.alias }

    fun resolve(model: String): List<String> {
        val rule = ruleMap[model] ?: return listOf(model)
        return rule.resolutionChain()
    }

    fun hasRule(model: String): Boolean = ruleMap.containsKey(model)
}
