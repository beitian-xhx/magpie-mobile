package cc.fplj.magpiemobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cc.fplj.magpiemobile.core.ModelEntry
import cc.fplj.magpiemobile.core.ModelsClient
import cc.fplj.magpiemobile.preferences.AppPage
import cc.fplj.magpiemobile.preferences.AppUiPreferences
import cc.fplj.magpiemobile.preferences.GatewayIntent
import cc.fplj.magpiemobile.preferences.ThemeMode
import cc.fplj.magpiemobile.preferences.UiPreferencesState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** A private catalog connection: key-bearing clients never enter public state. */
internal class CatalogConnection(val owner: Long, val read: () -> List<ModelEntry>, val isCurrent: () -> Boolean)

data class AppShellState(
    val page: AppPage = AppPage.GATEWAY,
    val theme: ThemeMode = ThemeMode.SYSTEM,
    val preferences: UiPreferencesState = UiPreferencesState(),
    val saveNotice: String = "",
    val models: List<ModelEntry> = emptyList(),
    val selectedModel: String = "",
    val catalogValid: Boolean = false,
    val loadingModels: Boolean = false,
    val modelsNotice: String = "请先启动网关并完成安全连接。",
)

/** UI state and catalog reads survive tab switches/rotation. No method starts a Service or sends Chat. */
class AppShellViewModel internal constructor(
    application: Application,
    private val preferences: AppUiPreferences,
    private val catalogConnection: () -> CatalogConnection?,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, AppUiPreferences.open(application), {
        val owner = NativeCoreSession.providerSessionId()
        if (owner == null) null else try {
            val config = NativeCoreSession.gatewayConfig()
            CatalogConnection(owner, { ModelsClient(config).list() }) { owner == NativeCoreSession.providerSessionId() }
        } catch (_: Exception) { null }
    })

    private val mutable = MutableStateFlow(AppShellState())
    val state = mutable.asStateFlow()
    private var hydrated = false
    private var pageTouched = false
    private var themeTouched = false
    private var modelTouched = false
    private var restoredModel: String? = null
    private var modelRestorePending = true
    private var initialCatalog = true
    private var owner: Long? = null
    private var request = 0L

    init {
        viewModelScope.launch {
            preferences.state.collect { snapshot ->
                mutable.update { it.copy(preferences = snapshot) }
                if (snapshot.ready && !hydrated) {
                    hydrated = true
                    restoredModel = snapshot.value.modelId
                    mutable.update { it.copy(
                        page = if (pageTouched) it.page else snapshot.value.page,
                        theme = if (themeTouched) it.theme else snapshot.value.theme,
                    ) }
                    if (mutable.value.catalogValid) reconcileSelection()
                }
            }
        }
    }

    fun selectPage(page: AppPage) {
        pageTouched = true
        mutable.update { it.copy(page = page) }
        save { preferences.setPage(page) }
    }

    fun selectTheme(theme: ThemeMode) {
        themeTouched = true
        mutable.update { it.copy(theme = theme) }
        save { preferences.setTheme(theme) }
    }

    fun selectModel(id: String) {
        val snapshot = mutable.value
        if (!snapshot.catalogValid || snapshot.models.none { it.id == id }) return
        modelTouched = true; modelRestorePending = false; initialCatalog = false
        mutable.update { it.copy(selectedModel = id) }
        save { preferences.setModelId(id) }
    }

    private fun save(block: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                block()
                mutable.update { it.copy(saveNotice = "") }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(saveNotice = "本机偏好未能保存，当前临时选择仍可使用；原文件保留，未自动重写或重试。") } }
        }
    }

    fun retryPreferences() { preferences.retryRead() }

    /** Called for a live connection change only, never because a page became visible. */
    fun connectionChanged() {
        val connection = catalogConnection()
        if (connection == null) {
            owner = null; ++request
            mutable.update { it.copy(catalogValid = false, loadingModels = false, modelsNotice = "请先启动网关并完成安全连接。") }
        } else if (owner != connection.owner) {
            owner = connection.owner
            refreshModels()
        }
    }

    fun refreshModels() {
        val connection = catalogConnection() ?: run { connectionChanged(); return }
        owner = connection.owner
        val sequence = ++request
        mutable.update { it.copy(catalogValid = false, loadingModels = true) }
        viewModelScope.launch {
            try {
                val models = withContext(Dispatchers.IO) { check(connection.isCurrent()); connection.read() }
                if (sequence == request && owner == connection.owner && connection.isCurrent()) {
                    mutable.update { it.copy(models = models, catalogValid = true, modelsNotice =
                        if (models.isEmpty()) "没有已配置模型，请添加提供商。" else "可选模型 " + models.size) }
                    reconcileSelection()
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (sequence == request) mutable.update { it.copy(modelsNotice = "模型目录读取失败，缓存未确认；请手动刷新。") }
            } finally {
                if (sequence == request) mutable.update { it.copy(loadingModels = false) }
            }
        }
    }

    private fun reconcileSelection() {
        val snapshot = mutable.value
        val ids = snapshot.models.map { it.id }.toSet()
        if (snapshot.selectedModel.isNotBlank()) {
            if (snapshot.selectedModel !in ids) mutable.update { it.copy(selectedModel = "", modelsNotice = "之前选择的模型已不可用，请重新选择；没有自动换模型。") }
            // User actions win over a late DataStore first read.
            if (modelTouched) { modelRestorePending = false; initialCatalog = false }
            return
        }
        if (!modelTouched && modelRestorePending && restoredModel != null) {
            val saved = restoredModel.orEmpty()
            modelRestorePending = false
            initialCatalog = false
            val chosen = if (saved.isBlank()) snapshot.models.firstOrNull()?.id.orEmpty() else saved.takeIf { it in ids }.orEmpty()
            mutable.update { it.copy(selectedModel = chosen, modelsNotice = if (saved.isNotBlank() && chosen.isBlank())
                "上次保存的模型已不可用，请重新选择；没有自动换模型。" else it.modelsNotice) }
        } else if (!modelTouched && initialCatalog && hydrated) {
            initialCatalog = false
            mutable.update { it.copy(selectedModel = snapshot.models.firstOrNull()?.id.orEmpty()) }
        }
    }
}
