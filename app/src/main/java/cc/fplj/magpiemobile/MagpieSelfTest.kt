package cc.fplj.magpiemobile

import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Direct probe of the local Magpie gateway's inference endpoint. It answers the
 * question DSH Go cannot: is the gateway itself returning a first token, and how
 * long did it take? A run of 3 attempts distinguishes "gateway is flaky" from
 * "DSH Go timeout/queueing".
 */
object MagpieSelfTest {
    data class Attempt(
        val ok: Boolean,
        val status: Int,
        val millis: Long,
        val firstChunk: String,
        val error: String,
    )

    fun run(apiKey: String, model: String, attempts: Int = 3, onStage: (String) -> Unit = {}): List<Attempt> {
        val out = mutableListOf<Attempt>()
        for (i in 1..attempts) {
            onStage("第 $i/$attempts 次：发送请求…")
            out += once(apiKey, model)
            val last = out.last()
            onStage(
                "第 $i/$attempts 次：" + if (last.ok) "成功，首块 ${last.millis} ms" else "失败：" + last.error
            )
            if (i < attempts) Thread.sleep(800)
        }
        return out
    }

    private fun once(apiKey: String, model: String): Attempt {
        val body = """{"model":${jsonString(model)},"messages":[{"role":"user","content":"Reply with exactly: pong"}],"stream":true,"max_tokens":16}"""
        val started = System.nanoTime()
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL("http://127.0.0.1:3425/v1/chat/completions").openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                doOutput = true
                connectTimeout = 15_000
                readTimeout = 180_000
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Authorization", "Bearer $apiKey")
                setRequestProperty("Accept", "text/event-stream")
            }
            conn.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
            val status = conn.responseCode
            val stream = if (status in 200..299) conn.inputStream else conn.errorStream
            val reader = stream?.bufferedReader(Charsets.UTF_8)
            val first = firstEvent(reader)
            val ms = elapsed(started)
            if (status !in 200..299) {
                Attempt(false, status, ms, "", "HTTP $status " + first.take(300))
            } else if (first.isBlank()) {
                Attempt(false, status, ms, "", "连接成功但 180 秒内没有收到任何流事件")
            } else {
                Attempt(true, status, ms, first.take(300), "")
            }
        } catch (e: Exception) {
            Attempt(false, 0, elapsed(started), "", e.message ?: e.javaClass.simpleName)
        } finally {
            runCatching { conn?.disconnect() }
        }
    }

    /** Read the first SSE data payload (or a plain JSON body) within the read timeout. */
    private fun firstEvent(reader: BufferedReader?): String {
        if (reader == null) return ""
        reader.use {
            var line = it.readLine()
            while (line != null) {
                val trimmed = line.trim()
                if (trimmed.startsWith("data:")) {
                    val payload = trimmed.removePrefix("data:").trim()
                    if (payload.isNotEmpty() && payload != "[DONE]") return payload
                } else if (trimmed.startsWith("{") || trimmed.startsWith("[")) {
                    return trimmed
                }
                line = it.readLine()
            }
            return ""
        }
    }

    private fun elapsed(started: Long): Long = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)

    private fun jsonString(value: String): String = buildString {
        append('"')
        for (c in value) {
            when (c) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> append(c)
            }
        }
        append('"')
    }
}
