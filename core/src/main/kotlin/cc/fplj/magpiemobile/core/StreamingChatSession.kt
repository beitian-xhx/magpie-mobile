package cc.fplj.magpiemobile.core

import java.io.IOException

/**
 * 流式优先的聊天会话；仅真正 HTTP 405/501（收到任何成功响应增量之前）可降级一次。
 * 其他 HTTP/SSE/IO/断流失败不重发；失败只回滚本次待定 user，不把 partial 当成功 assistant。
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
    private var nextSendSequence = 0L
    private var activeSendSequence: Long? = null

    private data class PendingUser(val sequence: Long, val index: Int, val message: ChatClient.Message)

    fun messages(): List<ChatClient.Message> = synchronized(lock) { history.toList() }

    fun reset() = synchronized(lock) {
        check(activeSendSequence == null) { "Cannot reset while a send is in progress" }
        history.clear()
    }

    fun send(
        text: String,
        model: String = defaultModel,
        onDelta: (String) -> Unit = {},
    ): String {
        val sequence = synchronized(lock) {
            check(activeSendSequence == null) { "A send is already in progress" }
            activeSendSequence = ++nextSendSequence
            nextSendSequence
        }
        var pendingUser: PendingUser? = null
        var engine = engineId(model, true)
        try {
            val requestMessages = synchronized(lock) {
                store.append(SessionRecord(now(), "user", engineId(model, false), text))
                val message = ChatClient.Message("user", text)
                pendingUser = PendingUser(sequence, history.size, message)
                history.add(message)
                history.toList()
            }
            val reply = try {
                // 返回值已经是纯文本，不能再过非流式 extractReply。
                streaming.stream(model, requestMessages, onDelta)
            } catch (e: ChatException) {
                // Client 只在真正非 2xx HTTP 响应上抛此异常；SSE/回调无法进入降级分支。
                if (e.httpCode != 405 && e.httpCode != 501) throw e
                engine = engineId(model, false) + ":fallback"
                ChatSession.extractReply(fallback.complete(model, requestMessages))
            }
            synchronized(lock) {
                check(pendingMatches(checkNotNull(pendingUser))) { "Pending user changed during send" }
                store.append(SessionRecord(now(), "assistant", engine, reply))
                history.add(ChatClient.Message("assistant", reply))
                pendingUser = null
            }
            return reply
        } catch (e: Exception) {
            synchronized(lock) {
                // 序号、末尾索引和对象身份均匹配才回滚，不能按文本删除旧成功的同名 user。
                pendingUser?.let { if (pendingMatches(it)) history.removeAt(it.index) }
            }
            val failure = safeFailure(e)
            try {
                // 只记固定分类和部分长度，不写原始异常 cause/stack、JSON 或 partial。
                store.append(SessionRecord(now(), "error", engine, errorSummary(failure)))
            } catch (_: Exception) {
                // 日志写入失败也不得替换成含路径/自由文本的原始 IO 异常。
            }
            throw failure
        } finally {
            synchronized(lock) {
                if (activeSendSequence == sequence) activeSendSequence = null
            }
        }
    }

    /** 仅在持有 lock 时调用；成功上下文和其他发送的消息永不参与回滚。 */
    private fun pendingMatches(pending: PendingUser): Boolean =
        activeSendSequence == pending.sequence && history.size == pending.index + 1 &&
            history.getOrNull(pending.index) === pending.message && pending.message.role == "user"

    private fun safeFailure(e: Exception): Exception = when (e) {
        is StreamingChatException -> e
        is ChatException -> ChatException("chat -> HTTP " + e.httpCode, e.httpCode)
        is IOException -> StreamingChatException(StreamingChatFailure.IO_FAILURE, "")
        else -> StreamingChatException(StreamingChatFailure.CHAT_FAILURE, "")
    }

    private fun errorSummary(e: Exception): String = when (e) {
        is StreamingChatException -> e.failure.safeMessage + "; partial characters=" + e.partialText.length
        is ChatException -> "HTTP " + e.httpCode
        else -> StreamingChatFailure.CHAT_FAILURE.safeMessage
    }

    private fun engineId(model: String, streamed: Boolean): String =
        "chat:" + model + if (streamed) ":stream" else ""
}
