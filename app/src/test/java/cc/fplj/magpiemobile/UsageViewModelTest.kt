package cc.fplj.magpiemobile

import cc.fplj.magpiemobile.core.UsageRecord
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class UsageViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private val sample1 = UsageRecord(
        id = "req-1",
        timestamp = "2026-11-08T15:00:00Z",
        model = "gpt-4o",
        provider = "openai",
        statusCode = 200,
        durationMs = 450,
        totalTokens = 120
    )

    private val sample2 = UsageRecord(
        id = "req-2",
        timestamp = "2026-11-08T15:05:00Z",
        model = "claude-3-5-sonnet",
        provider = "anthropic",
        statusCode = 200,
        durationMs = 600,
        totalTokens = 85
    )

    @Test
    fun refreshLoadsRequestsSuccessfully() = runTest(testDispatcher) {
        val vm = UsageViewModel(
            requestsReader = { listOf(sample1, sample2) }
        )

        vm.refresh(5)
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.loading)
        assertEquals(2, state.requests.size)
        assertEquals("req-1", state.requests[0].id)
        assertEquals("gpt-4o", state.requests[0].model)
        assertEquals(200, state.requests[0].statusCode)
        assertEquals("", state.error)
    }

    @Test
    fun refreshHandlesErrorGracefully() = runTest(testDispatcher) {
        val vm = UsageViewModel(
            requestsReader = { error("管理接口超时") }
        )

        vm.refresh(5)
        advanceUntilIdle()

        val state = vm.state.value
        assertFalse(state.loading)
        assertTrue(state.requests.isEmpty())
        assertTrue(state.error.contains("管理接口超时"))
    }

    @Test
    fun limitIsCoercedWithinBounds() = runTest(testDispatcher) {
        val seenLimit = AtomicInteger(0)
        val vm = UsageViewModel(
            requestsReader = { limit ->
                seenLimit.set(limit)
                emptyList()
            }
        )

        vm.refresh(0)
        advanceUntilIdle()
        assertEquals(1, seenLimit.get())

        vm.refresh(100)
        advanceUntilIdle()
        assertEquals(50, seenLimit.get())
    }

    @Test
    fun clearResetsState() = runTest(testDispatcher) {
        val vm = UsageViewModel(
            requestsReader = { listOf(sample1) }
        )

        vm.refresh(5)
        advanceUntilIdle()
        assertEquals(1, vm.state.value.requests.size)

        vm.clear()
        val cleared = vm.state.value
        assertFalse(cleared.loading)
        assertTrue(cleared.requests.isEmpty())
        assertEquals("", cleared.error)
    }

    @Test
    fun loadInsightsStoresRawJsonAndRecoversFromError() = runTest(UnconfinedTestDispatcher()) {
        val calls = AtomicInteger()
        val vm = UsageViewModel(
            requestsReader = { emptyList() },
            insightsReader = { endpoint ->
                calls.incrementAndGet()
                if (endpoint == cc.fplj.magpiemobile.core.UsageInsightEndpoint.HEATMAP) """{"days":[1,2,3]}"""
                else """{"openai":{"remaining":100}}"""
            },
        )
        vm.loadInsights()
        advanceUntilIdle()
        assertEquals(2, calls.get())
        assertEquals("""{"days":[1,2,3]}""", vm.state.value.heatmapJson)
        assertEquals("""{"openai":{"remaining":100}}""", vm.state.value.quotasJson)
        assertEquals("", vm.state.value.insightsError)

        val failing = UsageViewModel(
            requestsReader = { emptyList() },
            insightsReader = { throw IllegalStateException("HTTP 503") },
        )
        failing.loadInsights()
        advanceUntilIdle()
        assertFalse(failing.state.value.insightsLoading)
        assertTrue(failing.state.value.insightsError.contains("503"))
        // 主请求状态不受洞察失败影响。
        assertTrue(failing.state.value.requests.isEmpty())
        assertEquals("", failing.state.value.error)
    }
}
