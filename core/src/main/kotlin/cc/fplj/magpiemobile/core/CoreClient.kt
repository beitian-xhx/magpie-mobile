package cc.fplj.magpiemobile.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration

/**
 * Magpie 核心（loopback 管理 API）的 Kotlin 客户端。
 *
 * 契约基线：Magpie v0.1.3188 / commit 54dc257f（internal/gui/api.go）。
 * 上游无稳定 OpenAPI：字段解析一律宽松（忽略未知字段），仅在
 * [hello] 这类被官方文档明确钉住的形状上做强校验。
 */
class CoreClient(
    private val baseUrl: String = "http://127.0.0.1:3430",
    private val timeout: Duration = Duration.ofSeconds(5),
) {
    private val json = Json { ignoreUnknownKeys = true }

    /** GET /api/hello —— 官方文档钉住的探测端点，返回 {"name":"magpie","version":"…"} */
    fun hello(): Hello {
        val body = get("api/hello")
        return json.decodeFromString(Hello.serializer(), body)
    }

    /** GET /api/state —— 管理页主状态。上游字段未完全钉住，返回原始 JsonObject 由上层按需读取。 */
    fun state(): JsonObject = json.parseToJsonElement(get("api/state")).jsonObject

    private fun get(path: String): String {
        val conn = URL("$baseUrl/$path").openConnection() as HttpURLConnection
        conn.connectTimeout = timeout.toMillis().toInt()
        conn.readTimeout = timeout.toMillis().toInt()
        try {
            val code = conn.responseCode
            if (code != 200) throw CoreException("GET /$path -> HTTP $code")
            return conn.inputStream.bufferedReader().readText()
        } finally {
            conn.disconnect()
        }
    }
}

@kotlinx.serialization.Serializable
data class Hello(
    val name: String,
    val version: String,
)

class CoreException(message: String) : RuntimeException(message)
