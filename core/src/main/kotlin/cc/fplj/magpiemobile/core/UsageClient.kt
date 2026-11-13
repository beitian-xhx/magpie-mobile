package cc.fplj.magpiemobile.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull
import java.net.HttpURLConnection
import java.net.URL

@Serializable
data class UsageRecord(
    val id: String = "",
    val timestamp: String = "",
    val model: String = "default",
    val provider: String = "",
    val statusCode: Int = 200,
    val durationMs: Long = 0,
    val totalTokens: Int = 0,
    val inputTokens: Long = 0, val outputTokens: Long = 0,
    val cacheRead: Long = 0, val cacheWrite: Long = 0,
    val reasoningTokens: Long = 0, val ttftMs: Long = 0, val firstTextMs: Long = 0,
    val sentMs: Long = 0, val queuedMs: Long = 0, val retriedMs: Long = 0,
    val error: String = "", val cost: Double? = null, val canceled: Boolean = false,
)

/**
 * 管理面用量客户端：GET /api/usage/requests（仪表盘"近期请求"数据源）。
 *
 * 契约（internal/gui/usage.go，plan.md §3 Usage 行）：
 * 返回形状上游未钉死为稳定 schema——本客户端返回原始 JSON 文本，
 * 并提供弹性安全的指标解析函数；只钉状态码语义与鉴权（管理 cookie，非网关 key）。
 *
 * 注意两套凭据绝不混用：管理面走 adminCookie（webGuard 签发），
 * 网关走 gatewayKey——此约束由参数类型强制（见 request 方法签名）。
 */
class UsageClient(private val config: GatewayConfig) {

    private val json = Json { ignoreUnknownKeys = true }

