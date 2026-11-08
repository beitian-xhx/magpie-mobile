package cc.fplj.magpiemobile.data

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import cc.fplj.magpiemobile.core.ChatClient
import cc.fplj.magpiemobile.core.SessionRecord
import cc.fplj.magpiemobile.core.StreamingChatFailure
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.io.RandomAccessFile
import java.util.UUID
import java.util.concurrent.Callable
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [32])
class ChatRepositoryTest {
    @get:Rule val temporary = TemporaryFolder()
    private val worker = Executors.newSingleThreadExecutor()
    private lateinit var context: Context
    private lateinit var database: ChatDatabase
    private lateinit var repository: ChatRepository
    private val absent get() = File(temporary.root, "missing.jsonl")

    @Before fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java).build()
        repository = ChatRepository(database)
    }

    @After fun tearDown() {
        io { database.close() }
        worker.shutdownNow()
    }

    private fun <T> io(action: () -> T): T = worker.submit(Callable { action() }).get(30, TimeUnit.SECONDS)
    private fun record(role: String, text: String, ts: Long = 1, engine: String = "chat:test") =
        SessionRecord(ts, role, engine, text)
    private fun source(vararg lines: String): File = temporary.newFile().apply { writeText(lines.joinToString("\n")) }
    private fun succeeded(id: String, user: String = "question", assistant: String = "answer", ts: Long = 1) {
        val store = repository.turnStore(id)
        val turn = store.begin(record("user", user, ts))
        store.complete(turn, record("assistant", assistant, ts + 1, "chat:test:stream"))
    }
    private fun seedPair(id: String, user: String, assistant: String) {
        val dao = database.chatDao()
        val turn = UUID.randomUUID().toString()
        database.runInTransaction {
            dao.insertOperation(ChatOperationEntity(turn, id, "SUCCEEDED", 1, 2))
            dao.insertMessage(ChatMessageEntity(conversationId = id, operationId = turn,
                role = "user", text = user, engine = "chat:test", ts = 1))
            dao.insertMessage(ChatMessageEntity(conversationId = id, operationId = turn,
                role = "assistant", text = assistant, engine = "chat:test", ts = 2))
        }
    }
    private fun seedPending(id: String): String {
        val dao = database.chatDao()
        val turn = UUID.randomUUID().toString()
        database.runInTransaction {
            dao.insertOperation(ChatOperationEntity(turn, id, "RUNNING", 1, runningConversationId = id))
            dao.insertMessage(ChatMessageEntity(conversationId = id, operationId = turn,
                role = "user", text = "unfinished", engine = "chat:test", ts = 1))
        }
        return turn
    }
    private fun operationCount(): Int = database.openHelper.writableDatabase
        .query("SELECT COUNT(*) FROM operation").use { it.moveToFirst(); it.getInt(0) }

    @Test fun schemaContainsFourRequiredTables(): Unit = io {
        val tables = database.openHelper.writableDatabase.query("SELECT name FROM sqlite_master WHERE type = 'table'")
            .use { cursor -> buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) } }
        assertTrue(tables.containsAll(setOf("conversation", "message", "operation", "metadata")))
    }

    @Test fun beginAndCompletePersistOneAtomicSuccessfulPair(): Unit = io {
        repository.initialize(absent)
        val id = repository.createConversation()
        val store = repository.turnStore(id)
        val turn = store.begin(record("user", "hello", 10))
        assertEquals("RUNNING", database.chatDao().operation(turn)?.state)
        assertEquals(listOf("user"), repository.messages(id).map { it.role })
        assertTrue(repository.context(id).isEmpty())
        store.complete(turn, record("assistant", "world", 11, "chat:test:stream"))
        assertEquals("SUCCEEDED", database.chatDao().operation(turn)?.state)
        assertNull(database.chatDao().operation(turn)?.runningConversationId)
        assertEquals(listOf("SUCCEEDED", "SUCCEEDED"), repository.messages(id).map { it.state })
        assertEquals(listOf(ChatClient.Message("user", "hello"), ChatClient.Message("assistant", "world")), repository.context(id))
        assertEquals("hello", repository.conversation(id)?.title)
    }

    @Test fun terminalAndWrongConversationTurnIdsCannotBeReplayed(): Unit = io {
        val id = repository.createConversation()
        val other = repository.createConversation()
        val store = repository.turnStore(id)
        val turn = store.begin(record("user", "q"))
        assertThrows(IllegalStateException::class.java) {
            repository.turnStore(other).complete(turn, record("assistant", "wrong"))
        }
        store.complete(turn, record("assistant", "a"))
        assertThrows(IllegalStateException::class.java) { store.complete(turn, record("assistant", "duplicate")) }
        assertThrows(IllegalStateException::class.java) { store.fail(turn, record("error", "HTTP 500")) }
        assertThrows(IllegalStateException::class.java) { store.complete("unknown", record("assistant", "a")) }
        assertEquals(2, repository.messages(id).size)
        assertTrue(repository.messages(other).isEmpty())
    }

    @Test fun failedTurnHasOnlyFixedErrorAndNeverEntersContext(): Unit = io {
        val id = repository.createConversation()
        succeeded(id)
        val store = repository.turnStore(id)
        val turn = store.begin(record("user", "failed question"))
        store.fail(turn, record("error", "Stream SSE error; partial characters=123", engine = "chat:test:stream"))
        assertEquals("FAILED", database.chatDao().operation(turn)?.state)
        assertEquals(listOf("user", "assistant", "user", "error"), repository.messages(id).map { it.role })
        assertEquals(listOf("question", "answer"), repository.context(id).map { it.content })
        assertFalse(repository.messages(id).any { it.text == "partial answer" })
        assertThrows(IllegalStateException::class.java) { store.fail(turn, record("error", "HTTP 500")) }
    }

    @Test fun failureRoleAndFreeUpstreamTextAreRejectedWithoutMutation(): Unit = io {
        val id = repository.createConversation()
        val store = repository.turnStore(id)
        val turn = store.begin(record("user", "q"))
        val invalid = listOf("upstream leaked cookie=secret", "HTTP 500 raw body", "HTTP 999",
            "HTTP 99", "chat -> HTTP 500", "Chat failed; partial characters=-1",
            "Chat failed; partial characters=2147483648", "Chat failed; partial characters=001",
            "Stream SSE error; partial characters=5; secret")
        invalid.forEach { text ->
            assertThrows(IllegalArgumentException::class.java) { store.fail(turn, record("error", text)) }
        }
        assertThrows(IllegalArgumentException::class.java) { store.fail(turn, record("assistant", "HTTP 500")) }
        assertEquals("RUNNING", database.chatDao().operation(turn)?.state)
        assertEquals(1, repository.messages(id).size)
        store.fail(turn, record("error", "HTTP 200"))
        assertEquals("FAILED", database.chatDao().operation(turn)?.state)
    }

    @Test fun allCoreFixedFailureCategoriesAreAccepted(): Unit = io {
        val id = repository.createConversation()
        StreamingChatFailure.entries.forEach { failure ->
            val store = repository.turnStore(id)
            val turn = store.begin(record("user", "q"))
            store.fail(turn, record("error", failure.safeMessage + "; partial characters=0"))
        }
        assertTrue(repository.context(id).isEmpty())
        assertEquals(StreamingChatFailure.entries.size * 2, repository.messages(id).size)
    }

    @Test fun invalidRoleTimestampAndCredentialEnginesNeverCreateOperations(): Unit = io {
        val id = repository.createConversation()
        val store = repository.turnStore(id)
        assertThrows(IllegalArgumentException::class.java) { store.begin(record("assistant", "bad role")) }
        assertThrows(IllegalArgumentException::class.java) { store.begin(record("user", "q", -1)) }
        listOf("https://example/chat", "Bearer secret", "chat:sk-secret", "cookie=secret", "chat:api_key").forEach {
            assertThrows(IllegalArgumentException::class.java) { store.begin(record("user", "q", engine = it)) }
        }
        assertEquals(0, operationCount())
        assertTrue(repository.messages(id).isEmpty())
        val turn = store.begin(record("user", "q", engine = "chat:nvidia/meta/llama-3.1"))
        assertThrows(IllegalArgumentException::class.java) { store.complete(turn, record("user", "a")) }
        assertEquals("RUNNING", database.chatDao().operation(turn)?.state)
    }

    @Test fun onlyOneRunningTurnPerConversationButOtherConversationsAreIndependent(): Unit = io {
        val id = repository.createConversation()
        val other = repository.createConversation()
        val store = repository.turnStore(id)
        val turn = store.begin(record("user", "one"))
        assertThrows(IllegalStateException::class.java) { store.begin(record("user", "two")) }
        assertThrows(IllegalStateException::class.java) {
            ChatRepository(database).turnStore(id).begin(record("user", "another repo"))
        }
        val second = repository.turnStore(other).begin(record("user", "independent"))
        assertEquals(2, operationCount())
        store.fail(turn, record("error", "HTTP 429"))
        store.begin(record("user", "manual next turn"))
        assertEquals("RUNNING", database.chatDao().operation(second)?.state)
    }

    @Test fun concurrentBeginsAcrossRepositoriesProduceExactlyOnePendingUser() {
        val id = io { repository.createConversation() }
        val parallel = Executors.newFixedThreadPool(2)
        val gate = CountDownLatch(1)
        try {
            val repos = listOf(repository, ChatRepository(database))
            val calls = repos.map { repo -> parallel.submit(Callable {
                gate.await(5, TimeUnit.SECONDS)
                runCatching { repo.turnStore(id).begin(record("user", "race")) }
            }) }
            gate.countDown()
            val results = calls.map { it.get(10, TimeUnit.SECONDS) }
            assertEquals(1, results.count { it.isSuccess })
            assertEquals(1, results.count { it.isFailure })
            io { assertEquals(1, operationCount()); assertEquals(1, repository.messages(id).size) }
        } finally { parallel.shutdownNow() }
    }

    @Test fun beginRollbackLeavesNeitherOperationNorUser(): Unit = io {
        val id = repository.createConversation()
        val sql = database.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER abort_user BEFORE INSERT ON message WHEN NEW.role = 'user' BEGIN SELECT RAISE(ABORT, 'injected'); END")
        assertThrows(Exception::class.java) { repository.turnStore(id).begin(record("user", "q")) }
        assertEquals(0, operationCount())
        assertTrue(repository.messages(id).isEmpty())
        sql.execSQL("DROP TRIGGER abort_user")
        repository.turnStore(id).begin(record("user", "retry manually"))
        assertEquals(1, operationCount())
    }

    @Test fun completeAndFailRollbackKeepRunningIdentityAndNeverPartialTerminalState(): Unit = io {
        val id = repository.createConversation()
        val store = repository.turnStore(id)
        val turn = store.begin(record("user", "q"))
        val sql = database.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER abort_finish BEFORE UPDATE OF state ON operation WHEN NEW.state IN ('SUCCEEDED', 'FAILED') BEGIN SELECT RAISE(ABORT, 'injected'); END")
        assertThrows(Exception::class.java) { store.complete(turn, record("assistant", "a")) }
        assertEquals("RUNNING", database.chatDao().operation(turn)?.state)
        assertEquals(listOf("user"), repository.messages(id).map { it.role })
        assertThrows(Exception::class.java) { store.fail(turn, record("error", "HTTP 500")) }
        assertEquals("RUNNING", database.chatDao().operation(turn)?.state)
        assertEquals(1, repository.messages(id).size)
        sql.execSQL("DROP TRIGGER abort_finish")
        store.complete(turn, record("assistant", "successful manual completion"))
        assertEquals(2, repository.messages(id).size)
    }

    @Test fun firstSuccessfulInitializeInterruptsPendingWithoutAssistantOrNetwork(): Unit = io {
        val id = repository.createConversation()
        val turn = seedPending(id)
        assertEquals(LegacyImportReport(), repository.initialize(absent))
        assertEquals("INTERRUPTED", database.chatDao().operation(turn)?.state)
        assertEquals(listOf("user"), repository.messages(id).map { it.role })
        assertEquals(listOf("INTERRUPTED"), repository.messages(id).map { it.state })
        assertTrue(repository.context(id).isEmpty())
        assertThrows(IllegalStateException::class.java) {
            repository.turnStore(id).complete(turn, record("assistant", "late response"))
        }
        repository.turnStore(id).begin(record("user", "new explicit turn"))
    }

    @Test fun repeatedInitializeOnSameRepositoryDoesNotInterruptActiveRequest(): Unit = io {
        repository.initialize(absent)
        val id = repository.createConversation()
        val turn = repository.turnStore(id).begin(record("user", "active"))
        repeat(3) { assertEquals(LegacyImportReport(), repository.initialize(absent)) }
        assertEquals("RUNNING", database.chatDao().operation(turn)?.state)
        repository.turnStore(id).complete(turn, record("assistant", "done"))
        assertEquals(2, repository.context(id).size)
    }

    @Test fun runningFailedAndInterruptedUsersAreExcludedButSucceededPairsRemain(): Unit = io {
        val id = repository.createConversation()
        succeeded(id, "success user", "success assistant")
        val store = repository.turnStore(id)
        val failed = store.begin(record("user", "failed user"))
        store.fail(failed, record("error", "HTTP 500"))
        val interrupted = store.begin(record("user", "interrupted user"))
        database.chatDao().finishOperation(interrupted, id, "INTERRUPTED", 2)
        repository.initialize(absent)
        store.begin(record("user", "running user"))
        assertEquals(listOf("success user", "success assistant"), repository.context(id).map { it.content })
    }

    @Test fun incompleteSucceededOperationsAreNotContext(): Unit = io {
        val id = repository.createConversation()
        val op = "invalid-complete-pair"
        database.chatDao().insertOperation(ChatOperationEntity(op, id, "SUCCEEDED", 1, 2))
        database.chatDao().insertMessage(ChatMessageEntity(conversationId = id, operationId = op,
            role = "user", text = "incomplete", engine = "chat:test", ts = 1))
        assertTrue(repository.context(id).isEmpty())
    }

    @Test fun contextAllowsFortyFullTurnsAndThrowsRatherThanTruncatingFortyOne(): Unit = io {
        val id = repository.createConversation()
        repeat(40) { succeeded(id, "q$it", "a$it") }
        assertEquals(80, repository.context(id).size)
        assertThrows(ContextLimitException::class.java) { repository.turnStore(id).begin(record("user", "forty-first")) }
        seedPair(id, "forty-first", "answer")
        val error = assertThrows(ContextLimitException::class.java) { repository.context(id) }
        assertEquals("Chat context limit exceeded", error.message)
        assertNull(error.cause)
    }

    @Test fun contextCharacterLimitIsInclusiveAndNeverSilentlyTruncated(): Unit = io {
        val exact = repository.createConversation()
        succeeded(exact, "x".repeat(32768), "y".repeat(32768))
        assertEquals(65536, repository.context(exact).sumOf { it.content.length })
        val over = repository.createConversation()
        succeeded(over, "x".repeat(65536), "y")
        assertThrows(ContextLimitException::class.java) { repository.context(over) }
    }

    @Test fun beginChecksHistoryPlusCurrentUserBeforeWritingAnyPendingOperation(): Unit = io {
        val id = repository.createConversation()
        succeeded(id, "x".repeat(32768), "y".repeat(32767))
        val before = operationCount()
        assertThrows(ContextLimitException::class.java) { repository.turnStore(id).begin(record("user", "zz")) }
        assertEquals(before, operationCount())
        val turn = repository.turnStore(id).begin(record("user", "z"))
        assertEquals("RUNNING", database.chatDao().operation(turn)?.state)
        val fresh = repository.createConversation()
        assertThrows(ContextLimitException::class.java) { repository.turnStore(fresh).begin(record("user", "x".repeat(65537))) }
        assertTrue(repository.messages(fresh).isEmpty())
    }

    @Test fun liveMessagesHaveAnExplicitCursorSafeCharacterBound(): Unit = io {
        val id = repository.createConversation()
        val store = repository.turnStore(id)
        assertThrows(IllegalArgumentException::class.java) {
            store.begin(record("user", "x".repeat(256 * 1024 + 1)))
        }
        val turn = store.begin(record("user", "q"))
        assertThrows(IllegalArgumentException::class.java) {
            store.complete(turn, record("assistant", "x".repeat(256 * 1024 + 1)))
        }
        assertEquals(1, repository.messages(id).size)
        assertEquals("RUNNING", database.chatDao().operation(turn)?.state)
        store.complete(turn, record("assistant", "x".repeat(256 * 1024)))
        assertEquals(256 * 1024, repository.messages(id).last().text.length)
    }

    @Test fun beginBeforeInitializeRecoversOldPendingOnceButNeverItsOwnNewTurn(): Unit = io {
        val id = repository.createConversation()
        val old = seedPending(id)
        val live = repository.turnStore(id).begin(record("user", "new explicit request"))
        assertEquals("INTERRUPTED", database.chatDao().operation(old)?.state)
        repository.initialize(absent)
        ChatRepository(database).initialize(absent)
        assertEquals("RUNNING", database.chatDao().operation(live)?.state)
    }

    @Test fun messagePagesAreLatestFirstSelectionButChronologicalWithinEachPage(): Unit = io {
        val id = repository.createConversation()
        repeat(5) { succeeded(id, "q$it", "a$it", it.toLong() * 2 + 1) }
        val newest = repository.messages(id, limit = 3)
        assertEquals(listOf("a3", "q4", "a4"), newest.map { it.text })
        assertEquals(newest.map { it.id }.sorted(), newest.map { it.id })
        val older = repository.messages(id, beforeId = newest.first().id, limit = 3)
        assertEquals(listOf("q2", "a2", "q3"), older.map { it.text })
        assertTrue(older.all { it.id < newest.first().id })
        assertTrue(repository.messages(id, beforeId = 0).isEmpty())
        assertTrue(repository.messages(id, limit = 0).isEmpty())
        assertThrows(IllegalArgumentException::class.java) { repository.messages(id, limit = -1) }
    }

    @Test fun conversationPagesAreStableAndMissingConversationIsNullable(): Unit = io {
        val ids = (1..5).map { i ->
            "c$i".also { database.chatDao().insertConversation(ConversationEntity(it, "title$i", i.toLong())) }
        }
        assertEquals(ids.reversed(), repository.conversations().map { it.id })
        assertEquals(listOf("c3", "c2"), repository.conversations(offset = 2, limit = 2).map { it.id })
        assertTrue(repository.conversations(offset = 10).isEmpty())
        assertTrue(repository.conversations(limit = 0).isEmpty())
        assertNull(repository.conversation("missing"))
        assertThrows(IllegalArgumentException::class.java) { repository.conversations(offset = -1) }
    }

    @Test fun legacyImportsIntoOneReadOnlyArchiveAndPreservesSourceBytes(): Unit = io {
        val file = source(record("user", "old one").toJsonLine(), record("assistant", "old answer").toJsonLine(),
            record("user", "unpaired old user").toJsonLine(), record("error", "HTTP 429").toJsonLine())
        val before = file.readBytes()
        assertEquals(LegacyImportReport(imported = 4), repository.initialize(file))
        val archive = repository.conversations().single()
        assertTrue(archive.legacy)
        assertEquals(4, repository.messages(archive.id).size)
        assertTrue(repository.messages(archive.id).all { it.state == "LEGACY" })
        assertTrue(repository.context(archive.id).isEmpty())
        assertThrows(IllegalStateException::class.java) { repository.turnStore(archive.id).begin(record("user", "forbidden")) }
        assertEquals(0, operationCount())
        assertArrayEquals(before, file.readBytes())
        assertNotNull(database.chatDao().metadata(ChatRepository.LEGACY_MARKER))
    }

    @Test fun malformedUnsafeAndOversizedLinesAreCountedAndLaterValidLinesStillImport(): Unit = io {
        val lines = listOf("not JSON secret", "{}", "{\"ts\":1,\"role\":\"user\"}",
            record("unknown", "bad role").toJsonLine(), record("error", "raw upstream secret").toJsonLine(),
            record("user", "q", engine = "chat:sk-secret").toJsonLine(),
            "x".repeat(LegacyChatImporter.MAX_LINE_CHARS + 1), record("assistant", "valid after bad").toJsonLine())
        val file = source(*lines.toTypedArray())
        val original = file.readBytes()
        val report = repository.initialize(file)
        assertEquals(1, report.imported)
        assertEquals(7, report.skipped)
        assertEquals(LegacyChatImporter.BAD_LINES_WARNING, report.warning)
        assertFalse(report.warning!!.contains("secret"))
        assertEquals(listOf("valid after bad"), repository.messages(repository.conversations().single().id).map { it.text })
        assertArrayEquals(original, file.readBytes())
    }

    @Test fun exactLineCharacterLimitAndCrLfAreHandledWithoutFullFileBuffering(): Unit = io {
        val base = record("user", "").toJsonLine()
        val line = record("user", "x".repeat(LegacyChatImporter.MAX_LINE_CHARS - base.length)).toJsonLine()
        assertEquals(LegacyChatImporter.MAX_LINE_CHARS, line.length)
        val file = source(line, record("assistant", "tail").toJsonLine())
        assertEquals(LegacyImportReport(imported = 2), repository.initialize(file))
        val otherDb = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java).build()
        try {
            val crlf = source(record("user", "CRLF").toJsonLine() + "\r\n" + record("assistant", "tail").toJsonLine())
            assertEquals(2, ChatRepository(otherDb).initialize(crlf).imported)
        } finally { otherDb.close() }
    }

    @Test fun oversizedImportRetryNeverInterruptsARequestBegunAfterTheWarning(): Unit = io {
        val id = repository.createConversation()
        val crashed = seedPending(id)
        val file = temporary.newFile("private-source.jsonl")
        RandomAccessFile(file, "rw").use { it.setLength(LegacyChatImporter.MAX_BYTES + 1) }
        val report = repository.initialize(file)
        assertEquals(LegacyChatImporter.TOO_LARGE_WARNING, report.warning)
        assertFalse(report.warning!!.contains(file.name))
        assertEquals(0, report.imported)
        assertNull(database.chatDao().metadata(ChatRepository.LEGACY_MARKER))
        assertEquals("INTERRUPTED", database.chatDao().operation(crashed)?.state)
        val active = repository.turnStore(id).begin(record("user", "active after warning"))
        assertEquals(LegacyChatImporter.MAX_BYTES + 1, file.length())
        file.writeText(record("user", "repaired source").toJsonLine())
        assertEquals(1, repository.initialize(file).imported)
        assertNotNull(database.chatDao().metadata(ChatRepository.LEGACY_MARKER))
        assertEquals("RUNNING", database.chatDao().operation(active)?.state)
        repository.turnStore(id).complete(active, record("assistant", "normal completion"))
    }

    @Test fun byteBoundaryAndGrowthGuardAreEnforcedDuringStreaming(): Unit = io {
        val exact = temporary.newFile()
        RandomAccessFile(exact, "rw").use { it.setLength(LegacyChatImporter.MAX_BYTES) }
        val report = repository.initialize(exact)
        assertEquals(LegacyChatImporter.BAD_LINES_WARNING, report.warning)
        assertEquals(1, report.skipped)
        assertNotNull(database.chatDao().metadata(ChatRepository.LEGACY_MARKER))
        val secondDb = Room.inMemoryDatabaseBuilder(context, ChatDatabase::class.java).build()
        try {
            val growing = temporary.newFile()
            RandomAccessFile(growing, "rw").use {
                it.write((record("user", "prefix inserted before bound failure").toJsonLine() + "\n").toByteArray())
                it.setLength(LegacyChatImporter.MAX_BYTES + 1)
            }
            // Simulate a file growing after the initial length observation.
            val underestimated = object : File(growing.path) { override fun length(): Long = 0 }
            val second = ChatRepository(secondDb)
            assertEquals(LegacyChatImporter.TOO_LARGE_WARNING, second.initialize(underestimated).warning)
            assertNull(secondDb.chatDao().metadata(ChatRepository.LEGACY_MARKER))
            assertTrue(second.conversations().isEmpty())
        } finally { secondDb.close() }
    }

    @Test fun readFailureAndMalformedUtf8RollbackAndLeaveRetryableMarkerAbsent(): Unit = io {
        val directory = temporary.newFolder("private-path")
        val report = repository.initialize(directory)
        assertEquals(LegacyChatImporter.READ_WARNING, report.warning)
        assertFalse(report.warning!!.contains(directory.name))
        assertNull(database.chatDao().metadata(ChatRepository.LEGACY_MARKER))
        val malformed = temporary.newFile()
        malformed.writeBytes((record("user", "x".repeat(10000)).toJsonLine() + "\n").toByteArray() + byteArrayOf(0xC3.toByte()))
        assertEquals(LegacyChatImporter.READ_WARNING, repository.initialize(malformed).warning)
        assertTrue(repository.conversations().isEmpty())
        assertNull(database.chatDao().metadata(ChatRepository.LEGACY_MARKER))
        malformed.writeText(record("assistant", "retry").toJsonLine())
        assertEquals(1, repository.initialize(malformed).imported)
    }

    @Test fun importAndMarkerRollbackTogetherOnDatabaseFailure(): Unit = io {
        val file = source(record("user", "legacy").toJsonLine())
        val id = repository.createConversation()
        val turn = seedPending(id)
        val sql = database.openHelper.writableDatabase
        sql.execSQL("CREATE TRIGGER abort_marker BEFORE INSERT ON metadata BEGIN SELECT RAISE(ABORT, 'injected'); END")
        assertThrows(Exception::class.java) { repository.initialize(file) }
        assertEquals(1, repository.conversations().size)
        assertEquals("INTERRUPTED", database.chatDao().operation(turn)?.state)
        assertNull(database.chatDao().metadata(ChatRepository.LEGACY_MARKER))
        val active = repository.turnStore(id).begin(record("user", "live after import failure"))
        sql.execSQL("DROP TRIGGER abort_marker")
        assertEquals(1, repository.initialize(file).imported)
        assertEquals("INTERRUPTED", database.chatDao().operation(turn)?.state)
        assertEquals(2, repository.conversations().size)
        assertEquals("RUNNING", database.chatDao().operation(active)?.state)
    }

    @Test fun successfulLegacyScanIsIdempotentEvenForNewRepositoryAndChangedSource(): Unit = io {
        val file = source(record("user", "once").toJsonLine())
        assertEquals(1, repository.initialize(file).imported)
        file.appendText("\n" + record("assistant", "must not duplicate or append after marker").toJsonLine())
        assertEquals(LegacyImportReport(), repository.initialize(file))
        assertEquals(LegacyImportReport(), ChatRepository(database).initialize(file))
        assertEquals(1, repository.conversations().size)
        assertEquals(1, repository.messages(repository.conversations().single().id).size)
    }

    @Test fun absentSourceIsNoOpAndDoesNotWriteImportMarker(): Unit = io {
        assertEquals(LegacyImportReport(), repository.initialize(absent))
        assertTrue(repository.conversations().isEmpty())
        assertNull(database.chatDao().metadata(ChatRepository.LEGACY_MARKER))
    }

    @Test fun closeReopenPersistsSuccessAndLegacyMarkerAndRecoversPendingOnce(): Unit = io {
        database.close()
        val name = "chat-test-" + UUID.randomUUID() + ".db"
        database = Room.databaseBuilder(context, ChatDatabase::class.java, name).build()
        repository = ChatRepository(database)
        val file = source(record("assistant", "legacy archive").toJsonLine())
        assertEquals(1, repository.initialize(file).imported)
        val id = repository.createConversation()
        succeeded(id, "persisted q", "persisted a")
        val pending = repository.turnStore(id).begin(record("user", "crashed request"))
        database.close()
        database = Room.databaseBuilder(context, ChatDatabase::class.java, name).build()
        repository = ChatRepository(database)
        assertEquals(LegacyImportReport(), repository.initialize(file))
        assertEquals("INTERRUPTED", database.chatDao().operation(pending)?.state)
        assertEquals(listOf("persisted q", "persisted a"), repository.context(id).map { it.content })
        assertEquals(1, repository.conversations().count { it.legacy })
        val active = repository.turnStore(id).begin(record("user", "new request"))
        repository.initialize(file)
        assertEquals("RUNNING", database.chatDao().operation(active)?.state)
        database.close()
        context.deleteDatabase(name)
    }

    @Test fun unknownDatabaseVersionFailsWithoutDestructiveMigrationAndPreservesSentinel(): Unit = io {
        val name = "unsupported-" + UUID.randomUUID() + ".db"
        val path = context.getDatabasePath(name)
        path.parentFile!!.mkdirs()
        SQLiteDatabase.openOrCreateDatabase(path, null).use {
            it.execSQL("CREATE TABLE sentinel (value TEXT NOT NULL)")
            it.execSQL("INSERT INTO sentinel VALUES ('keep')")
            it.version = 2
        }
        val incompatible = Room.databaseBuilder(context, ChatDatabase::class.java, name).build()
        try {
            assertThrows(IllegalStateException::class.java) { ChatRepository(incompatible).conversations() }
        } finally { incompatible.close() }
        SQLiteDatabase.openOrCreateDatabase(path, null).use {
            assertEquals(2, it.version)
            it.rawQuery("SELECT value FROM sentinel", null).use { cursor ->
                assertTrue(cursor.moveToFirst()); assertEquals("keep", cursor.getString(0))
            }
        }
        context.deleteDatabase(name)
    }

    @Test fun productionOpenUsesProcessSingletonAndDiskChatDatabase() {
        val first = ChatRepository.open(context)
        val second = ChatRepository.open(context.applicationContext)
        assertSame(first, second)
        io {
            first.initialize(absent)
            first.createConversation()
            assertEquals("chat.db", File(first.database.openHelper.writableDatabase.path).name)
        }
        assertThrows(IllegalStateException::class.java) { first.conversations() }
    }
}
