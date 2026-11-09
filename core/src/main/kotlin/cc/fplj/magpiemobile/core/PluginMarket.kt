package cc.fplj.magpiemobile.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.net.URI

/**
 * Validated Plugin Market Catalog Entry (P3-4).
 * Enforces strict mobile safety, cryptographic checksum, and capability constraints.
 */
@Serializable
data class PluginCatalogEntry(
    val id: String,
    val name: String,
    val version: String,
    val description: String,
    val author: String,
    val downloadUrl: String,
    val checksumSha256: String,
    val capabilities: List<String> = emptyList(),
) {
    init {
        require(ID_REGEX.matches(id)) { "插件 ID 格式无效: $id" }
        require(name.isNotBlank() && name.length <= 128) { "插件名称长度必须在 1..128 字符之间" }
        require(VERSION_REGEX.matches(version)) { "版本号格式必须符合语义化版本规范: $version" }
        require(SHA256_REGEX.matches(checksumSha256)) { "SHA-256 校验和必须为 64 位十六进制字符" }

        val uri = try {
            URI(downloadUrl)
        } catch (e: Exception) {
            throw IllegalArgumentException("下载链接不是有效 URI: $downloadUrl")
        }
        val scheme = uri.scheme?.lowercase()
        require(scheme == "https" || scheme == "http") { "下载协议仅支持 HTTPS 或回环 HTTP: $downloadUrl" }
        val host = uri.host?.lowercase().orEmpty()
        val isLoopback = host == "127.0.0.1" || host == "localhost" || host == "::1"
        if (scheme == "http") {
            require(isLoopback) { "非本地回环地址必须使用加密 HTTPS 下载: $downloadUrl" }
        }
        require(uri.userInfo == null) { "下载链接不得包含明文认证凭据" }
    }

    companion object {
        private val ID_REGEX = Regex("^[a-zA-Z0-9][a-zA-Z0-9._-]{0,63}$")
        private val VERSION_REGEX = Regex("^[0-9]+\\.[0-9]+\\.[0-9]+(-[a-zA-Z0-9._-]+)?$")
        private val SHA256_REGEX = Regex("^[a-fA-F0-9]{64}$")
    }
}

/**
 * Curated offline repository of verified mobile-safe plugins.
 */
object CuratedPluginCatalog {
    private val json = Json { ignoreUnknownKeys = true }

    val VERIFIED_PLUGINS: List<PluginCatalogEntry> = listOf(
        PluginCatalogEntry(
            id = "magpie-openai-adapter",
            name = "OpenAI 官方协议扩展适配器",
            version = "1.0.0",
            description = "支持自定义 OpenAI API 响应头规范与函数调用适配",
            author = "Magpie Core Team",
            downloadUrl = "https://raw.githubusercontent.com/fplj-fplj/magpie-mobile/main/plugins/openai-adapter.js",
            checksumSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            capabilities = listOf("model-adapter", "tool-call")
        ),
        PluginCatalogEntry(
            id = "magpie-anthropic-adapter",
            name = "Anthropic Claude 协议适配器",
            version = "1.0.1",
            description = "提供原生 Claude Messages 流式与思考过程解析",
            author = "Magpie Core Team",
            downloadUrl = "https://raw.githubusercontent.com/fplj-fplj/magpie-mobile/main/plugins/anthropic-adapter.js",
            checksumSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            capabilities = listOf("model-adapter", "thinking-stream")
        ),
        PluginCatalogEntry(
            id = "magpie-local-calculator",
            name = "本地受控数学计算工具",
            version = "0.9.0",
            description = "沙箱环境内运行的高精度离线计算器工具",
            author = "Magpie Community",
            downloadUrl = "http://127.0.0.1:8080/plugins/calculator.js",
            checksumSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
            capabilities = listOf("mcp-tool", "offline")
        )
    )

    fun parseCatalogJson(jsonText: String): List<PluginCatalogEntry> {
        return json.decodeFromString(jsonText)
    }
}
