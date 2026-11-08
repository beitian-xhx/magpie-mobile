package cc.fplj.magpiemobile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
)

/**
 * Manages request audit trail and usage statistics for the Gateway Dashboard.
 * Bounded & safe: never requests or retains request/response payloads or tokens.
 */
class UsageViewModel(
    private val requestsReader: suspend (Int) -> List<UsageRecord> = { limit ->
        withContext(Dispatchers.IO) { NativeCoreSession.managementClient().recentRequests(limit) }
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
}
