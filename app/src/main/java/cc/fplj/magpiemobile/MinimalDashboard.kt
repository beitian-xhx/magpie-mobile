package cc.fplj.magpiemobile

import android.os.Build
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import cc.fplj.magpiemobile.core.EnginePaths
import cc.fplj.magpiemobile.core.GatewayEngineController
import cc.fplj.magpiemobile.core.SecretRedactor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

/**
 * 精简的 PC Magpie 风格界面：只保留「路由」与「用量」两页。
 * 路由页负责网关启停、端点密钥、WorkBuddy/Qoder 账号接入与模型清单；
 * 用量页只展示聚合用量、近期请求、模型速度与目录。
 */
@Composable
fun MinimalDashboard(
    layout: EnginePaths.Layout,
    appShell: AppShellViewModel,
    notificationNotice: String,
    onAllowNotifications: () -> Unit,
    onRetryCoreConnection: () -> Unit,
    onStartGateway: () -> Unit,
    onStopGateway: () -> Unit,
) {
    val shell by appShell.state.collectAsStateWithLifecycle()
    val gateway by GatewayRuntime.status.collectAsStateWithLifecycle()
    val connection by NativeCoreSession.status.collectAsStateWithLifecycle()
    val stopping by GatewayRuntime.stopping.collectAsStateWithLifecycle()
    val usageViewModel: UsageDashboardViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val usageState by usageViewModel.state.collectAsStateWithLifecycle()
    val magpieProvidersViewModel: MagpieProvidersViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val magpieProvidersState by magpieProvidersViewModel.state.collectAsStateWithLifecycle()

    var page by rememberSaveable { mutableStateOf("routing") }
    LaunchedEffect(layout) { magpieProvidersViewModel.initialize(layout) }

    val running = gateway.phase == GatewayEngineController.Phase.RUNNING
    val active = stopping || gateway.phase in setOf(
        GatewayEngineController.Phase.STARTING,
        GatewayEngineController.Phase.RUNNING,
        GatewayEngineController.Phase.BACKOFF,
    )
    val clientReady = running && !stopping && connection.ready
    val owner = if (clientReady) NativeCoreSession.providerSessionId() else null
    val currentGatewayKey = if (clientReady) NativeCoreSession.currentGatewayKey() else null

    val lifecycle = androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(clientReady, owner) {
        appShell.connectionChanged()
        if (!clientReady) usageViewModel.clear()
    }
    LaunchedEffect(page, clientReady, owner, lifecycle) {
        if (page == "usage" && clientReady) {
            lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.STARTED) {
                usageViewModel.refresh()
                while (true) {
                    kotlinx.coroutines.delay(10_000)
                    usageViewModel.refreshLocal()
                }
            }
        }
    }

    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = {
            Surface(color = MaterialTheme.colorScheme.background) {
                Column(
                    Modifier.fillMaxWidth().statusBarsPadding().padding(horizontal = 20.dp, vertical = 14.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("MAGPIE " + BuildConfig.VERSION_NAME, style = MaterialTheme.typography.titleLarge)
                        Spacer(Modifier.weight(1f))
                        Text(statusLabel(gateway.phase), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(
                        if (gateway.phase == GatewayEngineController.Phase.RUNNING) "127.0.0.1:3425 · 本机网关"
                        else gateway.message,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
        },
        bottomBar = {
            NavigationBar(containerColor = MaterialTheme.colorScheme.surface, tonalElevation = 0.dp) {
                NavigationBarItem(
                    selected = page == "routing",
                    onClick = { page = "routing" },
                    icon = { Text("⇄") },
                    label = { Text("路由") },
                )
                NavigationBarItem(
                    selected = page == "usage",
                    onClick = { page = "usage" },
                    icon = { Text("▤") },
                    label = { Text("用量") },
                )
            }
        },
    ) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            if (page == "routing") {
                RoutingContent(
                    shell = shell,
                    gateway = gateway,
                    connection = connection,
                    stopping = stopping,
                    active = active,
                    running = running,
                    clientReady = clientReady,
                    currentGatewayKey = currentGatewayKey,
                    notificationNotice = notificationNotice,
                    onAllowNotifications = onAllowNotifications,
                    onRetryCoreConnection = onRetryCoreConnection,
                    onStartGateway = onStartGateway,
                    onStopGateway = onStopGateway,
                    magpieProvidersViewModel = magpieProvidersViewModel,
                    magpieProvidersState = magpieProvidersState,
                    onRefreshModels = appShell::refreshModels,
                )
            } else {
                UsageDashboardContent(ready = clientReady, vm = usageViewModel, state = usageState)
            }
        }
    }
}

@Composable
private fun RoutingContent(
    shell: AppShellState,
    gateway: GatewayEngineController.Status,
    connection: NativeCoreSession.Snapshot,
    stopping: Boolean,
    active: Boolean,
    running: Boolean,
    clientReady: Boolean,
    currentGatewayKey: String?,
    notificationNotice: String,
    onAllowNotifications: () -> Unit,
    onRetryCoreConnection: () -> Unit,
    onStartGateway: () -> Unit,
    onStopGateway: () -> Unit,
    magpieProvidersViewModel: MagpieProvidersViewModel,
    magpieProvidersState: MagpieProvidersUiState,
    onRefreshModels: () -> Unit,
) {
    MonoPanel("Gateway / 网关") {
        Text(
            if (stopping) "正在停止…" else statusLabel(gateway.phase),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(gateway.message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (Build.SUPPORTED_ABIS.firstOrNull()?.startsWith("x86") == true) {
            Text(
                "当前是 x86 模拟器：Magpie 核心为 ARM64，会以 code 132 (SIGILL) 退出，网关无法启动。请在真 arm64 手机上使用。",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
        if (notificationNotice.isNotEmpty()) {
            Text(notificationNotice, style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = onAllowNotifications) { Text("允许通知") }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !active && !magpieProvidersState.busy,
                onClick = onStartGateway,
            ) { Text("启动") }
            OutlinedButton(
                enabled = active && !stopping,
                onClick = onStopGateway,
            ) { Text("停止") }
        }
        if (running && !connection.ready && connection.phase != NativeCoreSession.Phase.CONNECTING) {
            TextButton(onClick = onRetryCoreConnection) { Text("重新核对核心密钥") }
        }
    }

    GatewayEndpointCard(
        running = running && !stopping,
        clientReady = clientReady,
        currentGatewayKey = currentGatewayKey,
        defaultModel = shell.selectedModel,
    )

    MagpieProvidersCard(
        viewModel = magpieProvidersViewModel,
        state = magpieProvidersState,
        gatewayActive = active,
    )

    MonoPanel("Models / 模型") {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(if (clientReady) "可选模型" else "启动网关后显示模型", style = MaterialTheme.typography.titleSmall)
            Spacer(Modifier.weight(1f))
            TextButton(enabled = clientReady, onClick = onRefreshModels) { Text("刷新") }
        }
        if (shell.loadingModels) {
            Text("读取中…", style = MaterialTheme.typography.bodySmall)
        } else if (shell.models.isEmpty()) {
            Text(shell.modelsNotice, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            shell.models.take(40).forEach { model ->
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                    Text(model.id, style = MaterialTheme.typography.bodySmall)
                    Text("可用", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }

    SelfTestPanel(
        clientReady = clientReady,
        currentGatewayKey = currentGatewayKey,
        models = shell.models.map { it.id },
        engineLog = gateway.outputTail,
    )
}

@Composable
private fun SelfTestPanel(
    clientReady: Boolean,
    currentGatewayKey: String?,
    models: List<String>,
    engineLog: String,
) {
    val scope = rememberCoroutineScope()
    val probe = remember { MagpieSelfTest() }
    androidx.lifecycle.compose.LifecycleEventEffect(androidx.lifecycle.Lifecycle.Event.ON_STOP) {
        java.util.concurrent.CompletableFuture.runAsync { probe.cancel() }
    }
    var busy by remember { mutableStateOf(false) }
    var lines by remember { mutableStateOf(listOf<String>()) }
    var model by rememberSaveable { mutableStateOf("") }
    var expanded by remember { mutableStateOf(false) }
    LaunchedEffect(models) { if (model !in models) model = models.firstOrNull().orEmpty() }
    androidx.compose.runtime.DisposableEffect(probe) {
        onDispose { java.util.concurrent.CompletableFuture.runAsync { probe.cancel() } }
    }

    MonoPanel("Self test / 网关自检") {
        Text(
            "直接发出 3 次短请求，测量真实首内容、首正文和完整结束，每次最多 120 秒。测试会消耗所选模型的额度；成功仅说明这几次直连正常，不能排除间歇故障。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        androidx.compose.material3.OutlinedTextField(
            value = model, onValueChange = { model = it }, enabled = !busy,
            modifier = Modifier.fillMaxWidth(), label = { Text("测试模型 ID") }, singleLine = true,
        )
        TextButton(enabled = !busy && models.isNotEmpty(), onClick = { expanded = !expanded }) { Text("从可用模型中选择") }
        if (expanded) models.forEach { id ->
            TextButton(enabled = !busy, onClick = { model = id; expanded = false }) { Text(id) }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(
                enabled = !busy && clientReady && currentGatewayKey != null && model.isNotBlank(),
                onClick = {
                    val key = currentGatewayKey ?: return@Button
                    val id = model.trim()
                    busy = true
                    lines = emptyList()
                    scope.launch {
                        try {
                            kotlinx.coroutines.withContext(Dispatchers.IO) {
                                probe.run(key, id) { stage -> scope.launch { lines = lines + stage } }
                            }
                        } finally { busy = false }
                    }
                },
            ) { Text(if (busy) "测试中…" else "连续测试 3 次") }
            if (busy) OutlinedButton(onClick = { scope.launch(Dispatchers.IO) { probe.cancel() } }) { Text("取消") }
        }
        if (!clientReady) Text("需要先启动网关并完成核心连接。", style = MaterialTheme.typography.bodySmall)
        lines.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }

    MonoPanel("Engine log / 引擎日志") {
        if (engineLog.isBlank()) {
            Text("暂无引擎输出。", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            Text(
                SecretRedactor.redact(engineLog).takeLast(2500),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

private fun statusLabel(phase: GatewayEngineController.Phase): String = when (phase) {
    GatewayEngineController.Phase.STOPPED -> "未运行"
    GatewayEngineController.Phase.STARTING -> "启动中"
    GatewayEngineController.Phase.RUNNING -> "运行中"
    GatewayEngineController.Phase.BACKOFF -> "恢复中"
    GatewayEngineController.Phase.FAILED -> "失败"
}
