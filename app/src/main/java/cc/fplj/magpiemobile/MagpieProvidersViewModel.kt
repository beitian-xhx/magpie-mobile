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
    /** Which provider section owns the current result: "workbuddy" or "qoder". */
    val action: String = "",
    val stage: String = "",
    val notice: String = "",
    val error: String = "",
)

/**
 * WorkBuddy is saved by writing Magpie's plugin-auth.json directly (no child
 * process, no browser, no network). Qoder PAT still needs the CLI because the
 * PAT must be exchanged for a job token online.
 *
 * Every result is tied to [MagpieProvidersUiState.action] so the UI can show
 * it directly underneath the button that was pressed, not at the top of a
 * long scrolling card.
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

    fun showError(action: String, message: String) {
        mutable.update { it.copy(action = action, notice = "", error = message) }
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
                        error = if (result.ok) "" else result.output.ifBlank { "登录失败，请检查 Key/PAT 与网络。" },
                    )
                }
            } finally {
                loginGate.set(false)
            }
        }
    }

    fun loginWorkbuddy(provider: String, key: String) {
        if (key.isBlank()) {
            showError("workbuddy", "请输入 WorkBuddy API Key。")
            return
        }
        runLogin("workbuddy") { layout, onStage ->
            MagpiePlugins.writeApiKey(getApplication<Application>(), layout, provider, key, onStage)
        }
    }

    fun loginQoder(provider: String, method: String, pat: String) {
        if (pat.isBlank()) {
            showError("qoder", "请输入 Qoder PAT。")
            return
        }
        runLogin("qoder") { layout, onStage ->
            MagpiePlugins.login(getApplication<Application>(), layout, provider, method, pat, onStage)
        }
    }
}
