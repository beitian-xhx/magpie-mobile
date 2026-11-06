package cc.fplj.magpiemobile.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 应用内聊天会话（plan.md §3「聊天测试（新增）」的行为层）。
 *
 * 组合 ChatClient（网关传输）与 SessionStore（本地记录）：
 * 每轮对话把 user/assistant 消息写入会话记录；上游错误原样抛出并
 * 把失败轮次记入 store（不静默吞错，plan.md §5：保留部分回答并显示失败）。
 * 非流式起步；SSE 在 ChatClient 加流式入口后无缝替换。
 */
class ChatSession(
    private val chat: ChatClient,
    private val store: SessionStore,
    private val defaultModel: String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val history = mutableListOf<ChatClient.Message>()

    /** 历史快照（只读副本）。 */
    fun messages(): List<ChatClient.Message> = history.toList()

    /** 发送一轮：记录 user → 请求 → 记录 assistant；失败记录 error 标记并抛出。 */
    fun send(text: String, model: String = defaultModel): String {
        store.append(SessionRecord(now(), "user", engineId(model), text))
        history.add(ChatClient.Message("user", text))
        try {
            val raw = chat.complete(model, history.toList())
            val reply = extractReply(raw)
            history.add(ChatClient.Message("assistant", reply))
            store.append(SessionRecord(now(), "assistant", engineId(model), reply))
            return reply
        } catch (e: ChatException) {
            store.append(SessionRecord(now(), "error", engineId(model), "HTTP " + e.httpCode))
            throw e
        }
    }

    /** 清空上下文（新会话）；本地记录保留（会话历史不可篡改，plan.md §3 Sessions 行）。 */
    fun reset() {
        history.clear()
    }

    private fun engineId(model: String): String = "chat:" + model

    companion object {
        private val json = Json { ignoreUnknownKeys = true }

        /** OpenAI Chat Completions 标准形状 choices[0].message.content；形状不符抛 ChatException 由上层展示。 */
        fun extractReply(raw: String): String = try {
            val root = json.parseToJsonElement(raw).jsonObject
            val choice0 = (root["choices"] as? kotlinx.serialization.json.JsonArray)?.firstOrNull()?.jsonObject
                ?: throw IllegalArgumentException("choices missing")
            (choice0["message"]?.jsonObject?.get("content") as? kotlinx.serialization.json.JsonPrimitive)?.content
                ?: throw IllegalArgumentException("content missing")
        } catch (e: ChatException) {
            throw e
        } catch (e: Exception) {
            throw ChatException("reply shape unexpected: " + e.message, 200)
        }
    }
}
