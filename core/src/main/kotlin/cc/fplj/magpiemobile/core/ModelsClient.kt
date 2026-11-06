package cc.fplj.magpiemobile.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.net.HttpURLConnection
import java.net.URL

/**
 * 模型目录客户端：GET /v1/models（plan.md §4.2：必须动态读取，不内置列表）。
 *
 * 契约（上游 integrate 文档钉住）：data[] 每项含 id、display_name、owned_by；
 * reasoning_levels / context_window / modalities 已知时存在、未知时缺省——
 * 全部按"存在才解析"处理，缺失记为 null 而不是猜测默认值（plan.md：价格未知显示未知）。
 */
class ModelsClient(private val config: GatewayConfig) {

    private val json = Json { ignoreUnknownKeys = true }

    fun list(): List<ModelEntry> {
        val conn = URL(config.modelsPath()).openConnection() as HttpURLConnection
        conn.connectTimeout = config.connectTimeout.toMillis().toInt()
        conn.readTimeout = config.readTimeout.toMillis().toInt()
        conn.setRequestProperty("Authorization", "Bearer " + config.gatewayKey)
        try {
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                ?.bufferedReader()?.readText().orEmpty()
            if (code !in 200..299) throw ModelsException("models -> HTTP " + code, code)
            val root = json.parseToJsonElement(text).jsonObject
            val data = root["data"] as? JsonArray ?: throw ModelsException("models: missing data[]", code)
            return data.map { el ->
                val o = el.jsonObject
                ModelEntry(
                    id = o["id"]?.jsonPrimitive?.content ?: "",
                    displayName = o["display_name"]?.jsonPrimitive?.content,
                    ownedBy = o["owned_by"]?.jsonPrimitive?.content,
                    contextWindow = o["context_window"]?.jsonPrimitive?.content?.toLongOrNull(),
                )
            }.filter { it.id.isNotBlank() }
        } finally {
            conn.disconnect()
        }
    }
}

data class ModelEntry(
    val id: String,
    val displayName: String?,
    val ownedBy: String?,
    val contextWindow: Long?,
)

class ModelsException(message: String, val httpCode: Int) : RuntimeException(message)
