package cc.fplj.magpiemobile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.fplj.magpiemobile.core.RoutingRule
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class RoutingUiState(
    val rules: List<RoutingRule> = emptyList(),
    val notice: String = "",
    val error: String = "",
)

/**
 * Manages model alias routing and fallback chain rules (P2-2).
 * UI and local memory only; strictly validates model and alias formats.
 */
class RoutingViewModel : ViewModel() {

    private val mutable = MutableStateFlow(RoutingUiState())
    val state = mutable.asStateFlow()

    fun addRule(alias: String, targetModel: String, fallbackModels: List<String> = emptyList()) {
        try {
            val rule = RoutingRule(
                alias = alias.trim(),
                targetModel = targetModel.trim(),
                fallbackModels = fallbackModels.map { it.trim() }.filter { it.isNotEmpty() }
            )
            mutable.update { current ->
                val filtered = current.rules.filter { it.alias != rule.alias }
                current.copy(
                    rules = filtered + rule,
                    notice = "已保存别名路由规则: ${rule.alias} -> ${rule.targetModel}",
                    error = ""
                )
            }
        } catch (e: Exception) {
            mutable.update { it.copy(error = e.message ?: "创建路由规则失败", notice = "") }
        }
    }

    fun removeRule(alias: String) {
        mutable.update { current ->
            current.copy(
                rules = current.rules.filter { it.alias != alias },
                notice = "已移除别名规则: $alias",
                error = ""
            )
        }
    }

    fun toggleRule(alias: String) {
        mutable.update { current ->
            val updated = current.rules.map {
                if (it.alias == alias) it.copy(enabled = !it.enabled) else it
            }
            current.copy(rules = updated, notice = "已更新路由状态: $alias", error = "")
        }
    }

    fun clearNotice() {
        mutable.update { it.copy(notice = "", error = "") }
    }
}
