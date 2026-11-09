package cc.fplj.magpiemobile.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.net.URI

enum class McpTransport {
    HTTP,
    SSE,
    STDIO,
}

/**
 * Model Context Protocol (MCP) Server Configuration (P3-3).
 * Represents a registered tool provider endpoint with strict mobile safety constraints.
 */
@Serializable
data class McpServerConfig(
    val id: String,
    val name: String,
    val transport: McpTransport,
    val endpoint: String,
    val enabled: Boolean = true,
    val description: String = "",
) {
    init {
        require(ID_REGEX.matches(id)) { "MCP Server ID 格式无效: $id" }
        require(name.isNotBlank() && name.length <= 128) { "MCP Server 名称必须为 1..128 字符" }
        require(description.length <= 1024) { "描述长度超出上限 (1024 字符)" }

        when (transport) {
            McpTransport.HTTP, McpTransport.SSE -> {
                val uri = try {
                    URI(endpoint)
                } catch (e: Exception) {
                    throw IllegalArgumentException("无效的端点 URI: $endpoint")
                }
                val scheme = uri.scheme?.lowercase()
                require(scheme == "http" || scheme == "https") {
                    "HTTP/SSE MCP 传输仅支持 http 或 https 协议: $endpoint"
                }
                // 安全约束：非环回（127.0.0.1 / localhost）禁止明文 HTTP
                val host = uri.host?.lowercase().orEmpty()
                val isLoopback = host == "127.0.0.1" || host == "localhost" || host == "::1"
                if (scheme == "http") {
                    require(isLoopback) {
                        "非回环地址必须使用加密 HTTPS 连接: $endpoint"
                    }
                }
                // 凭据脱敏防护：禁止在端点 URL 中携带明文用户信息或查询凭据
                require(uri.userInfo == null) {
                    "MCP 端点 URL 不得包含明文 userinfo 认证信息"
                }
                val query = uri.query.orEmpty().lowercase()
                require(!query.contains("api_key") && !query.contains("token") && !query.contains("secret")) {
                    "MCP 端点 URL 不得在查询参数中泄露 API Key 或 Secret"
                }
            }
            McpTransport.STDIO -> {
                // 安全约束：Android W^X 规则下仅允许预置安全载荷路径，禁止任意 shell 命令
                require(endpoint.startsWith("/") && !endpoint.contains("..")) {
                    "STDIO 载荷必须为绝对路径且不得包含相对路径穿越: $endpoint"
                }
            }
        }
    }

    companion object {
        private val ID_REGEX = Regex("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$")
    }
}

/**
 * Manages persisted MCP Server registrations in app-private storage.
 */
class McpServerStore(private val storageFile: File) {

    private val json = Json { ignoreUnknownKeys = true; prettyPrint = true }
    private val lock = Any()

    fun list(): List<McpServerConfig> = synchronized(lock) {
        if (!storageFile.exists()) return emptyList()
        try {
            json.decodeFromString<List<McpServerConfig>>(storageFile.readText(Charsets.UTF_8))
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun save(server: McpServerConfig) = synchronized(lock) {
        val current = list().filter { it.id != server.id }
        val updated = current + server
        storageFile.parentFile?.mkdirs()
        storageFile.writeText(json.encodeToString(updated), Charsets.UTF_8)
    }

    fun delete(id: String): Boolean = synchronized(lock) {
        val current = list()
        val filtered = current.filter { it.id != id }
        if (filtered.size == current.size) return false
        storageFile.parentFile?.mkdirs()
        storageFile.writeText(json.encodeToString(filtered), Charsets.UTF_8)
        true
    }

    fun toggleEnabled(id: String): Boolean = synchronized(lock) {
        val current = list()
        val target = current.firstOrNull { it.id == id } ?: return false
        val updated = current.filter { it.id != id } + target.copy(enabled = !target.enabled)
        storageFile.parentFile?.mkdirs()
        storageFile.writeText(json.encodeToString(updated), Charsets.UTF_8)
        true
    }
}
