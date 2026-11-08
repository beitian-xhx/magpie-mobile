package cc.fplj.magpiemobile.data

import android.content.Context
import androidx.room.Room
import cc.fplj.magpiemobile.core.ChatClient
import cc.fplj.magpiemobile.core.ChatTurnStore
import cc.fplj.magpiemobile.core.SessionRecord
import java.io.File
import java.io.IOException
import java.util.UUID
import java.util.WeakHashMap
import java.util.concurrent.Callable

data class ConversationSummary(val id: String, val title: String, val updatedAt: Long, val legacy: Boolean)
data class StoredChatMessage(val id: Long, val role: String, val text: String, val engine: String, val ts: Long, val state: String)
data class LegacyImportReport(val imported: Int = 0, val skipped: Int = 0, val warning: String? = null)

/** No free text, caller-supplied message or cause is accepted. */
class ContextLimitException : IllegalStateException("Chat context limit exceeded")

/** All methods are synchronous. App callers must use Dispatchers.IO. */
class ChatRepository(val database: ChatDatabase) {
    private val dao = database.chatDao()
    private val initializationLock = Any()
    private var recovered = false
    private var importFinished = false

    /**
     * Recovery commits once before any begin, independent of a retryable legacy scan.
     * A failed legacy scan cannot re-arm recovery and interrupt a live request.
     * Legacy inserts and their completion marker still share one transaction.
     */
    fun initialize(legacyFile: File): LegacyImportReport = synchronized(initializationLock) {
        recoverOnce()
        if (importFinished) return@synchronized LegacyImportReport()
        val report = try {
            transaction {
                if (dao.metadata(LEGACY_MARKER) == null && legacyFile.exists()) {
                    importLegacy(legacyFile)
                } else LegacyImportReport()
            }
        } catch (_: LegacyFileTooLarge) {
            return@synchronized LegacyImportReport(warning = LegacyChatImporter.TOO_LARGE_WARNING)
        } catch (_: IOException) {
            return@synchronized LegacyImportReport(warning = LegacyChatImporter.READ_WARNING)
        } catch (_: SecurityException) {
            return@synchronized LegacyImportReport(warning = LegacyChatImporter.READ_WARNING)
        }
        importFinished = true
        report
    }

    fun conversations(offset: Int = 0, limit: Int = 30): List<ConversationSummary> {
        require(offset >= 0 && limit >= 0) { "Invalid pagination" }
        return dao.conversations(offset, limit).map { it.summary() }
    }

    fun createConversation(): String = transaction {
        val id = UUID.randomUUID().toString()
        dao.insertConversation(ConversationEntity(id, NEW_TITLE, System.currentTimeMillis()))
        id
    }

    fun conversation(id: String): ConversationSummary? = dao.conversation(id)?.summary()

    /** Latest page, then earlier id-based pages; each page is in insertion-time order. */
    fun messages(conversationId: String, beforeId: Long? = null, limit: Int = 50): List<StoredChatMessage> {
        require(limit >= 0) { "Invalid pagination" }
        return dao.messages(conversationId, beforeId, limit).asReversed()
    }

    /** A bounded full context, never a silent tail/truncation. Legacy is not context. */
    fun context(conversationId: String): List<ChatClient.Message> = transaction {
        val conversation = requireConversation(conversationId)
        if (conversation.legacy) return@transaction emptyList()
        val pairs = dao.successfulPairs(conversationId, MAX_CONTEXT_TURNS + 1)
        if (pairs.size > MAX_CONTEXT_TURNS) throw ContextLimitException()
        val characters = pairs.sumOf { it.userText.length.toLong() + it.assistantText.length.toLong() }
        if (characters > MAX_CONTEXT_CHARACTERS) throw ContextLimitException()
        pairs.flatMap { listOf(ChatClient.Message("user", it.userText), ChatClient.Message("assistant", it.assistantText)) }
    }

