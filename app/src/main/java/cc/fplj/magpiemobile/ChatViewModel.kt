package cc.fplj.magpiemobile

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import cc.fplj.magpiemobile.core.ChatClient
import cc.fplj.magpiemobile.core.ChatTurnStore
import cc.fplj.magpiemobile.core.ChatException
import cc.fplj.magpiemobile.core.GatewayConfig
import cc.fplj.magpiemobile.core.StreamingChatClient
import cc.fplj.magpiemobile.core.StreamingChatException
import cc.fplj.magpiemobile.core.StreamingChatSession
import cc.fplj.magpiemobile.data.ChatRepository
import cc.fplj.magpiemobile.data.ContextLimitException
import cc.fplj.magpiemobile.data.ConversationSummary
import cc.fplj.magpiemobile.data.StoredChatMessage
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlin.coroutines.coroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

data class ChatUiState(
    val ready: Boolean = false,
    val busy: Boolean = true,
    val sending: Boolean = false,
    val conversations: List<ConversationSummary> = emptyList(),
    val moreConversations: Boolean = false,
    val selected: ConversationSummary? = null,
    val messages: List<StoredChatMessage> = emptyList(),
    val olderMessages: Boolean = false,
    val viewingOlderMessages: Boolean = false,
    val draft: String = "",
    val partial: String = "",
    val notice: String = "",
    val importNotice: String = "",
    val contextBlocked: Boolean = false,
)

/** Activity ViewModel owns requests: rotation never launches another billable send. */
internal class ChatConnection(val config: GatewayConfig, val isCurrent: () -> Boolean)

