package cc.fplj.magpiemobile.preferences

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import java.io.File
import java.math.BigInteger

/**
 * UI metadata only. The injected scope must belong to the repository/process,
 * not an Activity: cancelling a caller's await never cancels an accepted write.
 * There is one bounded FIFO actor and no Service, HTTP or inference dependency.
 */
class AppUiPreferences(
    private val dataStore: DataStore<Preferences>,
    scope: CoroutineScope,
) {
    private sealed interface Command {
        data object Read : Command
        class Write(
            val change: (MutablePreferences, UiPreferences) -> Unit,
            val sequence: BigInteger,
            val result: CompletableDeferred<Unit>? = null,
        ) : Command
    }

    private val commands = Channel<Command>(QUEUE_CAPACITY)
    private val stateLock = Any()
    private val mutableState = MutableStateFlow(UiPreferencesState())
    val state: StateFlow<UiPreferencesState> = mutableState.asStateFlow()
    private var readWarning: String? = null
    private var writeWarning: String? = null
    private var requestSequence: BigInteger = BigInteger.ZERO
    private var writeWarningSequence: BigInteger? = null

    private val actor = scope.launch {
        try {
            readOnce()
            for (command in commands) {
                when (command) {
                    Command.Read -> readOnce()
                    is Command.Write -> persist(command)
                }
            }
        } finally {
            closeQueue()
        }
    }.also { job -> job.invokeOnCompletion { closeQueue() } }

    suspend fun setTheme(theme: ThemeMode) = write { preferences, _ ->
        preferences[UiPreferencesCodec.theme] = theme.name
    }

    suspend fun setPage(page: AppPage) = write { preferences, _ ->
        preferences[UiPreferencesCodec.page] = page.name
    }

    suspend fun setModelId(modelId: String) {
        val sequence = nextRequestSequence()
        if (!UiModelIdPolicy.valid(modelId)) {
            publishWriteWarning(UiPreferenceWarnings.MODEL, sequence)
            throw UiPreferencesWriteException()
        }
        write(sequence) { preferences, _ -> preferences[UiPreferencesCodec.modelId] = modelId }
    }

    /** Non-blocking enqueue. START/STOP/NONE are records, never executable commands. */
    fun recordGatewayIntent(intent: GatewayIntent) {
        val command = newWrite({ preferences, current ->
            if (current.intentRevision == Long.MAX_VALUE) throw UiIntentRevisionOverflow()
            preferences[UiPreferencesCodec.gatewayIntent] = intent.name
            preferences[UiPreferencesCodec.intentRevision] = current.intentRevision + 1
        })
        if (commands.trySend(command).isFailure) publishWriteWarning(UiPreferenceWarnings.QUEUE, command.sequence)
    }

    /** Only reread the DataStore; never persist defaults or replay a failed write. */
    fun retryRead() {
        if (commands.trySend(Command.Read).isFailure) publishReadWarning(UiPreferenceWarnings.READ)
    }

    private fun nextRequestSequence(): BigInteger = synchronized(stateLock) {
        requestSequence = requestSequence.add(BigInteger.ONE)
        requestSequence
    }

    private fun newWrite(
        change: (MutablePreferences, UiPreferences) -> Unit,
        result: CompletableDeferred<Unit>? = null,
        sequence: BigInteger = nextRequestSequence(),
    ): Command.Write = Command.Write(change, sequence, result)

    private suspend fun write(
        sequence: BigInteger = nextRequestSequence(),
        change: (MutablePreferences, UiPreferences) -> Unit,
    ) {
        // No parent Job: UI cancellation stops only the caller's await, not the actor.
        val result = CompletableDeferred<Unit>()
        val command = newWrite(change, result, sequence)
        try {
            commands.send(command)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            publishWriteWarning(UiPreferenceWarnings.QUEUE, sequence)
            throw UiPreferencesWriteException()
        }
        result.await()
    }

    private suspend fun readOnce() {
        try {
            val value = UiPreferencesCodec.decode(dataStore.data.first())
            synchronized(stateLock) {
                readWarning = null
                mutableState.value = UiPreferencesState(true, value, warnings())
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: InvalidStoredUiPreferences) {
            publishReadWarning(UiPreferenceWarnings.INVALID)
        } catch (_: Exception) {
            publishReadWarning(UiPreferenceWarnings.READ)
        }
    }

    private suspend fun persist(command: Command.Write) {
        try {
            val stored = dataStore.edit { preferences ->
                // Validate every owned field and schema before touching any key.
                // This runs against the actual latest DataStore value in one transaction.
                val current = UiPreferencesCodec.decode(preferences)
                command.change(preferences, current)
                preferences[UiPreferencesCodec.schema] = 1
            }
            val value = UiPreferencesCodec.decode(stored)
            synchronized(stateLock) {
                // An old completion must not hide a newer rejected/failed request.
                if (writeWarningSequence == null || command.sequence > checkNotNull(writeWarningSequence)) {
                    writeWarning = null
                    writeWarningSequence = null
                }
                mutableState.value = UiPreferencesState(true, value, warnings())
            }
            command.result?.complete(Unit)
        } catch (cancelled: CancellationException) {
            command.result?.completeExceptionally(UiPreferencesWriteException())
            throw cancelled // The outcome might be unknown. Never replay it.
        } catch (_: InvalidStoredUiPreferences) {
            publishReadWarning(UiPreferenceWarnings.INVALID)
            fail(command, UiPreferenceWarnings.WRITE)
        } catch (_: UiIntentRevisionOverflow) {
            fail(command, UiPreferenceWarnings.REVISION)
        } catch (_: Exception) {
            fail(command, UiPreferenceWarnings.WRITE)
        }
    }

    private fun fail(command: Command.Write, warning: String) {
        publishWriteWarning(warning, command.sequence)
        command.result?.completeExceptionally(UiPreferencesWriteException())
    }

    private fun publishReadWarning(warning: String) = synchronized(stateLock) {
        readWarning = warning
        // Defaults may be displayed, but must not be mistaken for hydrated metadata.
        mutableState.value = mutableState.value.copy(ready = false, warning = warnings())
    }

    private fun publishWriteWarning(warning: String, sequence: BigInteger) = synchronized(stateLock) {
        // Failure completion order is not request order; never replace a newer failure.
        if (writeWarningSequence == null || sequence >= checkNotNull(writeWarningSequence)) {
            writeWarning = warning
            writeWarningSequence = sequence
            mutableState.value = mutableState.value.copy(warning = warnings())
        }
    }

    // Keep read and write failures independent: retryRead cannot erase a failed write.
    private fun warnings(): String? = listOfNotNull(readWarning, writeWarning)
        .distinct().takeIf { it.isNotEmpty() }?.joinToString(" ")

    private fun closeQueue() {
        commands.close()
        while (true) {
            val command = commands.tryReceive().getOrNull() ?: break
            if (command is Command.Write) command.result?.completeExceptionally(UiPreferencesWriteException())
        }
    }

    companion object {
        private const val QUEUE_CAPACITY = 64
        @Volatile private var instance: AppUiPreferences? = null

        fun open(context: Context): AppUiPreferences = instance ?: synchronized(this) {
            instance ?: run {
                val appContext = context.applicationContext
                val processScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
                val store = PreferenceDataStoreFactory.create(
                    scope = processScope,
                    produceFile = { File(appContext.filesDir, "datastore/ui.preferences_pb") },
                )
                AppUiPreferences(store, processScope).also { instance = it }
            }
        }
    }
}
