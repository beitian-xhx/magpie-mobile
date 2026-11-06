package cc.fplj.magpiemobile.core

import kotlinx.serialization.json.Json
import java.net.HttpURLConnection
import java.net.URL

/**
 * 管理面用量客户端：GET /api/usage/requests（仪表盘"近期请求"数据源）。
 *
 * 契约（internal/gui/usage.go，plan.md §3 Usage 行）：
 * 返回形状上游未钉死为稳定 schema——本客户端返回原始 JSON 文本，
 * 由上层按需解析；只钉状态码语义与鉴权（管理 cookie，非网关 key）。
 *
 * 注意两套凭据绝不混用：管理面走 adminCookie（webGuard 签发），
 * 网关走 gatewayKey——此约束由参数类型强制（见 request 方法签名）。
 */
class UsageClient(private val config: GatewayConfig) {

    private val json = Json { ignoreUnknownKeys = true }

    /** 近期请求：limit 上限保守取 50（仪表盘只显示 5，导出走专门通道）。 */
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
}

class UsageException(message: String, val httpCode: Int) : RuntimeException(message)
