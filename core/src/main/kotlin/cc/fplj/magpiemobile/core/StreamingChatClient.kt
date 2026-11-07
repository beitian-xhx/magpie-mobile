package cc.fplj.magpiemobile.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 流式聊天的 HTTP 层；真实 SSE 事件语义交给既有 SseChatParser。
 *
 * 只有收到 [DONE] 才成功；EOF/IO/SSE 错误保留增量并以失败上抛，不自动重发。
 * 非 2xx 状态在读取任何成功响应增量之前抛 ChatException，供会话严格判断降级。
 */
class StreamingChatClient(private val config: GatewayConfig) {

    private val json = Json { ignoreUnknownKeys = true }

    /** 流式一轮：onDelta 每增量回调一次；成功时返回完整拼接文本。 */
    fun stream(
        model: String,
        messages: List<ChatClient.Message>,
        onDelta: (String) -> Unit = {},
    ): String {
        val body = buildJsonObject {
            put("model", model)
            put("messages", json.encodeToJsonElement(
                kotlinx.serialization.builtins.ListSerializer(ChatClient.Message.serializer()), messages))
            put("stream", true)
        }.toString()
        val parser = SseChatParser(onDelta)
        var conn: HttpURLConnection? = null
        var httpCode: Int? = null
        try {
            val connection = URL(config.chatCompletionsPath()).openConnection(java.net.Proxy.NO_PROXY) as HttpURLConnection
            conn = connection
            connection.requestMethod = "POST"
            connection.connectTimeout = config.connectTimeout.toMillis().toInt()
            connection.readTimeout = config.readTimeout.toMillis().toInt()
            connection.doOutput = true
            connection.instanceFollowRedirects = false
            connection.setRequestProperty("Content-Type", "application/json")
            connection.setRequestProperty("Authorization", "Bearer " + config.gatewayKey)
            connection.setRequestProperty("Accept", "text/event-stream")
            // 定长传输禁止 HttpURLConnection 在认证/重定向时隐式重发这个计费 POST。
            val bytes = body.toByteArray(Charsets.UTF_8)
            connection.setFixedLengthStreamingMode(bytes.size)
            connection.outputStream.use { it.write(bytes) }
            val code = connection.responseCode
            httpCode = code
            if (code !in 200..299) {
                // 不读取错误 body：状态足够判定，且错误 body 可能含凭据或保持长连接。
                throw ChatException("stream -> HTTP " + code, code)
            }
            val mediaType = connection.contentType?.substringBefore(';')?.trim()
            if (!mediaType.equals("text/event-stream", ignoreCase = true)) {
                throw StreamingChatException("stream -> expected text/event-stream", parser.fullText(), code)
            }
            try {
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    while (true) {
                        val line = reader.readLine()
                            ?: throw StreamingChatException("stream -> EOF before [DONE]", parser.fullText(), code)
                        // 必须先判断 DONE 再读下一行；长连接不需要关闭才算完成。
                        if (parser.feedLine(line)) break
                    }
                }
            } catch (e: ChatException) {
                // SSE error 或增量回调错误不是“不支持流式”，即使其 code=405/501 也不能重发。
                val safeMessage = SecretRedactor.redact(
                    (e.message ?: "stream -> SSE error").replace(config.gatewayKey, "[redacted]"))
                throw StreamingChatException(safeMessage, parser.fullText(), code, e)
            }
            return parser.fullText()
        } catch (e: IOException) {
            throw StreamingChatException("stream -> I/O failure: " + e.javaClass.simpleName,
                parser.fullText(), httpCode, e)
        } finally {
            conn?.disconnect()
        }
    }
}

/** ChatException 是 final；独立异常保留失败回答，调用方不得当作成功 assistant。 */
class StreamingChatException(
    message: String,
    val partialText: String,
    val httpCode: Int? = null,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
