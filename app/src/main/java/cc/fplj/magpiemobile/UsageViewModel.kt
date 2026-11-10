package cc.fplj.magpiemobile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.fplj.magpiemobile.core.UsageInsightEndpoint
import cc.fplj.magpiemobile.core.UsageRecord
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class UsageUiState(
    val loading: Boolean = false,
    val requests: List<UsageRecord> = emptyList(),
    val error: String = "",
    val insightsLoading: Boolean = false,
    /** 原始洞察 JSON；上游 schema 未钉死，UI 侧弹性解析展示。 */
    val heatmapJson: String = "",
    val quotasJson: String = "",
    val historyJson: String = "",
    val insightsError: String = "",
)

/**
 * Manages request audit trail and usage statistics for the Gateway Dashboard.
 * Bounded & safe: never requests or retains request/response payloads or tokens.
 */
class UsageViewModel(
    private val requestsReader: suspend (Int) -> List<UsageRecord> = { limit ->
        withContext(Dispatchers.IO) { NativeCoreSession.managementClient().recentRequests(limit) }
    },
    private val insightsReader: suspend (UsageInsightEndpoint) -> String = { endpoint ->
        withContext(Dispatchers.IO) { NativeCoreSession.managementClient().usageInsight(endpoint) }
    }
) : ViewModel() {

    private val mutable = MutableStateFlow(UsageUiState())
    val state = mutable.asStateFlow()

    fun refresh(limit: Int = 5) {
        val boundedLimit = limit.coerceIn(1, 50)
        mutable.update { it.copy(loading = true, error = "") }
        viewModelScope.launch {
            try {
                val list = requestsReader(boundedLimit)
                mutable.update { it.copy(loading = false, requests = list) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(loading = false, error = e.message ?: "读取近期请求失败") }
            }
        }
    }

    fun clear() {
        mutable.update { UsageUiState() }
    }

    /** 拉取用量洞察（热力图 + 额度配额 + 额度历史）；只读端点，失败不阻塞主状态。 */
    fun loadInsights() {
        if (mutable.value.insightsLoading) return
        mutable.update { it.copy(insightsLoading = true, insightsError = "") }
        viewModelScope.launch {
            try {
                val heatmap = withContext(Dispatchers.IO) { insightsReader(UsageInsightEndpoint.HEATMAP) }
                val quotas = withContext(Dispatchers.IO) { insightsReader(UsageInsightEndpoint.QUOTAS) }
                val history = withContext(Dispatchers.IO) { insightsReader(UsageInsightEndpoint.QUOTAS_HISTORY) }
                mutable.update { it.copy(insightsLoading = false, heatmapJson = heatmap, quotasJson = quotas, historyJson = history) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(insightsLoading = false, insightsError = e.message ?: "读取用量洞察失败") }
            }
        }
    }
}
