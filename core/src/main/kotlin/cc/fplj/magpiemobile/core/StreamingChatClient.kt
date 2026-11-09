package cc.fplj.magpiemobile.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * 流式聊天的 HTTP 层；SSE 事件语义交给 SseChatParser。
 *
 * 只有收到 [DONE] 才成功；EOF/IO/SSE/回调错误保留增量并安全上抛，不自动重发。
 * 仅真正的非 2xx HTTP 响应抛 ChatException；回调不得伪造不支持流式的状态。
 */
class StreamingChatClient(private val config: GatewayConfig) {

    private val json = Json { ignoreUnknownKeys = true }

    /** 流式一轮：onDelta 每增量回调一次；成功时返回完整拼接文本。 */
    fun stream(
        model: String,
        messages: List<ChatClient.Message>,
        onDelta: (String) -> Unit = {},
        maxTokens: Int? = null,
        temperature: Double? = null,
        topP: Double? = null,
    ): String {
        val body = buildJsonObject {
            put("model", model)
            put("messages", json.encodeToJsonElement(
                kotlinx.serialization.builtins.ListSerializer(ChatClient.Message.serializer()), messages))
            put("stream", true)
            if (maxTokens != null) put("max_tokens", maxTokens)
            if (temperature != null) put("temperature", temperature)
            if (topP != null) put("top_p", topP)
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
                throw StreamingChatException(StreamingChatFailure.UNEXPECTED_CONTENT_TYPE, parser.fullText(), code)
            }
            try {
                connection.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                    while (true) {
                        val line = reader.readLine()
                            ?: throw StreamingChatException(StreamingChatFailure.EOF_BEFORE_DONE, parser.fullText(), code)
                        // 必须先判断 DONE 再读下一行；长连接不需要关闭才算完成。
                        if (parser.feedLine(line)) break
                    }
                }
            } catch (_: ChatException) {
                // SSE error 不属于 HTTP 405/501；不保留原始 JSON/message/cause。
                throw StreamingChatException(StreamingChatFailure.SSE_ERROR, parser.fullText(), code)
            } catch (e: StreamingChatException) {
                // parser 的回调失败使用真实响应 code，不采用回调自报的 HTTP 状态。
                throw StreamingChatException(e.failure, parser.fullText(), code)
            }
            return parser.fullText()
        } catch (_: IOException) {
            // Throwable cause/类型名也可能夹带自由文本，安全边界只保留固定分类。
            throw StreamingChatException(StreamingChatFailure.IO_FAILURE, parser.fullText(), httpCode)
        } finally {
            conn?.disconnect()
        }
    }
}

/** 固定异常分类；不得将上游 message、JSON、provider key 或原始 cause 加入诊断。 */
enum class StreamingChatFailure(val safeMessage: String) {
    SSE_ERROR("Stream SSE error"),
    CALLBACK_FAILURE("Stream callback failure"),
    IO_FAILURE("Stream I/O failure"),
    EOF_BEFORE_DONE("Stream EOF before [DONE]"),
    UNEXPECTED_CONTENT_TYPE("Stream expected text/event-stream"),
    CHAT_FAILURE("Chat failed"),
}

/** 失败回答只留在内存供 UI 展示；异常 message/日志不包含 partialText 或原始 cause。 */
class StreamingChatException(
    val failure: StreamingChatFailure,
    val partialText: String,
    val httpCode: Int? = null,
) : RuntimeException(failure.safeMessage)
