package cc.fplj.magpiemobile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cc.fplj.magpiemobile.core.RegisteredGatewayKey
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class GatewayKeysUiState(
    val loading: Boolean = false,
    val keys: List<RegisteredGatewayKey> = emptyList(),
    val notice: String = "",
    val error: String = "",
    val newlyCreatedKey: RegisteredGatewayKey? = null,
)

/**
 * Manages client Caller Keys for external API access (OpenAI-compatible /v1 endpoints).
 * Safe credential handling: only explicitly created or copied keys obtain a plaintext secret.
 */
class GatewayKeysViewModel(
    private val keysReader: suspend () -> List<RegisteredGatewayKey> = {
        withContext(Dispatchers.IO) { NativeCoreSession.managementClient().listCallerKeys() }
    },
    private val keyCreator: suspend (String) -> RegisteredGatewayKey = { name ->
        withContext(Dispatchers.IO) { NativeCoreSession.managementClient().createCallerKey(name) }
    },
    private val keyCopier: suspend (String) -> RegisteredGatewayKey = { id ->
        withContext(Dispatchers.IO) { NativeCoreSession.managementClient().copyCallerKey(id) }
    },
) : ViewModel() {

    private val mutable = MutableStateFlow(GatewayKeysUiState())
    val state = mutable.asStateFlow()

    fun refresh() {
        mutable.update { it.copy(loading = true, error = "", notice = "") }
        viewModelScope.launch {
            try {
                val list = keysReader()
                mutable.update { it.copy(loading = false, keys = list) }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(loading = false, error = e.message ?: "读取密钥列表失败") }
            }
        }
    }

    fun createKey(name: String, onDone: (RegisteredGatewayKey) -> Unit = {}) {
        val trimmed = name.trim()
        if (trimmed.isBlank()) {
            mutable.update { it.copy(error = "密钥名称不能为空") }
            return
        }
        mutable.update { it.copy(loading = true, error = "", notice = "") }
        viewModelScope.launch {
            try {
                val created = keyCreator(trimmed)
                mutable.update {
                    it.copy(
                        loading = false,
                        newlyCreatedKey = created,
                        notice = "已成功创建客户端密钥：${created.name}"
                    )
                }
                refresh()
                onDone(created)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(loading = false, error = e.message ?: "创建密钥失败") }
            }
        }
    }

    fun copyKey(id: String, onDone: (String) -> Unit = {}) {
        mutable.update { it.copy(loading = true, error = "", notice = "") }
        viewModelScope.launch {
            try {
                val copied = keyCopier(id)
                mutable.update { it.copy(loading = false, notice = "已成功获取密钥：${copied.name}") }
                if (copied.secret.isNotBlank()) {
                    onDone(copied.secret)
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                mutable.update { it.copy(loading = false, error = e.message ?: "获取密钥失败") }
            }
        }
    }

    fun dismissNewKeyDialog() {
        mutable.update { it.copy(newlyCreatedKey = null) }
    }
}
