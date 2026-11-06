package cc.fplj.magpiemobile.core

import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.net.HttpURLConnection
import java.net.URL

/**
 * 应用内聊天测试客户端（plan.md §3「聊天测试（新增）」的传输层）。
 *
 * 契约：OpenAI Chat Completions（/v1/chat/completions，plan.md §4.2）。
 * 模型列表必须从 GET /v1/models 动态读取（plan.md §4.2：从列表读，不内置）。
 * 本客户端只做非流式请求；SSE 流式由上层用同一端点 + "stream":true 处理。
 *
 * 安全：gatewayKey 来自 GatewayConfig（构造期已强制非空）；
 * 本类不记录请求体/响应体（可能含用户对话内容，plan.md §4.3）。
 */
class ChatClient(private val config: GatewayConfig) {

    private val json = Json { ignoreUnknownKeys = true }

    /** 非流式一轮对话。 */
    fun complete(model: String, messages: List<Message>, maxTokens: Int? = null): String {
        val body = buildJsonObject {
            put("model", model)
            put("messages", json.encodeToJsonElement(ListSerializer(Message.serializer()), messages))
            if (maxTokens != null) put("max_tokens", maxTokens)
        }.toString()

        val conn = URL(config.chatCompletionsPath()).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = config.connectTimeout.toMillis().toInt()
        conn.readTimeout = config.readTimeout.toMillis().toInt()
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer " + config.gatewayKey)
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val text = (if (code in 200..299) conn.inputStream else conn.errorStream)
                .bufferedReader().readText()
            if (code !in 200..299) throw ChatException("chat -> HTTP " + code, code)
            // 宽松返回原始 JSON：choices 形状上游未钉死（plan.md §4.2），由上层按需取字段
            return text
        } finally {
            conn.disconnect()
        }
    }

    @Serializable
    data class Message(val role: String, val content: String)
}

class ChatException(message: String, val httpCode: Int) : RuntimeException(message)
