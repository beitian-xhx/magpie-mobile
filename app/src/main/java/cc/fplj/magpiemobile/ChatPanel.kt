package cc.fplj.magpiemobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun ChatPanel(
    viewModel: ChatViewModel,
    modelIds: List<String>,
    modelLabels: Map<String, String>,
    selectedModel: String,
    onSelectModel: (String) -> Unit,
    canConnect: Boolean,
    providerBusy: Boolean,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    var historyOpen by remember { mutableStateOf(false) }
    var modelMenu by remember { mutableStateOf(false) }
    val idle = state.ready && !state.busy && !state.sending
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("聊天与本机历史", style = MaterialTheme.typography.titleSmall)
            Text("消息保存在本 App 私有数据库，受 Android 文件加密保护（非逐字段加密），系统备份关闭。发送会把本会话成功上下文交给当前模型，可能消耗额度。", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                TextButton(enabled = idle && !providerBusy, onClick = viewModel::newConversation) { Text("新会话") }
                TextButton(enabled = idle, onClick = { historyOpen = true }) { Text("历史会话") }
                TextButton(enabled = !state.busy && !state.sending, onClick = viewModel::refresh) { Text("重读记录") }
            }
            state.selected?.let { selected ->
                Text(selected.title + " · " + chatDate(selected.updatedAt) + " · " + selected.id.take(8), style = MaterialTheme.typography.bodySmall)
                if (selected.legacy) Text("旧版只读存档：无法可靠区分原会话或失败轮次，不会用作推理上下文；请新建会话。")
            }
            if (state.importNotice.isNotBlank()) Text(state.importNotice, style = MaterialTheme.typography.bodySmall)
            if (state.busy) Text("读取本机记录中…")
            if (state.contextBlocked) Text("上下文达到 40 轮或超过 65,536 字符，记录仍保留。请新建会话；不会静默截断旧上下文。")
            if (state.viewingOlderMessages) TextButton(enabled = idle, onClick = viewModel::refresh) { Text("回最新消息") }
            if (state.olderMessages) TextButton(enabled = idle, onClick = viewModel::olderMessages) { Text("读取更早 50 条消息（换页）") }
            if (state.messages.isNotEmpty()) {
                LazyColumn(Modifier.fillMaxWidth().height(320.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    items(state.messages, key = { it.id }) { message ->
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(8.dp)) {
                                val role = when (message.role) { "user" -> "用户"; "assistant" -> "助手"; "error" -> "状态"; else -> "旧记录" }
                                val status = when (message.state) {
                                    "RUNNING" -> " · 进行中（不重复发送）"
                                    "FAILED" -> " · 失败（不进入成功上下文）"
                                    "INTERRUPTED" -> " · 中断 / 结果未知（未自动重发）"
                                    "LEGACY" -> " · 旧版存档"
                                    else -> ""
                                }
                                Text(role + status + " · " + chatDate(message.ts), style = MaterialTheme.typography.labelSmall)
                                SelectionContainer { Text(message.text, style = MaterialTheme.typography.bodyMedium) }
                                if (message.engine.isNotBlank()) Text(message.engine, style = MaterialTheme.typography.labelSmall)
                            }
                        }
                    }
                }
            } else if (state.ready) Text("尚无消息。打开历史只读取本机数据库，不调用模型。", style = MaterialTheme.typography.bodySmall)
            if (state.partial.isNotEmpty()) {
                Text("本轮输出预览（失败时仅在内存保留，不作为成功回答）", style = MaterialTheme.typography.labelSmall)
                SelectionContainer { Text(state.partial, style = MaterialTheme.typography.bodySmall) }
            }
            Button(enabled = modelIds.isNotEmpty() && idle && !state.selected.let { it?.legacy == true }, onClick = { modelMenu = true }) {
                Text(if (selectedModel.isBlank()) "请选择模型" else modelLabels[selectedModel] ?: selectedModel)
            }
            DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                modelIds.forEach { id ->
                    DropdownMenuItem(text = { Column { Text(modelLabels[id] ?: id); if (modelLabels[id] != id) Text(id, style = MaterialTheme.typography.bodySmall) } }, onClick = { onSelectModel(id); modelMenu = false })
                }
            }
            OutlinedTextField(value = state.draft, onValueChange = viewModel::editDraft,
                enabled = idle && state.selected?.legacy != true, modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("输入消息…（草稿只留内存）") })
            Button(enabled = idle && canConnect && !providerBusy && state.selected?.legacy == false && !state.contextBlocked && state.draft.isNotBlank() && selectedModel in modelIds,
                onClick = { viewModel.send(selectedModel) }) { Text(if (state.sending) "发送中…" else "发送") }
            if (state.notice.isNotBlank()) Text(state.notice, style = MaterialTheme.typography.bodySmall)
        }
    }
    if (historyOpen) Dialog(onDismissRequest = { historyOpen = false }) {
        Surface(shape = MaterialTheme.shapes.large) {
            Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("历史会话（本机）", style = MaterialTheme.typography.titleMedium)
                var editingId by remember { mutableStateOf<String?>(null) }
                var editTitle by remember { mutableStateOf("") }
                LazyColumn(Modifier.fillMaxWidth().height(360.dp)) {
                    items(state.conversations, key = { it.id }) { conversation ->
                        val isEditing = editingId == conversation.id && !conversation.legacy
                        if (isEditing) {
                            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                                OutlinedTextField(value = editTitle, onValueChange = { editTitle = it.take(120) },
                                    enabled = idle, modifier = Modifier.weight(1f), label = { Text("会话标题") })
                                TextButton(enabled = idle && editTitle.isNotBlank(), onClick = {
                                    viewModel.renameConversation(conversation.id, editTitle)
                                    editingId = null
                                }) { Text("保存") }
                                TextButton(enabled = idle, onClick = { editingId = null }) { Text("取消") }
                            }
                        } else {
                            Column(Modifier.fillMaxWidth()) {
                                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                    TextButton(enabled = idle, onClick = { viewModel.selectConversation(conversation.id); historyOpen = false }, modifier = Modifier.weight(1f)) {
                                        Column(Modifier.fillMaxWidth()) {
                                            Text(conversation.title + if (conversation.legacy) " · 只读存档" else "")
                                            Text(chatDate(conversation.updatedAt) + " · " + conversation.id.take(8), style = MaterialTheme.typography.bodySmall)
                                        }
                                    }
                                    if (!conversation.legacy) {
                                        TextButton(enabled = idle, onClick = { editingId = conversation.id; editTitle = conversation.title }) { Text("重命名") }
                                        TextButton(enabled = idle, onClick = { viewModel.deleteConversation(conversation.id) }) { Text("删除") }
                                    }
                                }
                            }
                        }
                    }
                    if (state.moreConversations) item {
                        TextButton(enabled = idle, onClick = viewModel::moreConversations) { Text("加载更多会话") }
                    }
                }
                TextButton(onClick = { historyOpen = false }) { Text("关闭") }
            }
        }
    }
}

private fun chatDate(ts: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))
