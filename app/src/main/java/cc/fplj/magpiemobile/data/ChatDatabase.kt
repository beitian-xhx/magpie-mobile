package cc.fplj.magpiemobile.data

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase

@Entity(tableName = "conversation")
data class ConversationEntity(
    @PrimaryKey val id: String,
    val title: String,
    val updatedAt: Long,
    val legacy: Boolean = false,
)

@Entity(
    tableName = "operation",
    foreignKeys = [ForeignKey(
        entity = ConversationEntity::class, parentColumns = ["id"],
        childColumns = ["conversationId"], onDelete = ForeignKey.RESTRICT,
    )],
    indices = [Index(value = ["conversationId"]), Index(value = ["runningConversationId"], unique = true)],
)
data class ChatOperationEntity(
    @PrimaryKey val id: String,
    val conversationId: String,
    val state: String,
    val startedAt: Long,
    val finishedAt: Long? = null,
    // SQLite permits many NULLs but only one non-NULL value per conversation.
    // This also enforces the single-RUNNING invariant across repository instances.
    val runningConversationId: String? = null,
)

@Entity(
    tableName = "message",
    foreignKeys = [
        ForeignKey(entity = ConversationEntity::class, parentColumns = ["id"],
            childColumns = ["conversationId"], onDelete = ForeignKey.RESTRICT),
        ForeignKey(entity = ChatOperationEntity::class, parentColumns = ["id"],
            childColumns = ["operationId"], onDelete = ForeignKey.RESTRICT),
    ],
    indices = [Index(value = ["conversationId"]), Index(value = ["operationId"]),
        Index(value = ["operationId", "role"], unique = true)],
)
data class ChatMessageEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val conversationId: String,
    val operationId: String?,
    val role: String,
    val text: String,
    val engine: String,
    val ts: Long,
)

@Entity(tableName = "metadata")
data class ChatMetadataEntity(@PrimaryKey val key: String, val value: String)

/** Only full, successful pairs can be selected by the context query. */
data class SuccessfulChatPair(val userText: String, val assistantText: String)

@Dao
interface ChatDao {
    @Insert fun insertConversation(conversation: ConversationEntity)
    @Insert fun insertOperation(operation: ChatOperationEntity)
    @Insert fun insertMessage(message: ChatMessageEntity): Long
    @Insert fun insertMetadata(metadata: ChatMetadataEntity)

    @Query("SELECT * FROM conversation ORDER BY updatedAt DESC, id DESC LIMIT :limit OFFSET :offset")
    fun conversations(offset: Int, limit: Int): List<ConversationEntity>

    @Query("SELECT * FROM conversation WHERE id = :id")
    fun conversation(id: String): ConversationEntity?

    @Query("UPDATE conversation SET updatedAt = MAX(updatedAt, :ts) WHERE id = :id")
    fun touchConversation(id: String, ts: Long)

    @Query("UPDATE conversation SET title = :title, updatedAt = MAX(updatedAt, :ts) WHERE id = :id")
    fun nameConversation(id: String, title: String, ts: Long)

    @Query("SELECT COUNT(*) FROM message WHERE conversationId = :conversationId")
    fun messageCount(conversationId: String): Int

    @Query("SELECT * FROM operation WHERE id = :id")
    fun operation(id: String): ChatOperationEntity?

    @Query("SELECT * FROM operation WHERE conversationId = :conversationId AND state = 'RUNNING'")
    fun runningOperation(conversationId: String): ChatOperationEntity?

    @Query("SELECT * FROM message WHERE operationId = :operationId ORDER BY id")
    fun operationMessages(operationId: String): List<ChatMessageEntity>

    @Query("""
        UPDATE operation SET state = :state, finishedAt = :ts, runningConversationId = NULL
        WHERE id = :id AND conversationId = :conversationId AND state = 'RUNNING'
          AND runningConversationId = :conversationId
    """)
    fun finishOperation(id: String, conversationId: String, state: String, ts: Long): Int

    @Query("""
        UPDATE operation SET state = 'INTERRUPTED', finishedAt = :ts, runningConversationId = NULL
        WHERE state = 'RUNNING'
    """)
    fun recoverPending(ts: Long): Int

    @Query("""
        SELECT m.id, m.role, m.text, m.engine, m.ts, COALESCE(o.state, 'LEGACY') AS state
        FROM message m LEFT JOIN operation o ON o.id = m.operationId
        WHERE m.conversationId = :conversationId AND (:beforeId IS NULL OR m.id < :beforeId)
        ORDER BY m.id DESC LIMIT :limit
    """)
    fun messages(conversationId: String, beforeId: Long?, limit: Int): List<StoredChatMessage>

    @Query("""
        SELECT u.text AS userText, a.text AS assistantText
        FROM operation o
        INNER JOIN message u ON u.operationId = o.id AND u.conversationId = o.conversationId AND u.role = 'user'
        INNER JOIN message a ON a.operationId = o.id AND a.conversationId = o.conversationId AND a.role = 'assistant'
        WHERE o.conversationId = :conversationId AND o.state = 'SUCCEEDED'
        ORDER BY u.id ASC LIMIT :limit
    """)
    fun successfulPairs(conversationId: String, limit: Int): List<SuccessfulChatPair>

    @Query("SELECT * FROM metadata WHERE `key` = :key")
    fun metadata(key: String): ChatMetadataEntity?

    @Query("DELETE FROM conversation WHERE id = :id AND legacy = 0")
    fun deleteConversationRow(id: String): Int
}

@Database(
    entities = [ConversationEntity::class, ChatMessageEntity::class,
        ChatOperationEntity::class, ChatMetadataEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class ChatDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
}
