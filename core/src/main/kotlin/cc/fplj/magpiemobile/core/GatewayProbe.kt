package cc.fplj.magpiemobile.core

import kotlinx.serialization.json.*
import java.net.HttpURLConnection
import java.net.Proxy
import java.net.URL
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** A diagnostic only: a complete streamed reply, not an HTTP header or role event. */
class GatewayProbe(
    private val baseUrl: String = "http://127.0.0.1:3425",
    private val deadlineMs: Long = 120_000,
) {
    data class Attempt(val ok: Boolean, val status: Int, val millis: Long, val firstContentMs: Long?, val firstTextMs: Long?, val text: String, val error: String)
    @Volatile private var active: HttpURLConnection? = null
    @Volatile private var canceled = false
    fun cancel() { canceled = true; active?.disconnect() }

    fun run(key: String, model: String, attempts: Int = 3, onStage: (String) -> Unit = {}): List<Attempt> {
        require(attempts in 1..3)
        canceled = false
        val out = mutableListOf<Attempt>()
        repeat(attempts) { index ->
            if (canceled) return out
            onStage("第 ${index + 1}/$attempts 次：等待真实输出与完整结束…")
            val result = once(key, model)
            out += result
            onStage(if (result.ok) "第 ${index + 1} 次完成 · 首内容 ${result.firstContentMs}ms · 首正文 ${result.firstTextMs ?: "—"}ms · 总计 ${result.millis}ms" else "第 ${index + 1} 次失败：${result.error}")
        }
        return out
    }

    fun once(key: String, model: String): Attempt {
        val start = System.nanoTime()
        fun elapsed() = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)
        var status = 0
        var first: Long? = null
        var firstText: Long? = null
        var finished = false
        val preview = StringBuilder()
        var conn: HttpURLConnection? = null
        val clock = Executors.newSingleThreadScheduledExecutor { Thread(it, "MagpieProbeDeadline").apply { isDaemon = true } }
        val expired = java.util.concurrent.atomic.AtomicBoolean(false)
        try {
            check(!canceled) { "测试已取消" }
            conn = (URL(baseUrl + "/v1/chat/completions").openConnection(Proxy.NO_PROXY) as HttpURLConnection).apply {
                requestMethod = "POST"; doOutput = true; instanceFollowRedirects = false; useCaches = false
                connectTimeout = minOf(10_000L, deadlineMs).toInt(); readTimeout = deadlineMs.toInt()
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Accept", "text/event-stream")
                setRequestProperty("Authorization", "Bearer $key")
            }
            active = conn
            if (canceled) { conn.disconnect(); error("测试已取消") }
            clock.schedule({ expired.set(true); conn.disconnect() }, deadlineMs, TimeUnit.MILLISECONDS)
            val body = buildJsonObject {
                put("model", model); put("stream", true); put("max_tokens", 32)
                putJsonArray("messages") { addJsonObject { put("role", "user"); put("content", "Reply with exactly: pong") } }
            }.toString().toByteArray(Charsets.UTF_8)
            conn.setFixedLengthStreamingMode(body.size)
            conn.outputStream.use { it.write(body) }
            status = conn.responseCode
            if (status !in 200..299) {
                val raw = conn.errorStream?.bufferedReader()?.use { reader ->
                    val chars = CharArray(512)
                    val n = reader.read(chars)
                    if (n > 0) String(chars, 0, n) else ""
                }.orEmpty()
                error("HTTP $status ${SecretRedactor.redact(raw)}")
            }
            var payload = StringBuilder()
            var readChars = 0
            fun event(): Boolean {
                val text = payload.toString().trim()
                payload = StringBuilder()
                if (text.isEmpty()) return false
                if (text == "[DONE]") { finished = true; return true }
                val obj = Json.parseToJsonElement(text).jsonObject
                obj["error"]?.takeIf { it != JsonNull }?.let { error ->
                    val message = (error as? JsonObject)?.get("message")?.jsonPrimitive?.contentOrNull ?: error.toString()
                    throw IllegalStateException("流中错误：${SecretRedactor.redact(message).take(300)}")
                }
                (obj["choices"] as? JsonArray).orEmpty().forEach { item ->
                    val choice = item.jsonObject
                    val delta = choice["delta"] as? JsonObject
                    val content = (delta?.get("content") as? JsonPrimitive)?.contentOrNull.orEmpty()
                    val reasoning = (delta?.get("reasoning_content") as? JsonPrimitive)?.contentOrNull
                        ?: (delta?.get("reasoning") as? JsonPrimitive)?.contentOrNull.orEmpty()
                    val tools = (delta?.get("tool_calls") as? JsonArray).orEmpty().any { tool ->
                        val f = (tool as? JsonObject)?.get("function") as? JsonObject
                        listOf("name", "arguments").any { !(f?.get(it) as? JsonPrimitive)?.contentOrNull.isNullOrEmpty() }
                    }
                    if (first == null && (content.isNotEmpty() || reasoning.isNotEmpty() || tools)) first = elapsed()
                    if (content.isNotEmpty()) {
                        if (firstText == null) firstText = elapsed()
                        if (preview.length < 100) preview.append(content.take(100 - preview.length))
                    }
                    if ((choice["finish_reason"] as? JsonPrimitive)?.contentOrNull != null) finished = true
                }
                return false
            }
            conn.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (!canceled) {
                    val line = reader.readLine() ?: break
                    readChars += line.length
                    check(readChars <= 1_000_000) { "诊断响应超过上限" }
                    if (line.isEmpty()) { if (event()) break }
                    else if (line.startsWith("data:")) { if (payload.isNotEmpty()) payload.append('\n'); payload.append(line.removePrefix("data:").trimStart()) }
                    if (elapsed() >= deadlineMs) error("诊断总时限 ${deadlineMs / 1000}s 已到")
                }
                if (payload.isNotEmpty()) event()
            }
            check(!canceled) { "测试已取消" }
            check(first != null) { "未收到文本、推理或工具内容；角色事件和保活不算回答" }
            check(firstText != null) { "只收到推理或工具事件，没有正文回答" }
            check(finished) { "连接提前结束，未收到完成标记" }
            return Attempt(true, status, elapsed(), first, firstText, preview.toString(), "")
        } catch (e: Exception) {
            val message = when { canceled -> "测试已取消"; expired.get() -> "超过 ${deadlineMs / 1000}s 总时限"; else -> SecretRedactor.redact(e.message ?: e.javaClass.simpleName).take(400) }
            return Attempt(false, status, elapsed(), first, firstText, preview.toString(), message)
        } finally {
            clock.shutdownNow()
            conn?.disconnect()
            if (active === conn) active = null
        }
    }
}
