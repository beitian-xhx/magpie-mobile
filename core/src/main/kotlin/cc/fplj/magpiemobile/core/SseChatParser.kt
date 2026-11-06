package cc.fplj.magpiemobile.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * SSE 流式聊天解析器（plan.md §3 聊天测试「流式」的纯协议层，无 HTTP/Android 依赖）。
 *
 * 输入：按行送入 OpenAI Chat Completions SSE（data: {...} / data: [DONE] / 空行分隔）。
 * 输出：每个含增量内容的 data 事件回调 onDelta；[DONE] 结束返回完整拼接文本。
 * 规则（plan.md §5）：错误事件原样上抛；半行/断流由调用方决定保留部分内容。
 */
class SseChatParser(
    private val onDelta: (String) -> Unit,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val sb = StringBuilder()

    /** 喂入一行（不带换行符）；返回 true 表示流结束（收到 [DONE]）。 */
    fun feedLine(line: String): Boolean {
        val trimmed = line.trim()
        if (trimmed.isEmpty()) return false
        if (!trimmed.startsWith("data:")) return false // 忽略 event:/comment 行
        val payload = trimmed.removePrefix("data:").trim()
        if (payload == "[DONE]") return true
        val delta = extractDelta(payload)
        if (delta != null) {
            sb.append(delta)
            onDelta(delta)
        }
        return false
    }

    /** 流结束后的完整文本。 */
    fun fullText(): String = sb.toString()

    private fun extractDelta(dataPayload: String): String? = try {
        val root = json.parseToJsonElement(dataPayload).jsonObject
        if (root["error"] != null) {
            throw ChatException("SSE error event: " + root["error"].toString(), 200)
        }
        val choices = root["choices"] as? kotlinx.serialization.json.JsonArray
        val choice0 = choices?.firstOrNull()?.jsonObject
        // 流式增量在 delta.content；结尾块可能只有 finish_reason 无 delta.content
        (choice0?.get("delta")?.jsonObject?.get("content") as? kotlinx.serialization.json.JsonPrimitive)?.content
    } catch (e: ChatException) {
        throw e
    } catch (e: Exception) {
        null // 无法解析的心跳/未知事件：跳过不炸
    }
}
