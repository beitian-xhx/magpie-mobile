package cc.fplj.magpiemobile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.fplj.magpiemobile.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.time.Duration

data class UsageSection<T>(val data: T? = null, val loading: Boolean = false, val error: String = "", val updatedAt: Long = 0)
data class UsageDashboardState(
    val query: UsageQuery = UsageQuery(), val offset: Int = 0,
    val overview: UsageSection<UsageOverview> = UsageSection(),
    val ledger: UsageSection<UsageLedger> = UsageSection(),
    val quotas: UsageSection<List<QuotaCardData>> = UsageSection(),
    val heatmap: UsageSection<List<HeatDayData>> = UsageSection(),
    val history: UsageSection<List<QuotaHistoryData>> = UsageSection(),
)

/** Each independent data source publishes as soon as ready; generation fences stale IO. */
class UsageDashboardViewModel(
    private val overviewReader: suspend (UsageQuery) -> UsageOverview = { NativeCoreSession.managementClient().usageOverview(it) },
    private val ledgerReader: suspend (UsageQuery, Int) -> UsageLedger = { q, n -> NativeCoreSession.managementClient().usageLedger(q, n) },
    private val quotasReader: suspend (Boolean) -> List<QuotaCardData> = { NativeCoreSession.managementClient(Duration.ofSeconds(20)).usageQuotas(asked = true, all = it) },
    private val heatmapReader: suspend (UsageQuery) -> List<HeatDayData> = { NativeCoreSession.managementClient().usageHeatmap(it) },
    private val historyReader: suspend () -> List<QuotaHistoryData> = { NativeCoreSession.managementClient(Duration.ofSeconds(15)).usageQuotaHistory() },
    private val io: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutable = MutableStateFlow(UsageDashboardState())
    val state = mutable.asStateFlow()
    private var generation = 0L
    private var sessionGeneration = 0L
    private val jobs = mutableMapOf<String, Job>()

    fun select(query: UsageQuery) {
        if (query == mutable.value.query) return
        invalidate(all = false)
        // Vendor reads belong to the session, not the selected ledger query.
        val was = mutable.value
        mutable.value = UsageDashboardState(query = query, quotas = was.quotas, history = was.history)
        refreshLocal()
        refreshHeatmap()
    }

    fun page(offset: Int) {
        if (offset < 0 || mutable.value.ledger.loading) return
        jobs.remove("ledger")?.cancel()
        mutable.update { it.copy(offset = offset, ledger = UsageSection()) }
        refreshLedger()
    }

    fun refresh(asked: Boolean = false) {
        refreshLocal()
        refreshHeatmap()
        refreshQuotas(asked)
        refreshHistory()
    }

    fun refreshLocal() {
        val q = mutable.value.query
        load("overview", { it.overview }, { s, v -> s.copy(overview = v) }) { overviewReader(q) }
        refreshLedger()
    }

    private fun refreshLedger() {
        val (q, offset) = mutable.value.let { it.query to it.offset }
        load("ledger", { it.ledger }, { s, v -> s.copy(ledger = v, offset = v.data?.offset ?: s.offset) }) { ledgerReader(q, offset) }
    }

    private fun refreshHeatmap() {
        val q = mutable.value.query
        load("heatmap", { it.heatmap }, { s, v -> s.copy(heatmap = v) }) { heatmapReader(q) }
    }

    fun refreshQuotas(asked: Boolean = true) {
        load("quotas", { it.quotas }, { s, v -> s.copy(quotas = v) }) { quotasReader(asked) }
    }

    private fun refreshHistory() {
        load("history", { it.history }, { s, v -> s.copy(history = v) }) { historyReader() }
    }

    fun clear() {
        invalidate()
        mutable.value = UsageDashboardState(query = mutable.value.query)
    }

    private fun invalidate(all: Boolean = true) {
        generation++
        if (all) sessionGeneration++
        val names = if (all) jobs.keys.toList() else listOf("overview", "ledger", "heatmap")
        names.forEach { jobs.remove(it)?.cancel() }
    }

    private fun <T> load(
        name: String, get: (UsageDashboardState) -> UsageSection<T>,
        set: (UsageDashboardState, UsageSection<T>) -> UsageDashboardState,
        read: suspend () -> T,
    ) {
        if (get(mutable.value).loading) return
        val shared = name == "quotas" || name == "history"
        val own = if (shared) sessionGeneration else generation
        fun isCurrent() = own == if (shared) sessionGeneration else generation
        mutable.update { set(it, get(it).copy(loading = true, error = "")) }
        jobs[name] = viewModelScope.launch {
            try {
                val result = withContext(io) { read() }
                if (isCurrent()) mutable.update { set(it, UsageSection(result, updatedAt = System.currentTimeMillis())) }
            } catch (e: CancellationException) { throw e
            } catch (e: Exception) {
                if (isCurrent()) mutable.update { set(it, get(it).copy(loading = false, error = SecretRedactor.redact(e.message ?: e.javaClass.simpleName))) }
            }
        }
    }
}
