package cc.fplj.magpiemobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cc.fplj.magpiemobile.core.EnginePaths
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

data class MagpieProvidersUiState(
    val bunReady: Boolean = false,
    val bunDetail: String = "",
    val ready: Boolean = false,
    val busy: Boolean = false,
    val stage: String = "",
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
    private val loginGate = AtomicBoolean(false)

    fun initialize(layout: EnginePaths.Layout) {
        this.layout = layout
        viewModelScope.launch(Dispatchers.IO) {
            val bun = MagpiePlugins.checkBun(layout)
            val result = runCatching { MagpiePlugins.ensureInstalled(getApplication<Application>(), layout) }
            val install = result.getOrNull()
            mutable.update {
                it.copy(
                    bunReady = bun.ok,
                    bunDetail = bun.detail,
                    ready = result.isSuccess,
                    notice = when {
                        install == null -> ""
                        install.warning.isNotBlank() -> install.warning
                        install.copied > 0 -> "已预置 ${install.copied} 个内置插件包"
                        else -> "内置插件包已就绪"
                    },
                    error = result.exceptionOrNull()?.message ?: "",
                )
            }
        }
    }

    fun showError(message: String) {
        mutable.update { it.copy(notice = "", error = message) }
    }

    fun login(provider: String, method: String, secret: String) {
        val layout = layout
        if (layout == null) {
            mutable.update { it.copy(notice = "", error = "账号模块尚未初始化，请等待界面加载完成后重试。") }
            return
        }
        if (secret.isBlank()) {
            mutable.update { it.copy(notice = "", error = "请输入 API Key 或 PAT") }
            return
        }
        if (!loginGate.compareAndSet(false, true)) return
        mutable.update { it.copy(busy = true, stage = "正在准备内置插件…", notice = "", error = "") }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = try {
                    MagpiePlugins.login(
                        context = getApplication<Application>(),
                        layout = layout,
                        provider = provider,
                        method = method,
                        secret = secret,
                        onStage = { text -> mutable.update { it.copy(stage = text) } },
                    )
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    MagpiePlugins.LoginResult(false, e.message ?: e.javaClass.simpleName)
                }
                mutable.update {
                    it.copy(
                        busy = false,
                        stage = "",
                        notice = if (result.ok) "登录成功：$provider。请重启网关后刷新模型列表。" else "",
                        error = if (result.ok) "" else result.output.ifBlank { "登录失败，请检查 Key/PAT 与网络。" },
                    )
                }
            } finally {
                loginGate.set(false)
            }
        }
    }
}
