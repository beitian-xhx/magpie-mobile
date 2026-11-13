package cc.fplj.magpiemobile

import android.app.Application
import android.content.Intent
import android.net.Uri
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
    /** Which provider section owns the current result: "workbuddy" or "qoder". */
    val action: String = "",
    val stage: String = "",
    val notice: String = "",
    val error: String = "",
)

/**
 * Browser account sign-in for WorkBuddy / Qoder subscriptions. No API key or
 * PAT is needed: the CLI prints a sign-in URL, the app opens it in the system
 * browser, and the plugin polls the vendor until the account is authorized.
 *
 * Results are tied to [MagpieProvidersUiState.action] so they appear directly
 * underneath the button that was pressed.
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

    private fun openBrowser(url: String, onStage: (String) -> Unit) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(url))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            getApplication<Application>().startActivity(intent)
            onStage("已打开浏览器。请登录并授权，然后切回 Magpie 等待完成…")
        } catch (e: Exception) {
            onStage("无法自动打开浏览器：" + (e.message ?: e.javaClass.simpleName))
        }
    }

    private inline fun runLogin(action: String, crossinline block: (EnginePaths.Layout, (String) -> Unit) -> MagpiePlugins.LoginResult) {
        val layout = layout
        if (layout == null) {
            mutable.update { it.copy(action = action, notice = "", error = "账号模块尚未初始化，请等待界面加载完成后重试。") }
            return
        }
        if (!loginGate.compareAndSet(false, true)) return
        mutable.update { it.copy(busy = true, action = action, stage = "正在准备…", notice = "", error = "") }
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val result = try {
                    block(layout) { text -> mutable.update { it.copy(stage = text) } }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    MagpiePlugins.LoginResult(false, e.message ?: e.javaClass.simpleName)
                }
                mutable.update {
                    it.copy(
                        busy = false,
                        stage = "",
                        notice = if (result.ok) result.output else "",
                        error = if (result.ok) "" else result.output.ifBlank { "登录失败，请检查网络与账号状态。" },
                    )
                }
            } finally {
                loginGate.set(false)
            }
        }
    }

    fun loginBrowser(action: String, provider: String, method: String) {
        runLogin(action) { layout, onStage ->
            MagpiePlugins.login(
                context = getApplication<Application>(),
                layout = layout,
                provider = provider,
                method = method,
                onStage = onStage,
                onBrowserUrl = { url -> openBrowser(url, onStage) },
            )
        }
    }

    fun cancelLogin() {
        viewModelScope.launch(Dispatchers.IO) { MagpiePlugins.cancelLogin() }
    }
}
