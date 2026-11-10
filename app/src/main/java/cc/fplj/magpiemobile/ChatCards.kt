package cc.fplj.magpiemobile

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.fplj.magpiemobile.core.GatewayEngineController
import cc.fplj.magpiemobile.core.SecretRedactor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
private fun SubscriptionPluginCard(
    viewModel: SubscriptionPluginViewModel,
    pluginState: PluginUiState,
) {
    var pluginId by remember { mutableStateOf("") }
    var pluginName by remember { mutableStateOf("") }
    val pluginVersion = "1.0.0"
    val pluginEntry = "index.js"
    val pluginScript = "console.log('Plugin initialized');"

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("订阅插件与 Bun 运行时 (Subscription Plugins & Bun)", style = MaterialTheme.typography.titleMedium)
            val bunText = if (pluginState.bunStatus.installed) {
                "Bun 载荷：预置就绪 (" + pluginState.bunStatus.architecture + ")"
            } else {
                "Bun 载荷：未检测到预置 ELF"
            }
            Text(bunText, style = MaterialTheme.typography.bodySmall, color = if (pluginState.bunStatus.installed) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline)

            if (pluginState.plugins.isEmpty()) {
                Text("暂无已安装订阅插件；可在下方沙箱中录入并执行订阅逻辑。", style = MaterialTheme.typography.bodySmall)
            } else {
                pluginState.plugins.forEach { plugin ->
                    Surface(
                        shape = MaterialTheme.shapes.small,
                        tonalElevation = 1.dp,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    plugin.name + " (" + plugin.id + " v" + plugin.version + ")",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (plugin.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                                )
                                Text(
                                    "入口: " + plugin.entrypoint + " · SHA: " + plugin.checksum.take(12) + "…",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { viewModel.togglePlugin(plugin.id) }) {
                                    Text(if (plugin.enabled) "已启用" else "已停用")
                                }
                                TextButton(onClick = { viewModel.rollbackPlugin(plugin.id) }) {
                                    Text("回滚")
                                }
                                TextButton(onClick = { viewModel.removePlugin(plugin.id) }) {
                                    Text("移除")
                                }
                            }
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = pluginId,
                    onValueChange = { pluginId = it },
                    label = { Text("插件标识 (例如: custom-filter)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = pluginName,
                    onValueChange = { pluginName = it },
                    label = { Text("插件名称 (例如: Custom Filter)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    enabled = pluginId.isNotBlank() && pluginName.isNotBlank(),
                    onClick = {
                        viewModel.installPlugin(pluginId, pluginName, pluginVersion, pluginEntry, pluginScript)
                        pluginId = ""
                        pluginName = ""
                    }
                ) {
                    Text("安装/更新插件")
                }
            }

            if (pluginState.error.isNotEmpty()) {
                Text(pluginState.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (pluginState.notice.isNotEmpty()) {
                Text(pluginState.notice, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}


@Composable
private fun ChatProfileCard(
    viewModel: ChatProfileViewModel,
    profileState: ChatProfileUiState,
) {
    var expanded by remember { mutableStateOf(false) }
    var editMode by remember { mutableStateOf(false) }
    var newId by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }
    var newPrompt by remember { mutableStateOf("") }
    var newTemp by remember { mutableStateOf("0.7") }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("智能体与配置预设 (Agent Profiles)", style = MaterialTheme.typography.titleMedium)
            val active = profileState.activeProfile
            Text(
                "当前预设: " + active.name + " (" + active.id + ") · 温度: " + active.temperature,
                style = MaterialTheme.typography.bodyMedium
            )
            if (active.systemPrompt.isNotBlank()) {
                Text(
                    "系统提示词: " + active.systemPrompt,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.outline
                )
            }

            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { expanded = true }) {
                    Text("切换预设 (" + profileState.profiles.size + ")")
                }
                TextButton(onClick = { editMode = !editMode }) {
                    Text(if (editMode) "收起新建" else "新建预设")
                }
            }

            DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                profileState.profiles.forEach { profile ->
                    DropdownMenuItem(
                        text = {
                            Column {
                                Text(profile.name + if (profile.id == active.id) " (当前)" else "")
                                Text("T: " + profile.temperature + " · " + profile.id, style = MaterialTheme.typography.bodySmall)
                            }
                        },
                        onClick = {
                            viewModel.selectProfile(profile.id)
                            expanded = false
                        }
                    )
                }
            }

            if (editMode) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    OutlinedTextField(
                        value = newId,
                        onValueChange = { newId = it },
                        label = { Text("Profile 标识 (例如: code-helper)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("预设名称 (例如: 编程助手)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newPrompt,
                        onValueChange = { newPrompt = it },
                        label = { Text("系统提示词 (System Prompt)") },
                        maxLines = 3,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = newTemp,
                        onValueChange = { newTemp = it },
                        label = { Text("采样温度 (0.0 .. 2.0)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(
                        enabled = newId.isNotBlank() && newName.isNotBlank(),
                        onClick = {
                            val tempVal = newTemp.toDoubleOrNull() ?: 0.7
                            viewModel.saveProfile(newId, newName, newPrompt, temperature = tempVal)
                            newId = ""
                            newName = ""
                            newPrompt = ""
                            editMode = false
                        }
                    ) {
                        Text("保存预设")
                    }
                }
            }

            if (profileState.error.isNotEmpty()) {
                Text(profileState.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (profileState.notice.isNotEmpty()) {
                Text(profileState.notice, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}


@Composable
private fun ModelCatalogCard(shell: AppShellState, ready: Boolean, idle: Boolean, refresh: () -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("模型目录与速览", style = MaterialTheme.typography.titleMedium)
            if (ready) {
                val modelCountText = "可用模型：${shell.models.size} 个"
                val selectedText = if (shell.selectedModel.isNotBlank()) "当前默认：${shell.selectedModel}" else "尚未选择默认模型"
                Text("$modelCountText · $selectedText", style = MaterialTheme.typography.bodyMedium)
                Text(shell.modelsNotice, style = MaterialTheme.typography.bodySmall)
            } else {
                Text(shell.modelsNotice, style = MaterialTheme.typography.bodySmall)
            }
            Button(enabled = ready && idle && !shell.loadingModels, onClick = refresh) { Text(if (shell.loadingModels) "读取中…" else "刷新模型列表") }
        }
    }
}
