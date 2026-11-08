package cc.fplj.magpiemobile

import cc.fplj.magpiemobile.core.ProviderListSnapshot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

@OptIn(ExperimentalCoroutinesApi::class)
class ProviderListCacheViewModelTest {
    private val fetchCount = AtomicInteger(0)
    private var returnFileError = false

    private fun fetchSnapshot(): ProviderListSnapshot {
        fetchCount.incrementAndGet()
        return ProviderListSnapshot(emptyList(), returnFileError)
    }

    @Test
    fun cacheReusedForSameOwnerWhenHealthy() = runTest {
        val vm = ProviderListCacheViewModel(readScope = this, readDispatcher = Dispatchers.Unconfined)
        val first = vm.read(1L, force = false) { fetchSnapshot() }
        assertEquals(1, fetchCount.get())
        assertFalse(first.hasFileError)

        val second = vm.read(1L, force = false) { fetchSnapshot() }
        assertEquals(1, fetchCount.get()) // No new fetch
        assertSame(first, second)
    }

    @Test
    fun concurrentReadsJoinSingleInflight() = runTest {
        val scheduler = TestCoroutineScheduler()
        val scope = TestScope(StandardTestDispatcher(scheduler))
        val vm = ProviderListCacheViewModel(readScope = scope, readDispatcher = StandardTestDispatcher(scheduler))

        val blockFetch = CompletableDeferred<Unit>()
        val a = scope.async {
            vm.read(1L, force = false) {
                blockFetch.await()
                fetchSnapshot()
            }
        }
        val b = scope.async {
            vm.read(1L, force = false) {
                fetchSnapshot()
            }
        }

        scheduler.advanceUntilIdle()
        blockFetch.complete(Unit)
        scheduler.advanceUntilIdle()

        val resA = a.await()
        val resB = b.await()
        assertEquals(1, fetchCount.get())
        assertSame(resA, resB)
    }

    @Test
    fun forceRefreshBypassesCacheAndInvalidatesOnError() = runTest {
        val vm = ProviderListCacheViewModel(readScope = this, readDispatcher = Dispatchers.Unconfined)
        val first = vm.read(1L, force = false) { fetchSnapshot() }
        assertEquals(1, fetchCount.get())

        val refreshed = vm.read(1L, force = true) { fetchSnapshot() }
        assertEquals(2, fetchCount.get())

        // Now simulate a failing force refresh
        try {
            vm.read(1L, force = true) {
                fetchCount.incrementAndGet()
                throw RuntimeException("Network down")
            }
            fail("Expected exception")
        } catch (_: Exception) {}

        assertEquals(3, fetchCount.get())

        // Cache must not contain the old healthy one anymore
        val third = vm.read(1L, force = false) { fetchSnapshot() }
        assertEquals(4, fetchCount.get())
    }

    @Test
    fun fileErrorSnapshotIsNotCachedAsHealthy() = runTest {
        val vm = ProviderListCacheViewModel(readScope = this, readDispatcher = Dispatchers.Unconfined)
        returnFileError = true

        val errorSnap = vm.read(1L, force = false) { fetchSnapshot() }
        assertEquals(1, fetchCount.get())
        assertTrue(errorSnap.hasFileError)

        returnFileError = false
        // Next read with force=false must not reuse the error snapshot
        val healthySnap = vm.read(1L, force = false) { fetchSnapshot() }
        assertEquals(2, fetchCount.get())
        assertFalse(healthySnap.hasFileError)
    }

    @Test
    fun ownerChangeInvalidatesOldCache() = runTest {
        val vm = ProviderListCacheViewModel(readScope = this, readDispatcher = Dispatchers.Unconfined)
        val snap1 = vm.read(1L, force = false) { fetchSnapshot() }
        assertEquals(1, fetchCount.get())

        val snap2 = vm.read(2L, force = false) { fetchSnapshot() }
        assertEquals(2, fetchCount.get())
    }

    @Test
    fun explicitInvalidateClearsCache() = runTest {
        val vm = ProviderListCacheViewModel(readScope = this, readDispatcher = Dispatchers.Unconfined)
        vm.read(1L, force = false) { fetchSnapshot() }
        assertEquals(1, fetchCount.get())

        vm.invalidate()

        vm.read(1L, force = false) { fetchSnapshot() }
        assertEquals(2, fetchCount.get())
    }

    @Test
    fun callerCancellationDoesNotAbortInflightOrCorruptCache() = runTest {
        val scope = CoroutineScope(Dispatchers.Default + Job())
        val vm = ProviderListCacheViewModel(readScope = scope, readDispatcher = Dispatchers.Default)

        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()

        val callerJob = scope.launch {
            vm.read(1L, force = false) {
                entered.complete(Unit)
                release.await()
                fetchSnapshot()
            }
        }

        entered.await()
        callerJob.cancelAndJoin() // Cancel the caller

        // Release the background fetch
        release.complete(Unit)

        // Read again with same owner - it should join or get the completed cached snapshot
        var res: ProviderListSnapshot? = null
        for (i in 0 until 50) {
            delay(20)
            try {
                res = vm.read(1L, force = false) { fetchSnapshot() }
                if (res != null) break
            } catch (_: Exception) {}
        }
        assertNotNull(res)
        assertEquals(1, fetchCount.get())

        scope.cancel()
    }
}
