package cc.fplj.magpiemobile

import android.app.Application
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.mutablePreferencesOf
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import cc.fplj.magpiemobile.core.ModelEntry
import cc.fplj.magpiemobile.preferences.*
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class AppShellViewModelTest {
    private lateinit var app: Application
    private val scopes = mutableListOf<CoroutineScope>()
    private val stores = mutableListOf<ViewModelStore>()
    private val entries = listOf(ModelEntry("p/model-a", "A", "p", null), ModelEntry("p/model-b", "B", "p", null))

    @Before fun setup() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        app = ApplicationProvider.getApplicationContext()
    }

    @After fun teardown() {
        stores.forEach { it.clear() }
        scopes.forEach { it.cancel() }
        Dispatchers.resetMain()
    }

    private fun repo(store: DataStore<Preferences>): AppUiPreferences {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO).also(scopes::add)
        return AppUiPreferences(store, scope)
    }

    private fun vm(repo: AppUiPreferences, connection: () -> CatalogConnection? = { null }): AppShellViewModel {
        val store = ViewModelStore().also(stores::add)
        val factory = object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T {
                @Suppress("UNCHECKED_CAST")
                return AppShellViewModel(app, repo, connection) as T
            }
        }
        return ViewModelProvider(store, factory)[AppShellViewModel::class.java]
    }

    private fun await(vm: AppShellViewModel, predicate: (AppShellState) -> Boolean): AppShellState = runBlocking {
        withTimeout(10000) { vm.state.first(predicate) }
    }

    private fun stored(page: AppPage = AppPage.CHAT, theme: ThemeMode = ThemeMode.DARK, model: String = "p/model-b") =
        mutablePreferencesOf(UiPreferencesCodec.schema to 1, UiPreferencesCodec.page to page.name,
            UiPreferencesCodec.theme to theme.name, UiPreferencesCodec.modelId to model)

    @Test fun restoresPreferencesButDoesNotReadCatalogOrStartGatewayOnItsOwn() {
        val calls = AtomicInteger()
        val repo = repo(FakeStore(stored().also {
            it[UiPreferencesCodec.gatewayIntent] = GatewayIntent.START.name
            it[UiPreferencesCodec.intentRevision] = 7L
        }))
        val vm = vm(repo) { calls.incrementAndGet(); CatalogConnection(1, { entries }) { true } }
        val state = await(vm) { it.preferences.ready }
        assertEquals(AppPage.CHAT, state.page)
        assertEquals(ThemeMode.DARK, state.theme)
        assertEquals(GatewayIntent.START, state.preferences.value.gatewayIntent)
        assertEquals(0, calls.get())
        assertFalse(state.catalogValid)
    }

    @Test fun savedModelIsRestoredOnlyAfterFreshCatalogAndTabsDoNotReadAgain() {
        val reads = AtomicInteger()
        val vm = vm(repo(FakeStore(stored()))) { CatalogConnection(1, { reads.incrementAndGet(); entries }) { true } }
        await(vm) { it.preferences.ready }
        vm.connectionChanged()
        await(vm) { it.catalogValid && !it.loadingModels && it.selectedModel == "p/model-b" }
        vm.selectPage(AppPage.MORE); vm.selectPage(AppPage.GATEWAY); vm.selectPage(AppPage.CHAT)
        vm.connectionChanged()
        assertEquals(1, reads.get())
        assertEquals("p/model-b", vm.state.value.selectedModel)
    }

    @Test fun missingSavedModelRequiresExplicitChoiceRatherThanSilentFallback() {
        val vm = vm(repo(FakeStore(stored(model = "p/missing")))) { CatalogConnection(1, { entries }) { true } }
        await(vm) { it.preferences.ready }; vm.connectionChanged()
        val state = await(vm) { it.catalogValid && !it.loadingModels }
        assertEquals("", state.selectedModel)
        assertTrue(state.modelsNotice.contains("没有自动换模型"))
        vm.selectModel("p/model-a")
        await(vm) { it.preferences.value.modelId == "p/model-a" }
        assertEquals("p/model-a", vm.state.value.selectedModel)
    }

    @Test fun latePreferenceReadCannotOverwriteNewPageThemeOrModelChoice() {
        val store = FakeStore(stored(), blocked = true)
        val vm = vm(repo(store)) { CatalogConnection(1, { entries }) { true } }
        vm.selectPage(AppPage.MORE)
        vm.selectTheme(ThemeMode.LIGHT)
        vm.connectionChanged(); await(vm) { it.catalogValid && !it.loadingModels }
        vm.selectModel("p/model-a")
        store.unblock()
        val state = await(vm) { it.preferences.ready && it.preferences.value.modelId == "p/model-a" }
        assertEquals(AppPage.MORE, state.page)
        assertEquals(ThemeMode.LIGHT, state.theme)
        assertEquals("p/model-a", state.selectedModel)
    }

    @Test fun validSelectionSurvivesRefreshAndOwnerChanges() {
        var owner = 1L
        val vm = vm(repo(FakeStore(stored()))) { val captured = owner; CatalogConnection(captured, { entries }) { owner == captured } }
        await(vm) { it.preferences.ready }; vm.connectionChanged()
        await(vm) { it.catalogValid && !it.loadingModels }
        vm.selectModel("p/model-a"); vm.refreshModels()
        await(vm) { it.catalogValid && !it.loadingModels }
        assertEquals("p/model-a", vm.state.value.selectedModel)
        owner = 2; vm.connectionChanged()
        await(vm) { it.catalogValid && !it.loadingModels }
        assertEquals("p/model-a", vm.state.value.selectedModel)
    }

    @Test fun removedSelectedModelIsClearedAndNotPersistentlyReplaced() {
        var catalog = entries
        val vm = vm(repo(FakeStore(stored()))) { CatalogConnection(1, { catalog }) { true } }
        await(vm) { it.preferences.ready }; vm.connectionChanged()
        await(vm) { it.catalogValid && !it.loadingModels }
        vm.selectModel("p/model-b")
        await(vm) { it.preferences.value.modelId == "p/model-b" }
        catalog = entries.take(1); vm.refreshModels()
        val state = await(vm) { it.catalogValid && !it.loadingModels }
        assertEquals("", state.selectedModel)
        assertEquals("p/model-b", state.preferences.value.modelId)
        assertTrue(state.modelsNotice.contains("没有自动换模型"))
    }

    @Test fun ownerChangedDuringReadCannotApplyStaleCatalog() {
        var owner = 1L
        val vm = vm(repo(FakeStore(stored()))) {
            val captured = owner
            CatalogConnection(captured, { if (captured == 1L) owner = 2L; entries }) { owner == captured }
        }
        await(vm) { it.preferences.ready }; vm.connectionChanged()
        val stale = await(vm) { !it.loadingModels }
        assertFalse(stale.catalogValid)
        assertTrue(stale.models.isEmpty())
        vm.connectionChanged()
        await(vm) { it.catalogValid && !it.loadingModels }
    }

    @Test fun failedPreferenceReadIsNotHydrationAndRetryCanRestoreHealthyData() {
        val store = FakeStore(mutablePreferencesOf(intPreferencesKey("schema") to 99))
        val repo = repo(store)
        val vm = vm(repo)
        val failure = await(vm) { it.preferences.warning != null }
        assertFalse(failure.preferences.ready)
        assertEquals(AppPage.GATEWAY, failure.page)
        store.replace(stored())
        vm.retryPreferences()
        val state = await(vm) { it.preferences.ready && it.page == AppPage.CHAT }
        assertEquals(ThemeMode.DARK, state.theme)
    }

    @Test fun retainedViewModelPreservesLocalPageAndDoesNotRepeatCatalogRead() {
        val reads = AtomicInteger()
        val vm = vm(repo(FakeStore(emptyPreferences()))) { CatalogConnection(1, { reads.incrementAndGet(); entries }) { true } }
        await(vm) { it.preferences.ready }; vm.connectionChanged()
        await(vm) { it.catalogValid && !it.loadingModels }; vm.selectPage(AppPage.MORE)
        val retained = ViewModelProvider(stores.last(), object : ViewModelProvider.Factory {
            override fun <T : ViewModel> create(modelClass: Class<T>): T = error("must retain")
        })[AppShellViewModel::class.java]
        assertSame(vm, retained)
        retained.connectionChanged()
        assertEquals(AppPage.MORE, retained.state.value.page)
        assertEquals(1, reads.get())
    }

    /** A controlled in-memory DataStore; real file corruption/atomicity tests are in preferences/. */
    private class FakeStore(initial: Preferences, blocked: Boolean = false) : DataStore<Preferences> {
        private val value = MutableStateFlow(initial)
        private val gate = CompletableDeferred<Unit>().also { if (!blocked) it.complete(Unit) }
        private val lock = Mutex()
        override val data: Flow<Preferences> = flow { gate.await(); emitAll(value) }
        override suspend fun updateData(transform: suspend (t: Preferences) -> Preferences): Preferences = lock.withLock {
            gate.await(); transform(value.value).also { value.value = it }
        }
        fun unblock() { gate.complete(Unit) }
        fun replace(raw: Preferences) { value.value = raw }
    }
}