    /** 近期请求原始 JSON：limit 上限保守取 50（仪表盘只显示 5，导出走专门通道）。 */
    fun recentRequests(limit: Int = 5): String {
        require(limit in 1..50) { "limit 必须在 1..50" }
        val cookie = config.adminCookie
            ?: throw UsageException("管理 cookie 缺失：管理面请求必须带 webGuard 签发的 cookie", 0)
        val conn = URL(config.adminBaseUrl + "/api/usage/requests?limit=" + limit)
            .openConnection() as HttpURLConnection
        conn.connectTimeout = config.connectTimeout.toMillis().toInt()
        conn.readTimeout = config.readTimeout.toMillis().toInt()
        conn.setRequestProperty("Cookie", "magpie_web_3430=" + cookie)
        try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) throw UsageException("usage -> HTTP " + code, code)
            return text
        } finally {
            conn.disconnect()
        }
    }

    /** 结构化解析近期请求记录 */
    fun recentRecords(limit: Int = 5): List<UsageRecord> {
        val raw = recentRequests(limit)
        return parseUsageRecords(raw)
    }

    /**
     * 只读用量洞察端点（P5-4）：/api/usage/heatmap、/api/usage/quotas、/api/usage/quotas/history。
     * 上游形状未钉死为稳定 schema，返回原始 JSON 文本，UI 侧做弹性解析；
     * 响应硬上限 2MB，管理 cookie 鉴权与 recentRequests 相同。
     */
    fun usageInsight(endpoint: UsageInsightEndpoint): String {
        val cookie = config.adminCookie
            ?: throw UsageException("管理 cookie 缺失：管理面请求必须带 webGuard 签发的 cookie", 0)
        val conn = URL(config.adminBaseUrl + endpoint.path)
            .openConnection() as HttpURLConnection
        conn.connectTimeout = config.connectTimeout.toMillis().toInt()
        conn.readTimeout = config.readTimeout.toMillis().toInt()
        conn.setRequestProperty("Cookie", "magpie_web_3430=" + cookie)
        try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.use { reader -> reader.readText().take(MAX_INSIGHT_CHARS) }.orEmpty()
            if (code !in 200..299) throw UsageException("usage insight -> HTTP " + code, code)
            return text
        } finally {
            conn.disconnect()
        }
    }

    companion object {
        fun parseUsageRecords(jsonText: String): List<UsageRecord> {
            if (jsonText.isBlank()) return emptyList()
            val element = try {
                Json.parseToJsonElement(jsonText)
            } catch (_: Exception) {
                return emptyList()
            }
            val array = when (element) {
                is JsonArray -> element
                is JsonObject -> {
                    val reqs = element["rows"] ?: element["requests"] ?: element["data"] ?: element["items"]
                    reqs as? JsonArray ?: JsonArray(emptyList())
                }
                else -> JsonArray(emptyList())
            }
            return array.mapNotNull { item ->
                val obj = item as? JsonObject ?: return@mapNotNull null
                val id = (obj["id"] ?: obj["rid"])?.jsonPrimitive?.contentOrNull.orEmpty()
                val timestamp = obj["timestamp"]?.jsonPrimitive?.contentOrNull
                    ?: obj["t"]?.jsonPrimitive?.contentOrNull
                    ?: obj["time"]?.jsonPrimitive?.contentOrNull
                    ?: obj["created_at"]?.jsonPrimitive?.contentOrNull
                    ?: ""
                val model = obj["model"]?.jsonPrimitive?.contentOrNull
                    ?: obj["model_id"]?.jsonPrimitive?.contentOrNull
                    ?: "unknown"
                val provider = obj["provider"]?.jsonPrimitive?.contentOrNull
                    ?: obj["provider_id"]?.jsonPrimitive?.contentOrNull
                    ?: ""
                val statusCode = obj["status"]?.jsonPrimitive?.intOrNull
                    ?: obj["statusCode"]?.jsonPrimitive?.intOrNull
                    ?: obj["code"]?.jsonPrimitive?.intOrNull
                    ?: 200
                val durationMs = obj["duration"]?.jsonPrimitive?.longOrNull
                    ?: obj["durationMs"]?.jsonPrimitive?.longOrNull
                    ?: obj["latency"]?.jsonPrimitive?.longOrNull
                    ?: obj["ms"]?.jsonPrimitive?.longOrNull
                    ?: 0L
                val tokens = obj["total_tokens"]?.jsonPrimitive?.intOrNull
                    ?: obj["tokens"]?.let { tok ->
                        if (tok is JsonObject) {
                            tok["total"]?.jsonPrimitive?.intOrNull
                                ?: tok["total_tokens"]?.jsonPrimitive?.intOrNull
                        } else {
                            tok.jsonPrimitive.intOrNull
                        }
                    } ?: listOf("in", "out", "cache_read", "cache_write").sumOf { obj[it]?.jsonPrimitive?.intOrNull ?: 0 }
                UsageRecord(
                    id = id,
                    timestamp = timestamp,
                    model = model,
                    provider = provider,
                    statusCode = statusCode,
                    durationMs = durationMs,
                    totalTokens = tokens,
                    inputTokens = obj["in"]?.jsonPrimitive?.longOrNull ?: 0,
                    outputTokens = obj["out"]?.jsonPrimitive?.longOrNull ?: 0,
                    cacheRead = obj["cache_read"]?.jsonPrimitive?.longOrNull ?: 0,
                    cacheWrite = obj["cache_write"]?.jsonPrimitive?.longOrNull ?: 0,
                    reasoningTokens = obj["reasoning"]?.jsonPrimitive?.longOrNull ?: 0,
                    ttftMs = obj["ttft_ms"]?.jsonPrimitive?.longOrNull ?: 0,
                    firstTextMs = obj["first_text_ms"]?.jsonPrimitive?.longOrNull ?: 0,
                    sentMs = obj["sent_ms"]?.jsonPrimitive?.longOrNull ?: 0,
                    queuedMs = obj["queued_ms"]?.jsonPrimitive?.longOrNull ?: 0,
                    retriedMs = obj["retried_ms"]?.jsonPrimitive?.longOrNull ?: 0,
                    error = SecretRedactor.redact((obj["err"] ?: obj["error"])?.jsonPrimitive?.contentOrNull.orEmpty()),
                    cost = if (obj["priced"]?.jsonPrimitive?.contentOrNull == "true") obj["cost"]?.jsonPrimitive?.doubleOrNull else null,
                    canceled = obj["canceled"]?.jsonPrimitive?.contentOrNull == "true" ||
                        (statusCode == 499 && obj["err_type"]?.jsonPrimitive?.contentOrNull == "agent_canceled"),
                )
            }
        }
    }
}

class UsageException(message: String, val httpCode: Int) : RuntimeException(message)

/** 白名单化只读洞察端点：精确路径，非前缀。 */
enum class UsageInsightEndpoint(internal val path: String) {
    HEATMAP("/api/usage/heatmap"),
    QUOTAS("/api/usage/quotas"),
    QUOTAS_HISTORY("/api/usage/quotas/history"),
}

/** 单次洞察响应的安全上限（2M 字符）。 */
const val MAX_INSIGHT_CHARS: Int = 2_000_000
