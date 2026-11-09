package cc.fplj.magpiemobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import cc.fplj.magpiemobile.core.CuratedPluginCatalog
import cc.fplj.magpiemobile.core.EnginePaths
import cc.fplj.magpiemobile.core.PluginCatalogEntry
import cc.fplj.magpiemobile.core.SubscriptionPlugin
import cc.fplj.magpiemobile.core.SubscriptionPluginManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import java.io.File

data class PluginMarketUiState(
    val entries: List<PluginCatalogEntry> = CuratedPluginCatalog.VERIFIED_PLUGINS,
    val installedIds: Set<String> = emptySet(),
    val notice: String = "",
    val error: String = "",
)

/**
 * Manages Plugin Discovery & Installation from Verified Catalog (P3-4).
 */
class PluginMarketViewModel(application: Application) : AndroidViewModel(application) {

    private val mutable = MutableStateFlow(PluginMarketUiState())
    val state = mutable.asStateFlow()

    private var pluginManager: SubscriptionPluginManager? = null

    fun initialize(layout: EnginePaths.Layout) {
        val pluginsDir = File(layout.homeDir, "plugins")
        val manager = SubscriptionPluginManager(pluginsDir)
        pluginManager = manager
        refreshInstalled()
    }

    fun refreshInstalled() {
        val manager = pluginManager ?: return
        val installed = manager.list().map { it.id }.toSet()
        mutable.update { it.copy(installedIds = installed) }
    }

    fun installFromMarket(entry: PluginCatalogEntry, mockScript: String? = null) {
        val manager = pluginManager
        if (manager == null) {
            mutable.update { it.copy(error = "插件管理器未初始化", notice = "") }
            return
        }

        try {
            val scriptContent = mockScript ?: ("// " + entry.name + "\nconsole.log('Running plugin: " + entry.id + "');")
            val plugin = SubscriptionPlugin(
                id = entry.id,
                name = entry.name,
                version = entry.version,
                entrypoint = "index.js",
                enabled = true
            )
            manager.install(plugin, scriptContent)
            refreshInstalled()
            mutable.update {
                it.copy(
                    notice = "已从受控市场安装插件: " + entry.name,
                    error = ""
                )
            }
        } catch (e: Exception) {
            mutable.update { it.copy(error = e.message ?: "安装失败", notice = "") }
        }
    }

    fun clearNotice() {
        mutable.update { it.copy(notice = "", error = "") }
    }
}
