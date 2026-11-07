package cc.fplj.magpiemobile.core

import java.io.IOException

/**
 * 流式优先的聊天会话；仅 HTTP 405/501（收到任何成功响应增量之前）可降级一次。
 * 其他 HTTP/SSE/IO/断流失败不重发；失败记录 error，不把 partial 当成功 assistant。
 * fallback 的 engine 带 :fallback 标记；reset 仅清上下文，发送期间拒绝 reset/send。
 */
class StreamingChatSession(
    private val streaming: StreamingChatClient,
    private val fallback: ChatClient,
    private val store: SessionStore,
    private val defaultModel: String,
    private val now: () -> Long = System::currentTimeMillis,
) {
    private val lock = Any()
    private val history = mutableListOf<ChatClient.Message>()
    private var sending = false

    fun messages(): List<ChatClient.Message> = synchronized(lock) { history.toList() }

    fun reset() = synchronized(lock) {
        check(!sending) { "Cannot reset while a send is in progress" }
        history.clear()
    }

    fun send(
        text: String,
        model: String = defaultModel,
        onDelta: (String) -> Unit = {},
    ): String {
        synchronized(lock) {
            check(!sending) { "A send is already in progress" }
            sending = true
        }
        var engine = engineId(model, true)
        try {
            val requestMessages = synchronized(lock) {
                store.append(SessionRecord(now(), "user", engineId(model, false), text))
                history.add(ChatClient.Message("user", text))
                history.toList()
            }
            val reply = try {
                // 返回值已经是纯文本，不能再过非流式 extractReply。
                streaming.stream(model, requestMessages, onDelta)
            } catch (e: ChatException) {
                if (e.httpCode != 405 && e.httpCode != 501) throw e
                engine = engineId(model, false) + ":fallback"
                ChatSession.extractReply(fallback.complete(model, requestMessages))
            }
            synchronized(lock) {
                store.append(SessionRecord(now(), "assistant", engine, reply))
                history.add(ChatClient.Message("assistant", reply))
            }
            return reply
        } catch (e: Exception) {
            // 只记脱敏摘要，不写异常 cause/stack 或 partial（可能含凭据）。
            store.append(SessionRecord(now(), "error", engine, errorSummary(e)))
            throw e
        } finally {
            synchronized(lock) { sending = false }
        }
    }

    private fun errorSummary(e: Exception): String = SecretRedactor.redact(when (e) {
        // 不持久化上游自由文本：仅靠正则不能保证覆盖任意形状的渠道 key。
        is StreamingChatException -> {
            val reason = when {
                e.cause is IOException -> "Stream I/O failure"
                e.cause is ChatException -> "Stream SSE/callback error"
                e.message == "stream -> EOF before [DONE]" -> "Stream EOF before [DONE]"
                e.message == "stream -> expected text/event-stream" -> "Stream expected text/event-stream"
                else -> "Stream failed"
            }
            reason + "; partial characters=" + e.partialText.length
        }
        is ChatException -> "HTTP " + e.httpCode
        is IOException -> "I/O failure: " + e.javaClass.simpleName
        else -> "Chat failed: " + e.javaClass.simpleName
    }).take(300)

    private fun engineId(model: String, streamed: Boolean): String =
        "chat:" + model + if (streamed) ":stream" else ""
}
