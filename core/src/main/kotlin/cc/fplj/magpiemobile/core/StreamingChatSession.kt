package cc.fplj.magpiemobile.core

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

/**
 * 流式优先的聊天会话（plan.md §3 聊天测试「流式」的行为层升级）。
 *
 * 策略：默认走 StreamingChatClient（增量回调实时上抛）；流式请求失败
 * （HTTP 层/服务器不支持 stream）时降级为 ChatClient 非流式——降级记录进
 * SessionStore（engine 字段带 :fallback 标记），不静默。
 * 记录契约与 ChatSession 相同：user/assistant/error 落盘；reset 只清上下文。
 */
class StreamingChatSession(
    private val streaming: StreamingChatClient,
    private val fallback: ChatClient,
    private val store: SessionStore,
    private val defaultModel: String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val history = mutableListOf<ChatClient.Message>()

    fun messages(): List<ChatClient.Message> = history.toList()

    fun reset() {
        history.clear()
    }

    fun send(
        text: String,
        model: String = defaultModel,
        onDelta: (String) -> Unit = {},
    ): String {
        store.append(SessionRecord(now(), "user", engineId(model, false), text))
        history.add(ChatClient.Message("user", text))
        try {
            val full: String
            var engine = engineId(model, true)
            try {
                // 流式优先
                full = streaming.stream(model, history.toList(), onDelta)
            } catch (e: ChatException) {
                // 降级非流式（记录降级标记；错误仍上抛由 UI 呈现）
                engine = engineId(model, false)
                full = fallback.complete(model, history.toList())
            }
            val reply = ChatSession.extractReply(full)
            history.add(ChatClient.Message("assistant", reply))
            store.append(SessionRecord(now(), "assistant", engine, reply))
            return reply
        } catch (e: ChatException) {
            store.append(SessionRecord(now(), "error", engineId(model, false), "HTTP " + e.httpCode))
            throw e
        }
    }

    private fun engineId(model: String, streamed: Boolean): String =
        "chat:" + model + if (streamed) ":stream" else ""
}
