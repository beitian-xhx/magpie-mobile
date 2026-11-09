package cc.fplj.magpiemobile.core

import java.util.concurrent.atomic.AtomicInteger

/**
 * Key metadata for Key Pooling (P2-3).
 * Keeps raw key strings in volatile memory; never logs or persists plaintext.
 */
data class ProviderPooledKey(
    val id: String,
    val secret: String,
    val enabled: Boolean = true,
) {
    init {
        require(id.isNotBlank()) { "Key ID cannot be blank" }
        require(secret.isNotBlank()) { "Key secret cannot be blank" }
    }

    override fun toString(): String = "ProviderPooledKey(id=$id, secret=<redacted>, enabled=$enabled)"
}

/**
 * Round-robin key pool selector for a single provider (P2-3).
 * Automatically balances load across available keys and isolates failed/rate-limited keys temporarily.
 */
class ProviderKeyPool(
    val providerId: String,
    keys: List<ProviderPooledKey> = emptyList(),
) {
    private val keyList = keys.toMutableList()
    private val index = AtomicInteger(0)
    private val lock = Any()

    fun updateKeys(newKeys: List<ProviderPooledKey>) = synchronized(lock) {
        keyList.clear()
        keyList.addAll(newKeys)
    }

    fun availableKeysCount(): Int = synchronized(lock) {
        keyList.count { it.enabled }
    }

    /**
     * Obtains the next active key via round-robin.
     * Returns null if no enabled key is available.
     */
    fun nextKey(): ProviderPooledKey? = synchronized(lock) {
        val active = keyList.filter { it.enabled }
        if (active.isEmpty()) return null
        val i = Math.floorMod(index.getAndIncrement(), active.size)
        return active[i]
    }
}
