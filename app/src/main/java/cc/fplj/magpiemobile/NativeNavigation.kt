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
    val keyPoolViewModel: KeyPoolViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val keyPoolState by keyPoolViewModel.state.collectAsStateWithLifecycle()
    val pluginViewModel: SubscriptionPluginViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val pluginState by pluginViewModel.state.collectAsStateWithLifecycle()
    val magpieProvidersViewModel: MagpieProvidersViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val magpieProvidersState by magpieProvidersViewModel.state.collectAsStateWithLifecycle()
    val profileViewModel: ChatProfileViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val profileState by profileViewModel.state.collectAsStateWithLifecycle()
    val mcpViewModel: McpViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val mcpState by mcpViewModel.state.collectAsStateWithLifecycle()
    val marketViewModel: PluginMarketViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val marketState by marketViewModel.state.collectAsStateWithLifecycle()
    val stabilityViewModel: StabilityViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val stabilityState by stabilityViewModel.state.collectAsStateWithLifecycle()

    LaunchedEffect(layout) {
        pluginViewModel.initialize(layout)
        magpieProvidersViewModel.initialize(layout)
        marketViewModel.initialize(layout)
    }
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
                        SectionCard(title = if (stopping) "正在停止并回收进程…" else gatewayLabel(gateway.phase)) {
                                Text(gateway.message, style = MaterialTheme.typography.bodySmall)
                                if (notificationNotice.isNotEmpty()) {
                                    Text(notificationNotice, style = MaterialTheme.typography.bodySmall)
                                    Button(onClick = onAllowNotifications) { Text("允许通知") }
                                }
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                    Button(enabled = !active && !magpieProvidersState.busy, onClick = onStartGateway) { Text("启动网关") }
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
                        SectionCard(title = "核心安全连接") {
                                Text(connection.message, style = MaterialTheme.typography.bodySmall)
                                if (running && !connection.ready && connection.phase != NativeCoreSession.Phase.CONNECTING)
                                    Button(onClick = onRetryCoreConnection) { Text("重新核对连接与核心密钥") }
                                Text("管理凭据仅留在进程内；客户端密钥由核心签发并私有保存。连接成功不代表已配置渠道。", style = MaterialTheme.typography.bodySmall)
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
                        UsageInsightsCard(
                            clientReady = clientReady,
                            viewModel = usageViewModel,
                            usageState = usageState
                        )
                        ModelSpeedsCard(usageState = usageState)
                        CatalogCard(usageState = usageState)
                        ModelRoutingCard(
                            clientReady = clientReady,
                            viewModel = routingViewModel,
                            routingState = routingState
                        )
                        KeyPoolingCard(
                            clientReady = clientReady,
                            viewModel = keyPoolViewModel,
                            poolState = keyPoolState
                        )
                        MagpieProvidersCard(
                            viewModel = magpieProvidersViewModel,
                            state = magpieProvidersState,
                            gatewayActive = active,
                        )
                        McpServerCard(
                            viewModel = mcpViewModel,
                            mcpState = mcpState
                        )
                        SystemStabilityCard(
                            viewModel = stabilityViewModel,
                            stabilityState = stabilityState
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
                        ChatProfileCard(profileViewModel, profileState)
                        ChatPanel(chatViewModel, shell.models.map { it.id },
                            shell.models.associate { it.id to (it.displayName?.takeIf(String::isNotBlank) ?: it.id) },
                            shell.selectedModel, appShell::selectModel, clientReady && shell.catalogValid, providerBusy)
                    }
                    AppPage.MORE -> {
                        val noticesViewModel: NoticesViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
                        val noticesState by noticesViewModel.state.collectAsStateWithLifecycle()
                        SectionCard(title = "本机界面偏好") {
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
                        SectionCard(title = "版本与诊断") {
                                Text("版本 " + BuildConfig.VERSION_NAME + " · " + BuildConfig.GIT_SHA, style = MaterialTheme.typography.bodySmall)
                                val startupMetrics = cc.fplj.magpiemobile.core.StartupBenchmark.getMetrics()
                                if (startupMetrics.totalToReadyMs >= 0) {
                                    Text(
                                        "冷启动就绪耗时: " + startupMetrics.totalToReadyMs + "ms (" + (if (startupMetrics.isReadyWithinBudget) "达标 <800ms" else "超出预算") + ")",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (startupMetrics.isReadyWithinBudget) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.error
                                    )
                                }
                                val auditor = remember(layout) { cc.fplj.magpiemobile.core.PostMortemAuditor(layout.configDir) }
                                var postMortem by remember(layout) { mutableStateOf(auditor.readReport()) }
                                if (postMortem != null) {
                                    Text(
                                        "异常退出审计记录: [" + postMortem!!.lastKnownPhase + "] 退出码=" + postMortem!!.exitCode,
                                        color = MaterialTheme.colorScheme.error,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                    if (postMortem!!.sanitizedDetail.isNotEmpty()) {
                                        Text(postMortem!!.sanitizedDetail.take(300), style = MaterialTheme.typography.labelSmall)
                                    }
                                    TextButton(onClick = { auditor.clear(); postMortem = null }) {
                                        Text("清除审计记录")
                                    }
                                }
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
                        SectionCard(title = "引擎状态与脱敏输出") {
                                Text(gateway.phase.name + "\n" + gateway.message, style = MaterialTheme.typography.bodySmall)
                                if (gateway.outputTail.isNotEmpty()) Text(gateway.outputTail, style = MaterialTheme.typography.bodySmall)
                        }
                        SectionCard(title = "配置安全备份与恢复（SAF · M4）") {
                                Text("支持经系统存储访问框架（SAF）导入导出脱敏配置。零密钥外泄、纯流式传输、SHA-256 完整性防篡改、拒绝任意文件路径及非安全协议。", style = MaterialTheme.typography.bodySmall)
                                Text("格式版本：schemaVersion 1 · 安全上限：5MB", style = MaterialTheme.typography.labelSmall)
                        }
                        Text("订阅、高级路由、统计和资源库仍按批准计划推进；这些入口尚未交付，不以灰色按钮充当完成。", style = MaterialTheme.typography.bodySmall)
                        LicenseNoticesCard(
                            clientReady = clientReady,
                            viewModel = noticesViewModel,
                            noticesState = noticesState,
                        )
                    }
                }
            }
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