    fun turnStore(conversationId: String): ChatTurnStore = object : ChatTurnStore {
        override fun begin(user: SessionRecord): String = synchronized(initializationLock) {
            recoverOnce()
            transaction {
                validateRecord(user, "user")
                val conversation = requireWritableConversation(conversationId)
                check(dao.runningOperation(conversationId) == null) { "A chat turn is already running" }
                val pairs = dao.successfulPairs(conversationId, MAX_CONTEXT_TURNS + 1)
                val characters = pairs.sumOf { it.userText.length.toLong() + it.assistantText.length.toLong() }
                if (pairs.size >= MAX_CONTEXT_TURNS || characters + user.text.length > MAX_CONTEXT_CHARACTERS) {
                    throw ContextLimitException()
                }
                val turnId = UUID.randomUUID().toString()
                dao.insertOperation(ChatOperationEntity(turnId, conversationId, "RUNNING", user.ts,
                    runningConversationId = conversationId))
                val firstMessage = dao.messageCount(conversationId) == 0
                dao.insertMessage(user.entity(conversationId, turnId))
                if (firstMessage && conversation.title == NEW_TITLE) {
                    val title = user.text.take(80).substringBefore('\n').substringBefore('\r').trim().ifEmpty { NEW_TITLE }
                    dao.nameConversation(conversationId, title, user.ts)
                } else dao.touchConversation(conversationId, user.ts)
                turnId
            }
        }

        override fun complete(turnId: String, assistant: SessionRecord) {
            transaction {
                validateRecord(assistant, "assistant")
                requireRunningTurn(conversationId, turnId)
                dao.insertMessage(assistant.entity(conversationId, turnId))
                check(dao.finishOperation(turnId, conversationId, "SUCCEEDED", assistant.ts) == 1) {
                    "Chat turn is no longer running"
                }
                dao.touchConversation(conversationId, assistant.ts)
            }
        }

        override fun fail(turnId: String, failure: SessionRecord) {
            transaction {
                validateRecord(failure, "error")
                require(ChatRecordPolicy.safeFailure(failure.text)) { "Invalid chat failure summary" }
                requireRunningTurn(conversationId, turnId)
                dao.insertMessage(failure.entity(conversationId, turnId))
                check(dao.finishOperation(turnId, conversationId, "FAILED", failure.ts) == 1) {
                    "Chat turn is no longer running"
                }
                dao.touchConversation(conversationId, failure.ts)
            }
        }
    }

    private fun recoverOnce() {
        if (recovered) return
        // Share the gate for direct constructors using the same database too.
        // A reopened database is a new key and gets normal crash recovery.
        synchronized(recoveredDatabases) {
            if (recoveredDatabases[database] != true) {
                transaction { dao.recoverPending(System.currentTimeMillis()) }
                recoveredDatabases[database] = true
            }
            recovered = true
        }
    }

    private fun importLegacy(file: File): LegacyImportReport {
        var archiveId: String? = null
        var updatedAt = 0L
        val report = LegacyChatImporter.scan(file) { record ->
            val id = archiveId ?: UUID.randomUUID().toString().also {
                dao.insertConversation(ConversationEntity(it, LEGACY_TITLE, record.ts, legacy = true))
                archiveId = it
            }
            dao.insertMessage(record.entity(id, null))
            updatedAt = maxOf(updatedAt, record.ts)
        }
        archiveId?.let { dao.touchConversation(it, updatedAt) }
        // Even an empty/all-invalid source was successfully scanned; never duplicate it.
        dao.insertMetadata(ChatMetadataEntity(LEGACY_MARKER, archiveId ?: "empty"))
        return report
    }

    private fun requireConversation(id: String): ConversationEntity =
        checkNotNull(dao.conversation(id)) { "Chat conversation not found" }

    private fun requireWritableConversation(id: String): ConversationEntity = requireConversation(id).also {
        check(!it.legacy) { "Legacy chat archive is read-only" }
    }

    private fun requireRunningTurn(conversationId: String, turnId: String) {
        requireWritableConversation(conversationId)
        val operation = dao.operation(turnId)
        check(operation != null && operation.conversationId == conversationId &&
            operation.state == "RUNNING" && operation.runningConversationId == conversationId) {
            "Chat turn is no longer running"
        }
        val messages = dao.operationMessages(turnId)
        check(messages.size == 1 && messages.single().role == "user" &&
            messages.single().conversationId == conversationId) { "Invalid pending chat turn" }
    }

    private fun validateRecord(record: SessionRecord, role: String) {
        require(record.role == role && record.ts >= 0 && record.text.length <= MAX_MESSAGE_CHARACTERS &&
            ChatRecordPolicy.engineIsIdentifier(record.engine)) {
            "Invalid chat record"
        }
    }

    private fun SessionRecord.entity(conversationId: String, operationId: String?) =
        ChatMessageEntity(conversationId = conversationId, operationId = operationId,
            role = role, text = text, engine = engine, ts = ts)

    private fun ConversationEntity.summary() = ConversationSummary(id, title, updatedAt, legacy)
    private fun <T> transaction(action: () -> T): T = database.runInTransaction(Callable { action() })

    companion object {
        internal const val LEGACY_MARKER = "legacy_chat_jsonl_import_v1"
        private const val NEW_TITLE = "新会话"
        private const val LEGACY_TITLE = "旧聊天记录（只读）"
        private const val MAX_MESSAGE_CHARACTERS = 256 * 1024
        private const val MAX_CONTEXT_TURNS = 40
        private val recoveredDatabases = WeakHashMap<ChatDatabase, Boolean>()
        private const val MAX_CONTEXT_CHARACTERS = 65536L
        @Volatile private var instance: ChatRepository? = null

        /** Process singleton; Activity rotation must not trigger pending recovery again. */
        fun open(context: Context): ChatRepository = instance ?: synchronized(this) {
            instance ?: ChatRepository(Room.databaseBuilder(context.applicationContext,
                ChatDatabase::class.java, "chat.db").build()).also { instance = it }
        }
    }
}
