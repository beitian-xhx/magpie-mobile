package cc.fplj.magpiemobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import cc.fplj.magpiemobile.core.McpServerConfig
import cc.fplj.magpiemobile.core.McpServerStore
import cc.fplj.magpiemobile.core.McpTransport
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

data class McpUiState(
    val servers: List<McpServerConfig> = emptyList(),
    val notice: String = "",
    val error: String = "",
) {
    val activeCount: Int
        get() = servers.count { it.enabled }
}

/**
 * ViewModel managing Model Context Protocol (MCP) server endpoints & tools (P3-3).
 */
class McpViewModel(application: Application) : AndroidViewModel(application) {

    private val store = McpServerStore(File(application.filesDir, "mcp_servers.json"))
    private val mutable = MutableStateFlow(McpUiState(servers = store.list()))
    val state = mutable.asStateFlow()

    fun addServer(
        id: String,
        name: String,
        transport: McpTransport,
        endpoint: String,
        description: String = "",
    ) {
        try {
            val server = McpServerConfig(
                id = id.trim(),
                name = name.trim(),
                transport = transport,
                endpoint = endpoint.trim(),
                enabled = true,
                description = description.trim(),
            )
            store.save(server)
            mutable.update {
                it.copy(
                    servers = store.list(),
                    notice = "已注册 MCP 服务: " + server.name,
                    error = ""
                )
            }
        } catch (e: Exception) {
            mutable.update { it.copy(error = e.message ?: "添加 MCP 服务失败", notice = "") }
        }
    }

    fun toggleServer(id: String) {
        if (store.toggleEnabled(id)) {
            mutable.update {
                it.copy(
                    servers = store.list(),
                    notice = "已切换服务状态: $id",
                    error = ""
                )
            }
        }
    }

    fun deleteServer(id: String) {
        if (store.delete(id)) {
            mutable.update {
                it.copy(
                    servers = store.list(),
                    notice = "已移除 MCP 服务: $id",
                    error = ""
                )
            }
        } else {
            mutable.update { it.copy(error = "未找到待移除的 MCP 服务: $id", notice = "") }
        }
    }

    fun clearNotice() {
        mutable.update { it.copy(notice = "", error = "") }
    }
}
