package cc.fplj.magpiemobile.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * 流式聊天（plan.md §3 聊天测试「流式」的 HTTP 层，配合 SseChatParser）。
 *
 * 契约：POST /v1/chat/completions {"stream":true}；SSE 按行喂 SseChatParser；
 * [DONE] 正常结束；HTTP 错误/无 body 容错与 ChatClient 相同；断流保留已收增量
 * 并抛 ChatException（调用方决定 UI 呈现，plan.md §5）。
 */
class StreamingChatClient(private val config: GatewayConfig) {

    private val json = Json { ignoreUnknownKeys = true }

    /** 流式一轮：onDelta 每增量回调一次；返回完整拼接文本。 */
    fun stream(
        model: String,
        messages: List<ChatClient.Message>,
        onDelta: (String) -> Unit,
    ): String {
        val body = buildJsonObject {
            put("model", model)
            put("messages", json.encodeToJsonElement(
                kotlinx.serialization.builtins.ListSerializer(ChatClient.Message.serializer()), messages))
            put("stream", true)
        }.toString()

        val conn = URL(config.chatCompletionsPath()).openConnection() as HttpURLConnection
        conn.requestMethod = "POST"
        conn.connectTimeout = config.connectTimeout.toMillis().toInt()
        conn.readTimeout = config.readTimeout.toMillis().toInt()
        conn.doOutput = true
        conn.setRequestProperty("Content-Type", "application/json")
        conn.setRequestProperty("Authorization", "Bearer " + config.gatewayKey)
        conn.setRequestProperty("Accept", "text/event-stream")
        try {
            conn.outputStream.use { it.write(body.toByteArray()) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            if (code !in 200..299) {
                val errText = stream?.bufferedReader()?.readText().orEmpty()
                throw ChatException("stream -> HTTP " + code + (if (errText.isNotEmpty()) ": " + errText.take(200) else ""), code)
            }
            val parser = SseChatParser(onDelta)
            val reader: BufferedReader = stream!!.bufferedReader()
            var line: String? = reader.readLine()
            var done = false
            while (line != null && !done) {
                done = parser.feedLine(line)
                line = reader.readLine()
            }
            // 服务器未发 [DONE] 即断流：保留已收文本（SseChatParser.fullText），由调用方呈报
            return parser.fullText()
        } finally {
            conn.disconnect()
        }
    }
}
