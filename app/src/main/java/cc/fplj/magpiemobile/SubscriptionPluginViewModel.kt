package cc.fplj.magpiemobile

import androidx.lifecycle.ViewModel
import cc.fplj.magpiemobile.core.BunRuntimeInspector
import cc.fplj.magpiemobile.core.BunRuntimeStatus
import cc.fplj.magpiemobile.core.EnginePaths
import cc.fplj.magpiemobile.core.SubscriptionPlugin
import cc.fplj.magpiemobile.core.SubscriptionPluginManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

data class PluginUiState(
    val bunStatus: BunRuntimeStatus = BunRuntimeStatus(installed = false, executable = false, architecture = "none", path = ""),
    val plugins: List<SubscriptionPlugin> = emptyList(),
    val notice: String = "",
    val error: String = "",
)

/**
 * Manages Bun runtime status and subscription plugin lifecycle (P2-4).
 */
class SubscriptionPluginViewModel : ViewModel() {

    private val mutable = MutableStateFlow(PluginUiState())
    val state = mutable.asStateFlow()
    private var pluginManager: SubscriptionPluginManager? = null

    fun initialize(layout: EnginePaths.Layout) {
        val bunStatus = BunRuntimeInspector.inspect(layout)
        val manager = SubscriptionPluginManager(File(layout.homeDir, "plugins"))
        pluginManager = manager
        mutable.update {
            it.copy(
                bunStatus = bunStatus,
                plugins = manager.list(),
                notice = "",
                error = ""
            )
        }
    }

    fun installPlugin(id: String, name: String, version: String, entrypoint: String, script: String) {
        val manager = pluginManager
        if (manager == null) {
            mutable.update { it.copy(error = "插件管理器未初始化", notice = "") }
            return
        }
        try {
            val plugin = SubscriptionPlugin(id.trim(), name.trim(), version.trim(), entrypoint.trim())
            manager.install(plugin, script)
            mutable.update {
                it.copy(
                    plugins = manager.list(),
                    notice = "已安装插件: " + plugin.name + " v" + plugin.version,
                    error = ""
                )
            }
        } catch (e: Exception) {
            mutable.update { it.copy(error = e.message ?: "安装插件失败", notice = "") }
        }
    }

    fun rollbackPlugin(id: String) {
        val manager = pluginManager ?: return
        if (manager.rollback(id)) {
            mutable.update {
                it.copy(
                    plugins = manager.list(),
                    notice = "已回滚插件至上一稳定版本: $id",
                    error = ""
                )
            }
        } else {
            mutable.update { it.copy(error = "未找到插件 " + id + " 的历史版本，无法回滚", notice = "") }
        }
    }

    fun togglePlugin(id: String) {
        val manager = pluginManager ?: return
        if (manager.toggle(id)) {
            mutable.update {
                it.copy(
                    plugins = manager.list(),
                    notice = "已切换插件状态: $id",
                    error = ""
                )
            }
        }
    }

    fun removePlugin(id: String) {
        val manager = pluginManager ?: return
        if (manager.remove(id)) {
            mutable.update {
                it.copy(
                    plugins = manager.list(),
                    notice = "已移除插件: $id",
                    error = ""
                )
            }
        }
    }

    fun clearNotice() {
        mutable.update { it.copy(notice = "", error = "") }
    }
}
