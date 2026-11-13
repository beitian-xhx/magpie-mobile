package cc.fplj.magpiemobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cc.fplj.magpiemobile.core.BunRuntimeInspector
import cc.fplj.magpiemobile.core.EnginePaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class MagpieProvidersUiState(
    val bunReady: Boolean = false,
    val ready: Boolean = false,
    val busy: Boolean = false,
    val notice: String = "",
    val error: String = "",
)

/**
 * Backs the WorkBuddy / Qoder account card.  The bundled plugin packages are
 * installed into the Go core's config directory; sign-in runs the core's own
 * `plugin login` command with the credential on stdin.
 */
class MagpieProvidersViewModel(application: Application) : AndroidViewModel(application) {
    private val mutable = MutableStateFlow(MagpieProvidersUiState())
    val state = mutable.asStateFlow()
    private var layout: EnginePaths.Layout? = null

    fun initialize(layout: EnginePaths.Layout) {
        this.layout = layout
        viewModelScope.launch(Dispatchers.IO) {
            val bun = BunRuntimeInspector.inspect(layout)
            val result = runCatching { MagpiePlugins.ensureInstalled(getApplication<Application>(), layout) }
            mutable.update {
                it.copy(
                    bunReady = bun.executable,
                    ready = result.isSuccess,
                    notice = result.fold(
                        { r -> if (r.copied > 0) "已预置 ${r.copied} 个内置插件包" else "内置插件包已就绪" },
                        { "" },
                    ),
                    error = result.exceptionOrNull()?.message ?: "",
                )
            }
        }
    }

    fun login(provider: String, method: String, secret: String) {
        val layout = layout ?: return
        if (secret.isBlank()) {
            mutable.update { it.copy(notice = "", error = "请输入 API Key 或 PAT") }
            return
        }
        viewModelScope.launch(Dispatchers.IO) {
            mutable.update { it.copy(busy = true, notice = "", error = "") }
            val result = try {
                MagpiePlugins.login(getApplication<Application>(), layout, provider, method, secret)
            } catch (e: Exception) {
                MagpiePlugins.LoginResult(false, e.message ?: e.javaClass.simpleName)
            }
            mutable.update {
                it.copy(
                    busy = false,
                    notice = if (result.ok) "登录成功：$provider。请重启网关后刷新模型列表。" else "",
                    error = if (result.ok) "" else result.output.ifBlank { "登录失败，请检查 Key/PAT 与网络。" },
                )
            }
        }
    }
}