class ChatViewModel internal constructor(
    application: Application,
    private val openRepository: (Application) -> ChatRepository,
    private val currentConnection: () -> ChatConnection?,
) : AndroidViewModel(application) {
    constructor(application: Application) : this(application, { ChatRepository.open(it) }, {
        val owner = NativeCoreSession.providerSessionId()
        if (owner == null) null else try {
            ChatConnection(NativeCoreSession.gatewayConfig()) { owner == NativeCoreSession.providerSessionId() }
        } catch (_: Exception) { null }
    })
    private val mutable = MutableStateFlow(ChatUiState())
    val state = mutable.asStateFlow()
    private var repository: ChatRepository? = null
    private var readRunning = false // Main-thread guard, including before openRepository returns.
    private val drafts = mutableMapOf<String, String>() // No Bundle, Intent, log or disk draft copies.

    init { refresh() }

    fun editDraft(text: String) {
        if (!mutable.value.sending && !mutable.value.busy) mutable.update { it.copy(draft = text.take(65536)) }
    }

    fun refresh() {
        if (readRunning || mutable.value.sending || (repository != null && mutable.value.busy)) return
        readRunning = true
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val report = withContext(Dispatchers.IO) {
                    val repo = repository ?: openRepository(getApplication()).also { repository = it }
                    repo.initialize(File(getApplication<Application>().filesDir, "chat.jsonl"))
                }
                val importText = report.warning ?: if (report.imported > 0)
                    "旧记录已归档：" + report.imported + " 条。源文件保留；旧记录不作为推理上下文。" else ""
                mutable.update { it.copy(importNotice = importText) }
                loadLatest(mutable.value.selected?.id)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                mutable.update { it.copy(ready = false, notice = "会话数据库读取失败；已保留原始数据，未重建或覆盖数据库。请重试读取，当前禁止发送。") }
            } finally { readRunning = false; mutable.update { it.copy(busy = false) } }
        }
    }

    fun newConversation() = changeSelection(null, create = true)
    fun selectConversation(id: String) = changeSelection(id)

    private fun changeSelection(id: String?, create: Boolean = false) {
        val snapshot = mutable.value
        if (!snapshot.ready || snapshot.busy || snapshot.sending) return
        snapshot.selected?.id?.let { drafts[it] = snapshot.draft }
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val target = if (create) withContext(Dispatchers.IO) { checkNotNull(repository).createConversation() } else id
                loadLatest(target)
                mutable.update { it.copy(draft = drafts[it.selected?.id].orEmpty(), partial = "", notice = "") }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(notice = "会话读取失败；未切换上下文，请重试。") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    private suspend fun loadLatest(selectedId: String?) {
        val loaded = withContext(Dispatchers.IO) {
            val repo = checkNotNull(repository)
            val list = repo.conversations(limit = 31)
            val selected = selectedId?.let(repo::conversation)
                ?: list.firstOrNull { !it.legacy }
                ?: repo.conversation(repo.createConversation())!!
            val messages = repo.messages(selected.id, limit = 51)
            val blocked = if (selected.legacy) false else try { repo.context(selected.id).size >= 80 }
                catch (_: ContextLimitException) { true }
            Triple(selected, messages, Pair(repo.conversations(limit = 31), blocked))
        }
        mutable.update { it.copy(
            ready = true, selected = loaded.first,
            messages = loaded.second.takeLast(50), olderMessages = loaded.second.size > 50, viewingOlderMessages = false,
            conversations = loaded.third.first.take(30), moreConversations = loaded.third.first.size > 30,
            contextBlocked = loaded.third.second,
        ) }
    }

    fun olderMessages() {
        val snapshot = mutable.value
        val id = snapshot.selected?.id ?: return
        val before = snapshot.messages.firstOrNull()?.id ?: return
        if (!snapshot.ready || snapshot.busy || snapshot.sending || !snapshot.olderMessages) return
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val page = withContext(Dispatchers.IO) { checkNotNull(repository).messages(id, before, 51) }
                mutable.update { it.copy(messages = page.takeLast(50), olderMessages = page.size > 50, viewingOlderMessages = true) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(notice = "更早消息读取失败；现有消息保留。") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    fun moreConversations() {
        val snapshot = mutable.value
        if (!snapshot.ready || snapshot.busy || snapshot.sending || !snapshot.moreConversations) return
        mutable.update { it.copy(busy = true) }
        viewModelScope.launch {
            try {
                val page = withContext(Dispatchers.IO) { checkNotNull(repository).conversations(snapshot.conversations.size, 31) }
                mutable.update { it.copy(conversations = it.conversations + page.take(30), moreConversations = page.size > 30) }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) { mutable.update { it.copy(notice = "会话列表读取失败；现有列表保留。") } }
            finally { mutable.update { it.copy(busy = false) } }
        }
    }

    fun send(model: String) {
        val snapshot = mutable.value
        val selected = snapshot.selected ?: return
        if (!snapshot.ready || snapshot.busy || snapshot.sending || selected.legacy || snapshot.contextBlocked || snapshot.draft.isBlank() || model.isBlank()) return
        // Capture a current private connection, never store its key in Room or public state.
        val connection = try { currentConnection() } catch (_: Exception) { null } ?: return
        val config = connection.config
        mutable.update { it.copy(sending = true, partial = "", notice = "") }
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    val repo = checkNotNull(repository)
                    val context = repo.context(selected.id)
                    if (context.size >= 80 || context.sumOf { it.content.length.toLong() } + snapshot.draft.length > 65536) throw ContextLimitException()
                    check(connection.isCurrent())
                    val sendContext = coroutineContext
                    sendContext.ensureActive()
                    val baseTurns = repo.turnStore(selected.id)
                    val guardedTurns = object : ChatTurnStore by baseTurns {
                        override fun begin(user: cc.fplj.magpiemobile.core.SessionRecord): String {
                            sendContext.ensureActive()
                            val id = baseTurns.begin(user)
                            sendContext.ensureActive()
                            return id
                        }
                    }
                    val session = StreamingChatSession(
                        StreamingChatClient(config), ChatClient(config), store = null, defaultModel = model,
                        turnStore = guardedTurns, initialMessages = context,
                    )
                    session.send(snapshot.draft, model) { delta ->
                        sendContext.ensureActive()
                        // Direct atomic update; no unlimited channel or delayed Activity callback.
                        mutable.update { it.copy(partial = (it.partial + delta).take(65536)) }
                    }
                }
                drafts.remove(selected.id)
                mutable.update { it.copy(draft = "", partial = "", notice = "本轮已保存。") }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                val status = when (e) {
                    is ContextLimitException -> "上下文达到本机安全上限，请新建会话"
                    is ChatException -> "HTTP " + e.httpCode
                    is StreamingChatException -> e.httpCode?.let { "HTTP " + it } ?: "网络或流式中断"
                    else -> "请求或本地保存未能完成"
                }
                mutable.update { it.copy(notice = "发送未完成（" + status + "）；草稿已保留，未自动重发。上游可能已处理，手动发送可能再次消耗额度。") }
            } finally {
                // A read failure must not be followed by another network operation or destructive repair.
                try { loadLatest(selected.id) }
                catch (e: CancellationException) { throw e }
                catch (_: Exception) { mutable.update { it.copy(ready = false, notice = it.notice + " 会话读取失败；保留原始数据，当前禁止发送。") } }
                finally { mutable.update { it.copy(sending = false) } }
            }
        }
    }
}
