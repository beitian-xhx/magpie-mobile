package cc.fplj.magpiemobile

import cc.fplj.magpiemobile.core.*
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.Assert.*

@OptIn(ExperimentalCoroutinesApi::class)
class UsageDashboardViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val overview = UsageOverview(UsageNumbers(calls = 1), emptyList(), emptyList(), emptyList(), emptyList(), emptyList())
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { Dispatchers.resetMain() }
    private fun vm(
        overviewRead: suspend (UsageQuery) -> UsageOverview = { overview },
        ledgerRead: suspend (UsageQuery, Int) -> UsageLedger = { _, _ -> UsageLedger(emptyList(), 0, UsageNumbers(), emptyList()) },
        quotasRead: suspend (Boolean) -> List<QuotaCardData> = { emptyList() },
    ) = UsageDashboardViewModel(overviewRead, ledgerRead, quotasRead, { emptyList() }, { emptyList() }, dispatcher)

    @Test fun slowAndFailedQuotaDoesNotBlockLocalUsage() = runTest(dispatcher) {
        val model = vm(quotasRead = { delay(12000); error("HTTP 503") })
        model.refresh(true)
        runCurrent()
        assertNotNull(model.state.value.overview.data)
        assertNotNull(model.state.value.ledger.data)
        assertTrue(model.state.value.quotas.loading)
        advanceTimeBy(12001); runCurrent()
        assertEquals("HTTP 503", model.state.value.quotas.error)
        assertEquals(1L, model.state.value.overview.data?.numbers?.calls)
    }
    @Test fun queryChangeAndDisconnectCannotBeOverwrittenByOldRead() = runTest(dispatcher) {
        val model = vm(overviewRead = { q ->
            // Simulate an uncooperative blocking API.
            withContext(NonCancellable) { delay(if (q.period == "today") 5000 else 10) }
            overview.copy(numbers = UsageNumbers(calls = if (q.period == "7d") 7 else 1))
        })
        model.refresh(); runCurrent()
        model.select(UsageQuery(period = "7d")); advanceUntilIdle()
        assertEquals(7L, model.state.value.overview.data?.numbers?.calls)
        model.refresh(); runCurrent(); model.clear(); advanceUntilIdle()
        assertNull(model.state.value.overview.data)
        assertFalse(model.state.value.overview.loading)
    }
    @Test fun pageAndFiltersReachActualQueryReader() = runTest(dispatcher) {
        val calls = mutableListOf<Pair<UsageQuery, Int>>()
        val model = vm(ledgerRead = { q, offset -> calls += q to offset; UsageLedger(emptyList(), 80, UsageNumbers(), emptyList()) })
        val q = UsageQuery(period = "30d", provider = "workbuddy", failed = true)
        model.select(q); advanceUntilIdle()
        model.page(20); advanceUntilIdle()
        assertEquals(q, calls.last().first)
        assertEquals(20, calls.last().second)
        model.select(q.copy(search = "flash")); advanceUntilIdle()
        assertEquals(0, calls.last().second)
    }
    @Test fun failureKeepsLastSuccessfulReadingAndRemainsVisible() = runTest(dispatcher) {
        var fail = false
        val model = vm(overviewRead = { if (fail) error("timeout") else overview })
        model.refreshLocal(); advanceUntilIdle()
        fail = true
        model.refreshLocal(); advanceUntilIdle()
        assertNotNull(model.state.value.overview.data)
        assertEquals("timeout", model.state.value.overview.error)
    }
}
