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
internal fun GatewayEndpointCard(
    running: Boolean,
    clientReady: Boolean,
    currentGatewayKey: String?,
    defaultModel: String,
) {
    val clipboard = LocalClipboardManager.current
    var notice by remember { mutableStateOf("") }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("网关接入与本地端点", style = MaterialTheme.typography.titleMedium)
            Text(
                if (running) "网关正在运行，监听地址：http://127.0.0.1:3425/v1"
                else "网关未运行；启动后将在 http://127.0.0.1:3425/v1 提供本地标准服务。",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                "协议：兼容 OpenAI API 标准规范（/v1/chat/completions 与 /v1/models）。手机本地其他 AI 客户端（如 Chatbox、NextChat、OpenCat 等）配置此 Base URL 即可直接通过本网关调用。",
                style = MaterialTheme.typography.bodySmall
            )
            if (running) {
                if (clientReady && currentGatewayKey != null) {
                    val masked = if (currentGatewayKey.length > 20) currentGatewayKey.take(16) + "…" + currentGatewayKey.takeLast(4) else "已就绪"
                    Text("当前内置客户端 Key：" + masked + " (有效已就绪)", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
                } else {
                    Text("核心安全连接验证中，正在核对客户端密钥…", style = MaterialTheme.typography.labelSmall)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = {
                        clipboard.setText(AnnotatedString("http://127.0.0.1:3425/v1"))
                        notice = "已复制网关 Base URL：http://127.0.0.1:3425/v1"
                    }) {
                        Text("复制 Base URL")
                    }
                    OutlinedButton(
                        enabled = clientReady && currentGatewayKey != null,
                        onClick = {
                            if (currentGatewayKey != null) {
                                clipboard.setText(AnnotatedString(currentGatewayKey))
                                notice = "已复制当前网关 API Key 到剪贴板，可粘贴至第三方应用"
                            }
                        }
                    ) {
                        Text("复制 API Key")
                    }
                    OutlinedButton(
                        enabled = clientReady && currentGatewayKey != null,
                        onClick = {
                            val model = defaultModel.ifBlank { "default" }
                            val effectiveKey = currentGatewayKey ?: "sk-magpie-key"
                            val curlCmd = """curl -X POST http://127.0.0.1:3425/v1/chat/completions -H "Content-Type: application/json" -H "Authorization: Bearer $effectiveKey" -d '{"model":"$model","messages":[{"role":"user","content":"Hello"}]}'"""
                            clipboard.setText(AnnotatedString(curlCmd))
                            notice = "已复制测试 cURL（内嵌有效网关 Key），终端粘贴回车即可测试"
                        }
                    ) {
                        Text("复制测试 cURL")
                    }
                }
            }
            if (notice.isNotEmpty()) {
                Text(notice, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}


@Composable
internal fun GatewayKeysCard(
    clientReady: Boolean,
    viewModel: GatewayKeysViewModel,
    keysState: GatewayKeysUiState,
) {
    val clipboard = LocalClipboardManager.current
    var notice by remember { mutableStateOf("") }
    var newKeyName by remember { mutableStateOf("") }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("网关密钥管理 (Caller Keys)", style = MaterialTheme.typography.titleMedium)
            Text(
                "所有访问网关的客户端均需有效密钥鉴权。您可在此为不同外部应用（如 Chatbox、NextChat、OpenCat 或自动化脚本）查看、复制或签发专属客户端密钥。",
                style = MaterialTheme.typography.bodySmall
            )

            if (!clientReady) {
                Text("请先启动网关并完成核心安全连接；就绪后可管理与新增密钥。", style = MaterialTheme.typography.bodySmall)
            } else {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("已登记密钥 (" + keysState.keys.size + ")", style = MaterialTheme.typography.titleSmall)
                    TextButton(enabled = !keysState.loading, onClick = viewModel::refresh) {
                        Text(if (keysState.loading) "刷新中…" else "刷新列表")
                    }
                }

                if (keysState.keys.isEmpty() && !keysState.loading) {
                    Text("暂无其他密钥记录。", style = MaterialTheme.typography.bodySmall)
                } else {
                    keysState.keys.forEach { key ->
                        Surface(
                            shape = MaterialTheme.shapes.small,
                            tonalElevation = 1.dp,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp).fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Column(modifier = Modifier.weight(1f)) {
                                    Text(key.name, style = MaterialTheme.typography.bodyMedium)
                                    Text(
                                        "ID: " + key.id.take(12) + "…" + (if (key.readOnly) " · 只读" else "") + (if (key.off) " · 已停用" else ""),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                                OutlinedButton(
                                    enabled = !key.off && !keysState.loading,
                                    onClick = {
                                        viewModel.copyKey(key.id) { secret ->
                                            clipboard.setText(AnnotatedString(secret))
                                            notice = "已复制密钥 [" + key.name + "] 到剪贴板"
                                        }
                                    }
                                ) {
                                    Text("复制 Key")
                                }
                            }
                        }
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))

                Text("新增客户端专用密钥", style = MaterialTheme.typography.titleSmall)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    OutlinedTextField(
                        value = newKeyName,
                        onValueChange = { newKeyName = it },
                        modifier = Modifier.weight(1f),
                        singleLine = true,
                        placeholder = { Text("例如：Chatbox 或 Termux") },
                        label = { Text("客户端名称") }
                    )
                    Button(
                        enabled = newKeyName.isNotBlank() && !keysState.loading,
                        onClick = {
                            val nameToCreate = newKeyName.trim()
                            viewModel.createKey(nameToCreate) { created ->
                                clipboard.setText(AnnotatedString(created.secret))
                                notice = "成功生成并复制新密钥 [" + created.name + "]"
                                newKeyName = ""
                            }
                        }
                    ) {
                        Text("生成 Key")
                    }
                }

                if (keysState.newlyCreatedKey != null) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                            Text("新密钥生成成功！", style = MaterialTheme.typography.titleSmall)
                            Text("名称：" + keysState.newlyCreatedKey.name, style = MaterialTheme.typography.bodySmall)
                            Text("密钥（已复制）：" + keysState.newlyCreatedKey.secret, style = MaterialTheme.typography.labelMedium)
                            Text("请及时粘贴至第三方客户端保存；后续亦可随时点击“复制 Key”重新获取。", style = MaterialTheme.typography.labelSmall)
                            TextButton(onClick = viewModel::dismissNewKeyDialog) {
                                Text("知道了")
                            }
                        }
                    }
                }

                if (keysState.error.isNotEmpty()) {
                    Text(keysState.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                }
                if (notice.isNotEmpty()) {
                    Text(notice, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}


@Composable
internal fun RecentRequestsCard(
    clientReady: Boolean,
    viewModel: UsageViewModel,
    usageState: UsageUiState,
) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("近期请求速览 (Recent Requests)", style = MaterialTheme.typography.titleMedium)
                if (clientReady) {
                    TextButton(enabled = !usageState.loading, onClick = { viewModel.refresh(5) }) {
                        Text(if (usageState.loading) "刷新中…" else "刷新")
                    }
                }
            }
            Text(
                "展示网关近期通过的 API 请求指标（时间、模型、状态、耗时与 Token 统计；严格零正文记录）。",
                style = MaterialTheme.typography.bodySmall
            )

            if (!clientReady) {
                Text("网关运行且完成安全连接后自动开始记录。", style = MaterialTheme.typography.bodySmall)
            } else if (usageState.requests.isEmpty() && !usageState.loading) {
                Text("暂无近期请求记录（向网关端点发送请求后将在此显示）。", style = MaterialTheme.typography.bodySmall)
            } else {
                usageState.requests.take(5).forEach { req ->
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
                                val modelName = req.model.ifBlank { "unknown" }
                                val providerPart = if (req.provider.isNotBlank()) " [" + req.provider + "]" else ""
                                Text(modelName + providerPart, style = MaterialTheme.typography.bodyMedium)
                                val timeText = if (req.timestamp.isNotBlank()) req.timestamp.take(19).replace('T', ' ') else "刚刚"
                                Text(
                                    timeText + " · " + req.durationMs + "ms",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                val isSuccess = req.statusCode in 200..299
                                Text(
                                    "" + req.statusCode,
                                    style = MaterialTheme.typography.titleSmall,
                                    color = if (isSuccess) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                )
                                Text(
                                    "" + req.totalTokens + " tokens",
                                    style = MaterialTheme.typography.labelSmall
                                )
                            }
                        }
                    }
                }
            }

            if (usageState.error.isNotEmpty()) {
                Text(usageState.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

