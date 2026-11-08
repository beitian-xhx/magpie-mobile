package cc.fplj.magpiemobile.preferences

import android.app.Application
import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketTimeoutException

@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class AppUiPreferencesTest {
    @get:Rule val temporary = TemporaryFolder()
    private val fixtures = mutableListOf<Fixture>()

    private class Fixture(
        val file: File,
        val job: Job,
        val scheduler: TestCoroutineScheduler,
        val store: DataStore<Preferences>,
        val ui: AppUiPreferences,
    ) {
        suspend fun close() = job.cancelAndJoin()
    }

    private suspend fun TestScope.fixture(
        file: File = File(temporary.newFolder(), "ui.preferences_pb"),
        seed: (MutablePreferences.() -> Unit)? = null,
        decorate: (DataStore<Preferences>) -> DataStore<Preferences> = { it },
    ): Fixture {
        val job = SupervisorJob()
        val owner = CoroutineScope(job + StandardTestDispatcher(testScheduler))
        val store = PreferenceDataStoreFactory.create(scope = owner, produceFile = { file })
        if (seed != null) store.edit { seed(it) }
        return Fixture(file, job, testScheduler, store, AppUiPreferences(decorate(store), owner))
            .also { fixtures.add(it) }
    }

    @After fun closeStores() {
        fixtures.forEach { it.job.cancel() }
        fixtures.map { it.scheduler }.distinct().forEach { it.advanceUntilIdle() }
    }

    private suspend fun ready(ui: AppUiPreferences): UiPreferencesState = ui.state.first { it.ready }
    private suspend fun warned(ui: AppUiPreferences): UiPreferencesState = ui.state.first { it.warning != null }.also {
        assertFalse(it.ready)
    }
    private suspend fun rejected(action: suspend () -> Unit): UiPreferencesWriteException {
        try { action() } catch (failure: UiPreferencesWriteException) { return failure }
        throw AssertionError("Expected fixed preference write failure")
    }

    private class GatedStore(private val delegate: DataStore<Preferences>) : DataStore<Preferences> {
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var updates = 0
        override val data: Flow<Preferences> get() = delegate.data
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            updates++
            if (updates == 1) { entered.complete(Unit); release.await() }
            return delegate.updateData(transform)
        }
    }

    private class FaultStore(private val delegate: DataStore<Preferences>) : DataStore<Preferences> {
        var failRead = false
        var failWrite = false
        var reads = 0
        var updates = 0
        override val data: Flow<Preferences> get() = flow {
            reads++
            if (failRead) throw IOException("private path upstream cookie=must-not-leak")
            emitAll(delegate.data)
        }
        override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
            updates++
            if (failWrite) throw IOException("private file upstream bearer must-not-leak")
            return delegate.updateData(transform)
        }
    }

    @Test fun missingFileReadsDefaultsWithoutImplicitWritingOrSchemaCreation() = runTest {
        val f = fixture()
        assertEquals(UiPreferencesState(), f.ui.state.value)
        assertEquals(UiPreferencesState(true), ready(f.ui))
        assertFalse(f.file.exists())
        assertNull(f.store.data.first()[UiPreferencesCodec.schema])
        repeat(3) { f.ui.retryRead() }
        advanceUntilIdle()
        assertFalse(f.file.exists())
        assertEquals(UiPreferences(), f.ui.state.value.value)
    }

    @Test fun closeAndReopenPersistsAllUiMetadataWithoutReplayingIntent() = runTest {
        val first = fixture()
        ready(first.ui)
        first.ui.setTheme(ThemeMode.DARK)
        first.ui.setPage(AppPage.CHAT)
        first.ui.setModelId("nvidia/meta/llama-3.1-70b-instruct")
        first.ui.recordGatewayIntent(GatewayIntent.START)
        first.ui.recordGatewayIntent(GatewayIntent.STOP)
        first.ui.setTheme(ThemeMode.DARK) // FIFO barrier.
        val expected = UiPreferences(ThemeMode.DARK, AppPage.CHAT,
            "nvidia/meta/llama-3.1-70b-instruct", GatewayIntent.STOP, 2)
        assertEquals(expected, first.ui.state.value.value)
        assertEquals(1, first.store.data.first()[UiPreferencesCodec.schema])
        val bytes = first.file.readBytes()
        first.close()
        val second = fixture(file = first.file)
        assertEquals(expected, ready(second.ui).value)
        assertArrayEquals(bytes, second.file.readBytes())
        second.ui.retryRead()
        advanceUntilIdle()
        assertEquals(2L, second.ui.state.value.value.intentRevision)
        assertArrayEquals(bytes, second.file.readBytes())
    }

    @Test fun dirtyKeyWritePreservesOtherOwnedValuesAndUnknownKeys() = runTest {
        val extra = stringPreferencesKey("future.label")
        val set = stringSetPreferencesKey("future.tags")
        val f = fixture(seed = {
            this[UiPreferencesCodec.schema] = 1
            this[UiPreferencesCodec.theme] = ThemeMode.LIGHT.name
            this[UiPreferencesCodec.page] = AppPage.PROVIDERS.name
            this[UiPreferencesCodec.modelId] = "provider/model"
            this[UiPreferencesCodec.gatewayIntent] = GatewayIntent.STOP.name
            this[UiPreferencesCodec.intentRevision] = 7L
            this[extra] = "keep"
            this[set] = setOf("a", "b")
        })
        ready(f.ui)
        val before = f.store.data.first().asMap()
        f.ui.setTheme(ThemeMode.DARK)
        val after = f.store.data.first().asMap()
        assertEquals(before.filterKeys { it.name != "theme" }, after.filterKeys { it.name != "theme" })
        assertEquals(ThemeMode.DARK.name, f.store.data.first()[UiPreferencesCodec.theme])
        assertEquals("keep", f.store.data.first()[extra])
        assertEquals(setOf("a", "b"), f.store.data.first()[set])
    }

    @Test fun firstExplicitWriteTouchesOnlyDirtyKeyAndSchemaNotOtherDefaults() = runTest {
        val extra = longPreferencesKey("future.counter")
        val f = fixture(seed = { this[extra] = 9L })
        ready(f.ui)
        f.ui.setPage(AppPage.MORE)
        val values = f.store.data.first().asMap().entries.associate { it.key.name to it.value }
        assertEquals(mapOf("future.counter" to 9L, "page" to "MORE", "schema" to 1), values)
    }

    @Test fun futureSchemaRefusesEveryWriteAndPreservesOriginalBytes() = runTest {
        val f = fixture(seed = {
            this[UiPreferencesCodec.schema] = 2
            this[stringPreferencesKey("future.field")] = "keep"
        })
        assertEquals(UiPreferenceWarnings.INVALID, warned(f.ui).warning)
        val original = f.file.readBytes()
        rejected { f.ui.setTheme(ThemeMode.DARK) }
        rejected { f.ui.setPage(AppPage.CHAT) }
        rejected { f.ui.setModelId("valid/model") }
        f.ui.recordGatewayIntent(GatewayIntent.START)
        f.ui.retryRead()
        advanceUntilIdle()
        assertArrayEquals(original, f.file.readBytes())
        assertTrue(f.ui.state.value.warning!!.contains(UiPreferenceWarnings.INVALID))
        assertTrue(f.ui.state.value.warning!!.contains(UiPreferenceWarnings.WRITE))
        assertEquals(0L, f.ui.state.value.value.intentRevision)
    }

    @Test fun invalidEnumsAreNotReplacedByDefaultsDuringAWritesTransaction() = runTest {
        val seeds: List<MutablePreferences.() -> Unit> = listOf(
            { this[UiPreferencesCodec.theme] = "AUTO" },
            { this[UiPreferencesCodec.page] = "UNKNOWN" },
            { this[UiPreferencesCodec.gatewayIntent] = "RESTART" },
        )
        for (seed in seeds) {
            val f = fixture(seed = { this[UiPreferencesCodec.schema] = 1; seed(this) })
            assertEquals(UiPreferenceWarnings.INVALID, warned(f.ui).warning)
            val bytes = f.file.readBytes()
            rejected { f.ui.setTheme(ThemeMode.DARK) }
            f.ui.recordGatewayIntent(GatewayIntent.STOP)
            advanceUntilIdle()
            assertArrayEquals(bytes, f.file.readBytes())
        }
    }

    @Test fun knownKeysWithWrongRawTypesAreRejectedNotSilentlyDefaulted() = runTest {
        val seeds: List<MutablePreferences.() -> Unit> = listOf(
            { this[longPreferencesKey("schema")] = 1L },
            { this[intPreferencesKey("theme")] = 1 },
            { this[booleanPreferencesKey("page")] = true },
            { this[intPreferencesKey("modelId")] = 7 },
            { this[intPreferencesKey("gatewayIntent")] = 1 },
            { this[intPreferencesKey("intentRevision")] = 0 },
            { this[stringPreferencesKey("intentRevision")] = "0" },
        )
        for (seed in seeds) {
            val f = fixture(seed = seed)
            assertEquals(UiPreferenceWarnings.INVALID, warned(f.ui).warning)
            val bytes = f.file.readBytes()
            rejected { f.ui.setPage(AppPage.MORE) }
            assertArrayEquals(bytes, f.file.readBytes())
        }
    }

    @Test fun negativeRevisionRejectsAllWritesAndCannotBeResetOrIncremented() = runTest {
        val f = fixture(seed = {
            this[UiPreferencesCodec.schema] = 1
            this[UiPreferencesCodec.intentRevision] = -1L
        })
        assertEquals(UiPreferenceWarnings.INVALID, warned(f.ui).warning)
        val bytes = f.file.readBytes()
        rejected { f.ui.setTheme(ThemeMode.LIGHT) }
        f.ui.recordGatewayIntent(GatewayIntent.START)
        advanceUntilIdle()
        assertArrayEquals(bytes, f.file.readBytes())
        assertEquals(-1L, f.store.data.first()[UiPreferencesCodec.intentRevision])
    }

    @Test fun revisionOverflowCannotWrapAndDoesNotPreventOtherDirtyKeys() = runTest {
        val f = fixture(seed = {
            this[UiPreferencesCodec.schema] = 1
            this[UiPreferencesCodec.gatewayIntent] = GatewayIntent.STOP.name
            this[UiPreferencesCodec.intentRevision] = Long.MAX_VALUE
        })
        ready(f.ui)
        val bytes = f.file.readBytes()
        f.ui.recordGatewayIntent(GatewayIntent.START)
        advanceUntilIdle()
        assertEquals(UiPreferenceWarnings.REVISION, f.ui.state.value.warning)
        assertArrayEquals(bytes, f.file.readBytes())
        f.ui.setTheme(ThemeMode.DARK)
        assertEquals(Long.MAX_VALUE, f.store.data.first()[UiPreferencesCodec.intentRevision])
        assertEquals(GatewayIntent.STOP, f.ui.state.value.value.gatewayIntent)
    }

    @Test fun corruptFileReadWriteAndRetryPreserveBytesWithoutReplacementHandler() = runTest {
        val file = File(temporary.newFolder(), "ui.preferences_pb")
        val original = byteArrayOf(0xff.toByte(), 0xff.toByte(), 0xff.toByte())
        file.writeBytes(original)
        val f = fixture(file = file)
        assertEquals(UiPreferenceWarnings.READ, warned(f.ui).warning)
        val failure = rejected { f.ui.setTheme(ThemeMode.DARK) }
        assertEquals(UiPreferenceWarnings.WRITE, failure.message)
        assertNull(failure.cause)
        f.ui.recordGatewayIntent(GatewayIntent.STOP)
        repeat(3) { f.ui.retryRead() }
        advanceUntilIdle()
        assertArrayEquals(original, file.readBytes())
        assertEquals(UiPreferences(), f.ui.state.value.value)
        assertTrue(f.ui.state.value.warning!!.contains(UiPreferenceWarnings.READ))
        assertTrue(f.ui.state.value.warning!!.contains(UiPreferenceWarnings.WRITE))
    }

    @Test fun fifoStartThenStopRemainsStopEvenWhenStartWriteIsDelayed() = runTest {
        lateinit var gate: GatedStore
        val f = fixture(decorate = { GatedStore(it).also { gate = it } })
        ready(f.ui)
        f.ui.recordGatewayIntent(GatewayIntent.START)
        f.ui.recordGatewayIntent(GatewayIntent.STOP)
        gate.entered.await()
        assertFalse(f.file.exists())
        assertEquals(0L, f.ui.state.value.value.intentRevision)
        gate.release.complete(Unit)
        f.ui.setPage(AppPage.CHAT)
        assertEquals(GatewayIntent.STOP, f.ui.state.value.value.gatewayIntent)
        assertEquals(2L, f.ui.state.value.value.intentRevision)
        assertEquals(3, gate.updates)
    }

    @Test fun recordIntentSurvivesCancellationOfUiLifecycleScope() = runTest {
        lateinit var gate: GatedStore
        val f = fixture(decorate = { GatedStore(it).also { gate = it } })
        ready(f.ui)
        val uiOwner = Job()
        val uiScope = CoroutineScope(uiOwner + StandardTestDispatcher(testScheduler))
        uiScope.launch { f.ui.recordGatewayIntent(GatewayIntent.START) }.join()
        gate.entered.await()
        uiOwner.cancelAndJoin()
        f.ui.recordGatewayIntent(GatewayIntent.STOP)
        gate.release.complete(Unit)
        f.ui.setPage(AppPage.MORE)
        assertEquals(GatewayIntent.STOP, f.ui.state.value.value.gatewayIntent)
        assertEquals(2L, f.ui.state.value.value.intentRevision)
    }

    @Test fun cancellingSuspendSetterAwaitDoesNotCancelItsAcceptedWrite() = runTest {
        lateinit var gate: GatedStore
        val f = fixture(decorate = { GatedStore(it).also { gate = it } })
        ready(f.ui)
        val caller = launch { f.ui.setTheme(ThemeMode.DARK) }
        gate.entered.await()
        caller.cancelAndJoin()
        gate.release.complete(Unit)
        f.ui.setPage(AppPage.MORE)
        assertEquals(ThemeMode.DARK, f.ui.state.value.value.theme)
        assertEquals(AppPage.MORE, f.ui.state.value.value.page)
    }

    @Test fun finiteIntentQueueRejectsOverflowWithoutReplayingOrOverwritingNewStop() = runTest {
        lateinit var gate: GatedStore
        val f = fixture(decorate = { GatedStore(it).also { gate = it } })
        ready(f.ui)
        f.ui.recordGatewayIntent(GatewayIntent.START)
        gate.entered.await()
        repeat(64) { f.ui.recordGatewayIntent(GatewayIntent.STOP) }
        f.ui.recordGatewayIntent(GatewayIntent.START) // Queue full: rejected, never replayed.
        assertEquals(UiPreferenceWarnings.QUEUE, f.ui.state.value.warning)
        gate.release.complete(Unit)
        advanceUntilIdle()
        assertEquals(UiPreferenceWarnings.QUEUE, f.ui.state.value.warning)
        f.ui.setPage(AppPage.MORE)
        assertNull(f.ui.state.value.warning)
        assertEquals(65L, f.ui.state.value.value.intentRevision)
        assertEquals(GatewayIntent.STOP, f.ui.state.value.value.gatewayIntent)
    }

    @Test fun failedIntentIsNotReplayedByRetryReadOrByTheNextIntent() = runTest {
        lateinit var faulty: FaultStore
        val f = fixture(decorate = { FaultStore(it).also { store -> faulty = store } })
        ready(f.ui)
        faulty.failWrite = true
        f.ui.recordGatewayIntent(GatewayIntent.START)
        advanceUntilIdle()
        assertEquals(1, faulty.updates)
        assertEquals(UiPreferenceWarnings.WRITE, f.ui.state.value.warning)
        faulty.failWrite = false
        f.ui.retryRead()
        advanceUntilIdle()
        assertEquals(1, faulty.updates)
        assertFalse(f.file.exists())
        f.ui.recordGatewayIntent(GatewayIntent.STOP)
        f.ui.setTheme(ThemeMode.SYSTEM)
        assertEquals(GatewayIntent.STOP, f.ui.state.value.value.gatewayIntent)
        assertEquals(1L, f.ui.state.value.value.intentRevision)
    }

    @Test fun readAndWriteWarningsStaySeparateAndNeverIncludeOriginalCause() = runTest {
        lateinit var faulty: FaultStore
        val f = fixture(decorate = { FaultStore(it).also { store ->
            faulty = store; store.failRead = true; store.failWrite = true
        } })
        assertEquals(UiPreferenceWarnings.READ, warned(f.ui).warning)
        val failure = rejected { f.ui.setTheme(ThemeMode.DARK) }
        assertEquals(UiPreferenceWarnings.WRITE, failure.message)
        assertNull(failure.cause)
        assertTrue(f.ui.state.value.warning!!.contains(UiPreferenceWarnings.READ))
        assertTrue(f.ui.state.value.warning!!.contains(UiPreferenceWarnings.WRITE))
        assertFalse(f.ui.state.value.warning!!.contains("must-not-leak"))
        assertFalse(f.ui.state.value.warning!!.contains("private"))
        faulty.failRead = false
        f.ui.retryRead()
        advanceUntilIdle()
        assertEquals(UiPreferenceWarnings.WRITE, f.ui.state.value.warning)
        assertFalse(f.file.exists())
        faulty.failWrite = false
        f.ui.setPage(AppPage.MORE)
        assertNull(f.ui.state.value.warning)
    }

    @Test fun failedReadStaysNotReadyUntilRetryRestoresSavedValues() = runTest {
        lateinit var faulty: FaultStore
        val f = fixture(seed = {
            this[UiPreferencesCodec.schema] = 1
            this[UiPreferencesCodec.theme] = ThemeMode.DARK.name
            this[UiPreferencesCodec.page] = AppPage.CHAT.name
            this[UiPreferencesCodec.modelId] = "provider/saved-model"
            this[UiPreferencesCodec.gatewayIntent] = GatewayIntent.STOP.name
            this[UiPreferencesCodec.intentRevision] = 12L
        }, decorate = { FaultStore(it).also { store -> faulty = store; store.failRead = true } })
        assertEquals(UiPreferenceWarnings.READ, warned(f.ui).warning)
        assertFalse(f.ui.state.value.ready)
        assertEquals(UiPreferences(), f.ui.state.value.value)
        val original = f.file.readBytes()
        faulty.failRead = false
        f.ui.retryRead()
        val restored = ready(f.ui)
        assertEquals(UiPreferences(ThemeMode.DARK, AppPage.CHAT, "provider/saved-model", GatewayIntent.STOP, 12), restored.value)
        assertNull(restored.warning)
        assertEquals(0, faulty.updates)
        assertArrayEquals(original, f.file.readBytes())
    }

    @Test fun transactionValidatesLatestStoreNotEarlierHealthyState() = runTest {
        val f = fixture()
        ready(f.ui)
        f.ui.setTheme(ThemeMode.LIGHT)
        f.store.edit { it[UiPreferencesCodec.schema] = 9 }
        val original = f.file.readBytes()
        rejected { f.ui.setPage(AppPage.CHAT) }
        assertFalse(f.ui.state.value.ready)
        assertTrue(f.ui.state.value.warning!!.contains(UiPreferenceWarnings.INVALID))
        assertArrayEquals(original, f.file.readBytes())
    }

    @Test fun writeFailurePreservesExistingFileAndRetryOnlyReads() = runTest {
        lateinit var faulty: FaultStore
        val f = fixture(seed = {
            this[UiPreferencesCodec.schema] = 1
            this[UiPreferencesCodec.theme] = ThemeMode.LIGHT.name
        }, decorate = { FaultStore(it).also { store -> faulty = store } })
        ready(f.ui)
        val bytes = f.file.readBytes()
        faulty.failWrite = true
        rejected { f.ui.setTheme(ThemeMode.DARK) }
        val calls = faulty.updates
        repeat(3) { f.ui.retryRead() }
        advanceUntilIdle()
        assertEquals(calls, faulty.updates)
        assertArrayEquals(bytes, f.file.readBytes())
        assertEquals(ThemeMode.LIGHT, f.ui.state.value.value.theme)
        assertEquals(UiPreferenceWarnings.WRITE, f.ui.state.value.warning)
    }

    @Test fun retryReadOnDefaultsNeverWritesAndRunsExactlyRequestedReads() = runTest {
        lateinit var counted: FaultStore
        val f = fixture(decorate = { FaultStore(it).also { store -> counted = store } })
        ready(f.ui)
        repeat(3) { f.ui.retryRead() }
        advanceUntilIdle()
        assertEquals(4, counted.reads)
        assertEquals(0, counted.updates)
        assertFalse(f.file.exists())
    }

    @Test fun modelIdAcceptsIdentifiersSlashesAndEmptyButRejectsSensitiveFreeInput() = runTest {
        val f = fixture()
        ready(f.ui)
        val valid = listOf("", "provider/model", "nvidia/meta/llama-3.1", "chat:qwen/qwen3-32b", "模型/版本-1", "m".repeat(512))
        for (model in valid) {
            f.ui.setModelId(model)
            assertEquals(model, f.ui.state.value.value.modelId)
        }
        val bytes = f.file.readBytes()
        val bad = listOf("m".repeat(513), "https://example/model", "file:/private", "provider/model?key=value",
            "provider/model#fragment", "Bearer token", "cookie=value", "chat:sk-private", "api_key_private",
            "hf_private", "ghp_private", "github_pat_private", "AKIAABCDEFGHIJKLMNOP", "AIzaPrivate",
            "eyJhbGciOiJIUzI1NiJ9.payload.signature", "a".repeat(64), "provider/" + "a".repeat(64),
            "sk_private", "key_private", "token_private", "provider/" + "Aa1".repeat(20),
            "free form text", "x\n", "x\u0000", "x\t")
        for (model in bad) {
            val error = rejected { f.ui.setModelId(model) }
            assertEquals(UiPreferenceWarnings.WRITE, error.message)
            assertNull(error.cause)
            assertEquals(UiPreferenceWarnings.MODEL, f.ui.state.value.warning)
            assertArrayEquals(bytes, f.file.readBytes())
        }
    }

    @Test fun sensitiveModelAlreadyOnDiskBlocksOtherWritesAndPreservesFile() = runTest {
        val f = fixture(seed = {
            this[UiPreferencesCodec.schema] = 1
            this[UiPreferencesCodec.modelId] = "chat:sk-private"
        })
        assertEquals(UiPreferenceWarnings.INVALID, warned(f.ui).warning)
        val bytes = f.file.readBytes()
        rejected { f.ui.setPage(AppPage.CHAT) }
        rejected { f.ui.setModelId("safe/model") }
        assertArrayEquals(bytes, f.file.readBytes())
    }

    @Test fun revisionAndIntentShareOneTransactionAndNoneIsAlsoJustMetadata() = runTest {
        val f = fixture()
        ready(f.ui)
        f.ui.recordGatewayIntent(GatewayIntent.START)
        f.ui.recordGatewayIntent(GatewayIntent.STOP)
        f.ui.recordGatewayIntent(GatewayIntent.NONE)
        f.ui.setTheme(ThemeMode.SYSTEM)
        val stored = f.store.data.first()
        assertEquals("NONE", stored[UiPreferencesCodec.gatewayIntent])
        assertEquals(3L, stored[UiPreferencesCodec.intentRevision])
        assertEquals(1, stored[UiPreferencesCodec.schema])
        assertNull(stored[UiPreferencesCodec.page])
        assertNull(stored[UiPreferencesCodec.modelId])
    }

    @Test fun closedRepositoryRejectsRecordsAndSettersWithoutTouchingDisk() = runTest {
        val f = fixture()
        ready(f.ui)
        f.ui.setTheme(ThemeMode.LIGHT)
        val bytes = f.file.readBytes()
        f.close()
        f.ui.recordGatewayIntent(GatewayIntent.START)
        assertEquals(UiPreferenceWarnings.QUEUE, f.ui.state.value.warning)
        rejected { f.ui.setPage(AppPage.CHAT) }
        f.ui.retryRead()
        assertTrue(f.ui.state.value.warning!!.contains(UiPreferenceWarnings.QUEUE))
        assertArrayEquals(bytes, f.file.readBytes())
    }

    @Test fun metadataIntentsDoNotStartAndroidServicesOrCallHttp() = runTest {
        val application = ApplicationProvider.getApplicationContext<Application>()
        val shadow = Shadows.shadowOf(application)
        assertNull(shadow.nextStartedService)
        val f = fixture()
        ready(f.ui)
        ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { server ->
            server.soTimeout = 100
            rejected { f.ui.setModelId("http://127.0.0.1:" + server.localPort + "/model") }
            f.ui.recordGatewayIntent(GatewayIntent.START)
            f.ui.recordGatewayIntent(GatewayIntent.STOP)
            f.ui.setPage(AppPage.GATEWAY)
            withContext(Dispatchers.IO) {
                assertThrows(SocketTimeoutException::class.java) { server.accept().use { } }
            }
        }
        assertNull(shadow.nextStartedService)
    }

    @Test fun openUsesApplicationContextAndOneProcessSingleton() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val wrapper = object : android.content.ContextWrapper(context) {
            override fun getApplicationContext(): Context = context.applicationContext
            override fun getFilesDir(): File = throw AssertionError("Must use application context")
        }
        val first = AppUiPreferences.open(wrapper)
        val second = AppUiPreferences.open(context.applicationContext)
        assertSame(first, second)
        ready(first)
        assertFalse(File(context.filesDir, "datastore/ui.preferences_pb").exists())
    }
}
