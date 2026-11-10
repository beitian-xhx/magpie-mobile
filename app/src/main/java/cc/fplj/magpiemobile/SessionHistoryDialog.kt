package cc.fplj.magpiemobile

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
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
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** 历史会话对话框：切换/重命名/删除（从 ChatPanel 原样迁移，P5-1 逻辑零变更）。 */
@Composable
internal fun SessionHistoryDialog(
    state: ChatUiState,
    idle: Boolean,
    onDismiss: () -> Unit,
    viewModel: ChatViewModel,
) {
    Dialog(onDismissRequest = onDismiss) {
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
                                    TextButton(enabled = idle, onClick = { viewModel.selectConversation(conversation.id); onDismiss() }, modifier = Modifier.weight(1f)) {
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
                TextButton(onClick = onDismiss) { Text("关闭") }
            }
        }
    }
}

internal fun chatDate(ts: Long): String = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()).format(Date(ts))