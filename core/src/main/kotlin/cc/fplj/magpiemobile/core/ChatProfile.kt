package cc.fplj.magpiemobile.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Chat Profile and Agent Parameter Configuration (P3-1/P3-2).
 * Controls system prompt injection, sampling temperature, top_p, and model routing.
 */
@Serializable
data class ChatProfile(
    val id: String,
    val name: String,
    val systemPrompt: String = "",
    val temperature: Double = 0.7,
    val topP: Double = 1.0,
    val maxTokens: Int? = null,
    val modelAlias: String = "",
) {
    init {
        require(ID_REGEX.matches(id)) { "Profile ID 格式无效: $id" }
        require(name.isNotBlank() && name.length <= 128) { "Profile 名称必须为 1..128 字符" }
        require(systemPrompt.length <= 32768) { "System Prompt 长度超出上限 (32KB)" }
        require(temperature in 0.0..2.0) { "Temperature 必须在 0.0 .. 2.0 之间" }
        require(topP in 0.0..1.0) { "Top-P 必须在 0.0 .. 1.0 之间" }
        require(maxTokens == null || maxTokens in 1..131072) { "Max Tokens 必须在 1..131072 之间" }
    }

    companion object {
        private val ID_REGEX = Regex("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$")

        val DEFAULT = ChatProfile(
            id = "default",
            name = "通用助手",
            systemPrompt = "You are a helpful and concise AI assistant.",
            temperature = 0.7,
            topP = 1.0
        )
    }
}

/**
 * Manages persisted Chat Profiles safely in app-private storage.
 */
class ChatProfileStore(private val storageFile: File) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val lock = Any()

    fun list(): List<ChatProfile> = synchronized(lock) {
        if (!storageFile.exists()) return listOf(ChatProfile.DEFAULT)
        try {
            val list = json.decodeFromString<List<ChatProfile>>(storageFile.readText(Charsets.UTF_8))
            if (list.none { it.id == ChatProfile.DEFAULT.id }) {
                listOf(ChatProfile.DEFAULT) + list
            } else list
        } catch (_: Exception) {
            listOf(ChatProfile.DEFAULT)
        }
    }

    fun save(profile: ChatProfile) = synchronized(lock) {
        val current = list().filter { it.id != profile.id }
        val updated = current + profile
        storageFile.parentFile?.mkdirs()
        storageFile.writeText(json.encodeToString(updated), Charsets.UTF_8)
    }

    fun delete(id: String): Boolean = synchronized(lock) {
        if (id == ChatProfile.DEFAULT.id) return false // Default profile cannot be deleted
        val current = list()
        val filtered = current.filter { it.id != id }
        if (filtered.size == current.size) return false
        storageFile.parentFile?.mkdirs()
        storageFile.writeText(json.encodeToString(filtered), Charsets.UTF_8)
        true
    }
}
