package cc.fplj.magpiemobile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.fplj.magpiemobile.core.ProviderListSnapshot
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/** Activity-owned safe metadata cache; Go remains the only provider configuration source.
 * No file writes, credentials, raw JSON, discovery, mutation, startup or inference operations.
 * The fetch callback must return the already-sanitized snapshot from getProviders().
 */
class ProviderListCacheViewModel(
    private val readScope: CoroutineScope? = null,
    private val readDispatcher: CoroutineDispatcher = Dispatchers.IO,
) : ViewModel() {
    private val mutex = Mutex()
    private var owner: Long? = null
    private var generation = 0L
    private var cached: ProviderListSnapshot? = null
    private var inflight: Deferred<ProviderListSnapshot>? = null

    suspend fun read(
        owner: Long,
        force: Boolean = false,
        fetch: () -> ProviderListSnapshot,
    ): ProviderListSnapshot {
        val task = mutex.withLock {
            if (this.owner != owner) {
                this.owner = owner
                generation++
                cached = null
                inflight = null
            }
            // Join refresh as well as automatic reads; caller cancellation never clears this slot.
            inflight?.let {
                if (!it.isCompleted) return@withLock it
                // Also handles a VM scope cancelled before a lazy Deferred ever entered its body.
                inflight = null
            }
            if (!force) cached?.let { if (!it.hasFileError) return it }
            // A failed explicit refresh must not leave an old healthy snapshot reusable.
            cached = null
            val readGeneration = generation
            lateinit var pending: Deferred<ProviderListSnapshot>
            pending = (readScope ?: viewModelScope).async(readDispatcher, start = CoroutineStart.LAZY) {
                var result: ProviderListSnapshot? = null
                try {
                    fetch().also { result = it }
                } finally {
                    val healthy = result?.takeIf { isActive && !it.hasFileError }
                    // Completion owns cleanup, even when every Composable await was cancelled.
                    withContext(NonCancellable) {
                        mutex.withLock {
                            if (this@ProviderListCacheViewModel.owner == owner &&
                                generation == readGeneration && inflight === pending) {
                                cached = healthy
                                inflight = null
                            }
                        }
                    }
                }
            }
            inflight = pending
            pending
        }
        // Start after registration and after releasing the selection lock.
        task.start()
        return task.await()
    }

    /** Await invalidation before a save/refresh read, so it cannot join an older generation. */
    suspend fun invalidate() = mutex.withLock {
        generation++
        cached = null
        inflight = null
    }
}
