package cc.fplj.magpiemobile

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.fplj.magpiemobile.core.EnginePaths
import cc.fplj.magpiemobile.core.GatewayEngineController
import cc.fplj.magpiemobile.core.SecretRedactor
import cc.fplj.magpiemobile.preferences.AppPage
import cc.fplj.magpiemobile.preferences.GatewayIntent
import cc.fplj.magpiemobile.preferences.ThemeMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
fun Dashboard(
    layout: EnginePaths.Layout,
    appShell: AppShellViewModel,
    notificationNotice: String,
    onAllowNotifications: () -> Unit,
    onRetryCoreConnection: () -> Unit,
    onStartGateway: () -> Unit,
    onStopGateway: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val shell by appShell.state.collectAsStateWithLifecycle()
    val gateway by GatewayRuntime.status.collectAsStateWithLifecycle()
    val connection by NativeCoreSession.status.collectAsStateWithLifecycle()
    val stopping by GatewayRuntime.stopping.collectAsStateWithLifecycle()
    val chatViewModel: ChatViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val chat by chatViewModel.state.collectAsStateWithLifecycle()
    val keysViewModel: GatewayKeysViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val keysState by keysViewModel.state.collectAsStateWithLifecycle()
    val usageViewModel: UsageViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val usageState by usageViewModel.state.collectAsStateWithLifecycle()
    val routingViewModel: RoutingViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val routingState by routingViewModel.state.collectAsStateWithLifecycle()
    var providerBusy by remember { mutableStateOf(false) }
    var diagnosing by remember { mutableStateOf(false) }
    var diagnostic by remember { mutableStateOf("") } // Never DataStore/Bundle.
    val pageStates = rememberSaveableStateHolder() // Scroll position and existing nonsecret recovery markers only.
    val running = gateway.phase == GatewayEngineController.Phase.RUNNING
    val active = stopping || gateway.phase in setOf(GatewayEngineController.Phase.STARTING,
        GatewayEngineController.Phase.RUNNING, GatewayEngineController.Phase.BACKOFF)
    val clientReady = running && !stopping && connection.ready
    val owner = if (clientReady) NativeCoreSession.providerSessionId() else null
    val currentGatewayKey = if (clientReady) NativeCoreSession.currentGatewayKey() else null

    LaunchedEffect(clientReady, owner) {
        appShell.connectionChanged()
        if (clientReady) {
            keysViewModel.refresh()
            usageViewModel.refresh(5)
        } else {
            usageViewModel.clear()
        }
    }
    BackHandler(enabled = shell.page != AppPage.GATEWAY) {
        if (!providerBusy) appShell.selectPage(AppPage.GATEWAY)
    }

    Scaffold(
        topBar = {
            Surface(tonalElevation = 3.dp) {
                Column(Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 16.dp, vertical = 8.dp)) {
                    Text("Magpie Mobile · " + pageTitle(shell.page), style = MaterialTheme.typography.titleLarge)
                    Text(if (stopping) "正在停止并回收进程…" else gatewayLabel(gateway.phase), style = MaterialTheme.typography.bodySmall)
                    if (chat.sending) Text("聊天正在发送；切换页面不会重复请求。", style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        bottomBar = {
            NavigationBar {
                AppPage.entries.forEach { page ->
                    NavigationBarItem(
                        selected = shell.page == page,
                        enabled = !providerBusy,
                        onClick = { if (!providerBusy) appShell.selectPage(page) },
                        icon = { Text(when (page) { AppPage.GATEWAY -> "网"; AppPage.PROVIDERS -> "商"; AppPage.CHAT -> "聊"; AppPage.MORE -> "更" }) },
                        label = { Text(pageTitle(page)) },
                    )
                }
            }
        },
    ) { padding ->
        pageStates.SaveableStateProvider(shell.page.name) {
            Column(Modifier.fillMaxSize().padding(padding).imePadding().padding(16.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when (shell.page) {
                    AppPage.GATEWAY -> {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text(if (stopping) "正在停止并回收进程…" else gatewayLabel(gateway.phase), style = MaterialTheme.typography.titleMedium)
                                Text(gateway.message, style = MaterialTheme.typography.bodySmall)
                                if (notificationNotice.isNotEmpty()) {
                                    Text(notificationNotice, style = MaterialTheme.typography.bodySmall)
                                    Button(onClick = onAllowNotifications) { Text("允许通知") }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(enabled = !active, onClick = onStartGateway) { Text("启动网关") }
                                    Button(enabled = active && !stopping, onClick = onStopGateway) { Text("停止网关") }
                                }
                                if (shell.preferences.ready) Text(when (shell.preferences.value.gatewayIntent) {
                                    GatewayIntent.NONE -> "尚无保存的启停意图。"
                                    GatewayIntent.START -> "上次用户选择：启动。实际状态以上方为准，不会据此自动恢复服务。"
                                    GatewayIntent.STOP -> "上次用户选择：停止。不会因打开应用或系统重启自动复活。"
                                }, style = MaterialTheme.typography.bodySmall)
                                Text("启停意图仅是本机记录，不是自动启动许可。", style = MaterialTheme.typography.bodySmall)
                                if (shell.preferences.warning != null) Text(shell.preferences.warning!!, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("核心安全连接", style = MaterialTheme.typography.titleMedium)
                                Text(connection.message, style = MaterialTheme.typography.bodySmall)
                                if (running && !connection.ready && connection.phase != NativeCoreSession.Phase.CONNECTING)
                                    Button(onClick = onRetryCoreConnection) { Text("重新核对连接与核心密钥") }
                                Text("管理凭据仅留在进程内；客户端密钥由核心签发并私有保存。连接成功不代表已配置渠道。", style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        GatewayEndpointCard(
                            running = running && !stopping,
                            clientReady = clientReady,
                            currentGatewayKey = currentGatewayKey,
                            defaultModel = shell.selectedModel
                        )
                        GatewayKeysCard(
                            clientReady = clientReady,
                            viewModel = keysViewModel,
                            keysState = keysState
                        )
                        RecentRequestsCard(
                            clientReady = clientReady,
                            viewModel = usageViewModel,
                            usageState = usageState
                        )
                        ModelRoutingCard(
                            clientReady = clientReady,
                            viewModel = routingViewModel,
                            routingState = routingState
                        )
                        ModelCatalogCard(shell, clientReady, !chat.sending, appShell::refreshModels)
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = { appShell.selectPage(AppPage.PROVIDERS) }) { Text("管理提供商") }
                            TextButton(onClick = { appShell.selectPage(AppPage.CHAT) }) { Text("打开聊天与历史") }
                        }
                    }
                    AppPage.PROVIDERS -> {
                        if (!clientReady) {
                            Text("请先启动网关并完成核心安全连接；打开此页面不会自动启动。")
                            TextButton(onClick = { appShell.selectPage(AppPage.GATEWAY) }) { Text("返回网关概览") }
                        }
                        ProvidersPanel(enabled = clientReady, operationBusy = chat.sending,
                            onBusyChanged = { providerBusy = it }, onProvidersChanged = appShell::refreshModels)
                    }
                    AppPage.CHAT -> {
                        ModelCatalogCard(shell, clientReady, !chat.sending, appShell::refreshModels)
                        ChatPanel(chatViewModel, shell.models.map { it.id },
                            shell.models.associate { it.id to (it.displayName?.takeIf(String::isNotBlank) ?: it.id) },
                            shell.selectedModel, appShell::selectModel, clientReady && shell.catalogValid, providerBusy)
                    }
                    AppPage.MORE -> {
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("本机界面偏好", style = MaterialTheme.typography.titleMedium)
                                Text("主题、最近页面和模型标识保存于 App 私有 DataStore；不另存提供商配置、密钥或聊天正文。", style = MaterialTheme.typography.bodySmall)
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    ThemeMode.entries.forEach { theme ->
                                        FilterChip(selected = shell.theme == theme, onClick = { appShell.selectTheme(theme) }, label = {
                                            Text(when (theme) { ThemeMode.SYSTEM -> "跟随系统"; ThemeMode.LIGHT -> "浅色"; ThemeMode.DARK -> "深色" })
                                        })
                                    }
                                }
                                if (!shell.preferences.ready) Text("偏好尚未确认；当前临时选择可使用，原文件不会被重建。", style = MaterialTheme.typography.bodySmall)
                                shell.preferences.warning?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
                                if (shell.saveNotice.isNotEmpty()) Text(shell.saveNotice, style = MaterialTheme.typography.bodySmall)
                                TextButton(onClick = appShell::retryPreferences) { Text("重新读取本机偏好") }
                            }
                        }
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("版本与诊断", style = MaterialTheme.typography.titleMedium)
                                Text("版本 " + BuildConfig.VERSION_NAME + " · " + BuildConfig.GIT_SHA, style = MaterialTheme.typography.bodySmall)
                                Button(enabled = !diagnosing, onClick = {
                                    diagnosing = true
                                    scope.launch {
                                        try {
                                            val result = withContext(Dispatchers.IO) {
                                                try {
                                                    val runner = cc.fplj.magpiemobile.core.EngineProcessRunner()
                                                    val magpie = runner.run(EnginePaths.magpieExecutable(layout).absolutePath,
                                                        listOf("--version"), layout.homeDir, layout.cacheDir, layout.configDir)
                                                    val bun = runner.run(EnginePaths.bunExecutable(layout).absolutePath,
                                                        listOf("--version"), layout.homeDir, layout.cacheDir, layout.configDir)
                                                    "magpie exit=" + magpie.exitCode + ": " + magpie.stdout.trim() + "\n" + magpie.stderr.take(1500) +
                                                        "\nbun exit=" + bun.exitCode + ": " + bun.stdout.trim() + "\n" + bun.stderr.take(1500)
                                                } catch (_: Exception) { "诊断未能完成，请检查载荷与运行状态。" }
                                            }
                                            diagnostic = SecretRedactor.redact(result).takeLast(8192)
                                        } catch (e: CancellationException) { throw e }
                                        finally { diagnosing = false }
                                    }
                                }) { Text(if (diagnosing) "诊断中…" else "一键诊断（两引擎版本）") }
                                if (diagnostic.isNotEmpty()) Text(diagnostic, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("引擎状态与脱敏输出", style = MaterialTheme.typography.titleMedium)
                                Text(gateway.phase.name + "\n" + gateway.message, style = MaterialTheme.typography.bodySmall)
                                if (gateway.outputTail.isNotEmpty()) Text(gateway.outputTail, style = MaterialTheme.typography.bodySmall)
                            }
                        }
                        Card(Modifier.fillMaxWidth()) {
                            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                Text("配置安全备份与恢复（SAF · M4）", style = MaterialTheme.typography.titleMedium)
                                Text("支持经系统存储访问框架（SAF）导入导出脱敏配置。零密钥外泄、纯流式传输、SHA-256 完整性防篡改、拒绝任意文件路径及非安全协议。", style = MaterialTheme.typography.bodySmall)
                                Text("格式版本：schemaVersion 1 · 安全上限：5MB", style = MaterialTheme.typography.labelSmall)
                            }
                        }
                        Text("订阅、高级路由、统计和资源库仍按批准计划推进；这些入口尚未交付，不以灰色按钮充当完成。", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
private fun GatewayEndpointCard(
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
private fun GatewayKeysCard(
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
private fun RecentRequestsCard(
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

@Composable
private fun ModelRoutingCard(
    clientReady: Boolean,
    viewModel: RoutingViewModel,
    routingState: RoutingUiState,
) {
    var newAlias by remember { mutableStateOf("") }
    var newTarget by remember { mutableStateOf("") }
    var newFallback by remember { mutableStateOf("") }

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text("模型别名与路由降级 (Model Routing)", style = MaterialTheme.typography.titleMedium)
            Text(
                "配置客户端模型别名映射（例如将 gpt-4o 别名映射至指定提供商模型），并定义备用回退链。",
                style = MaterialTheme.typography.bodySmall
            )

            if (routingState.rules.isEmpty()) {
                Text("暂无路由别名规则；所有模型请求将直接路由至提供商原生目录。", style = MaterialTheme.typography.bodySmall)
            } else {
                routingState.rules.forEach { rule ->
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
                                    rule.alias + " -> " + rule.targetModel,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (rule.enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.outline
                                )
                                if (rule.fallbackModels.isNotEmpty()) {
                                    Text(
                                        "回退链: " + rule.fallbackModels.joinToString(", "),
                                        style = MaterialTheme.typography.labelSmall
                                    )
                                }
                            }
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                TextButton(onClick = { viewModel.toggleRule(rule.alias) }) {
                                    Text(if (rule.enabled) "已启用" else "已停用")
                                }
                                TextButton(onClick = { viewModel.removeRule(rule.alias) }) {
                                    Text("删除")
                                }
                            }
                        }
                    }
                }
            }

            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                OutlinedTextField(
                    value = newAlias,
                    onValueChange = { newAlias = it },
                    label = { Text("请求别名 (例如: fast-chat)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = newTarget,
                    onValueChange = { newTarget = it },
                    label = { Text("目标模型 (例如: openai/gpt-4o-mini)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = newFallback,
                    onValueChange = { newFallback = it },
                    label = { Text("备选回退模型 (可选，英文逗号分隔)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Button(
                    enabled = newAlias.isNotBlank() && newTarget.isNotBlank(),
                    onClick = {
                        val fallbacks = newFallback.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                        viewModel.addRule(newAlias, newTarget, fallbacks)
                        newAlias = ""
                        newTarget = ""
                        newFallback = ""
                    }
                ) {
                    Text("添加路由规则")
                }
            }

            if (routingState.error.isNotEmpty()) {
                Text(routingState.error, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (routingState.notice.isNotEmpty()) {
                Text(routingState.notice, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
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

private fun pageTitle(page: AppPage): String = when (page) {
    AppPage.GATEWAY -> "网关"; AppPage.PROVIDERS -> "提供商"; AppPage.CHAT -> "聊天"; AppPage.MORE -> "更多"
}

private fun gatewayLabel(phase: GatewayEngineController.Phase): String = when (phase) {
    GatewayEngineController.Phase.STOPPED -> "网关未运行"
    GatewayEngineController.Phase.STARTING -> "正在启动…"
    GatewayEngineController.Phase.RUNNING -> "运行中（本机端口已就绪）"
    GatewayEngineController.Phase.BACKOFF -> "异常退出，有限恢复中…"
    GatewayEngineController.Phase.FAILED -> "网关启动/运行失败"
}
