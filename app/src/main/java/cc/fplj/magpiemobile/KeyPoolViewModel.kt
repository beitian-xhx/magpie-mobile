package cc.fplj.magpiemobile

import androidx.lifecycle.ViewModel
import cc.fplj.magpiemobile.core.ProviderKeyPool
import cc.fplj.magpiemobile.core.ProviderPooledKey
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class KeyPoolEntry(
    val id: String,
    val maskedSecret: String,
    val enabled: Boolean = true,
)

data class KeyPoolUiState(
    val providerId: String = "",
    val keys: List<KeyPoolEntry> = emptyList(),
    val notice: String = "",
    val error: String = "",
)

/**
 * Manages provider key pooling and load balancing (P2-3).
 * Never exposes raw secret keys in UI state; masks everything except the suffix.
 */
class KeyPoolViewModel : ViewModel() {

    private val mutable = MutableStateFlow(KeyPoolUiState())
    val state = mutable.asStateFlow()

    private val pools = mutableMapOf<String, ProviderKeyPool>()

    fun selectProvider(providerId: String) {
        val pool = pools.getOrPut(providerId) { ProviderKeyPool(providerId) }
        mutable.update {
            it.copy(
                providerId = providerId,
                notice = "",
                error = ""
            )
        }
    }

    fun addKey(providerId: String, keyId: String, secret: String) {
        val trimmedSecret = secret.trim()
        val trimmedId = keyId.trim()
        if (trimmedId.isBlank() || trimmedSecret.isBlank()) {
            mutable.update { it.copy(error = "密钥标识和密钥内容不能为空", notice = "") }
            return
        }
        val pool = pools.getOrPut(providerId) { ProviderKeyPool(providerId) }
        val newKey = ProviderPooledKey(trimmedId, trimmedSecret, enabled = true)

        mutable.update { current ->
            val existing = current.keys.filter { it.id != trimmedId }
            val masked = "sk-••••" + trimmedSecret.takeLast(4)
            val updatedEntry = KeyPoolEntry(trimmedId, masked, enabled = true)
            current.copy(
                providerId = providerId,
                keys = existing + updatedEntry,
                notice = "已向账号池添加密钥: $trimmedId",
                error = ""
            )
        }
    }

    fun toggleKey(keyId: String) {
        mutable.update { current ->
            val updated = current.keys.map {
                if (it.id == keyId) it.copy(enabled = !it.enabled) else it
            }
            current.copy(keys = updated, notice = "已切换密钥状态: $keyId", error = "")
        }
    }

    fun removeKey(keyId: String) {
        mutable.update { current ->
            current.copy(
                keys = current.keys.filter { it.id != keyId },
                notice = "已从池中移除密钥: $keyId",
                error = ""
            )
        }
    }

    fun clearNotice() {
        mutable.update { it.copy(notice = "", error = "") }
    }
}
