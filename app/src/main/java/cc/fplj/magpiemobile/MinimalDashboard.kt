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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.fplj.magpiemobile.core.EnginePaths
import cc.fplj.magpiemobile.core.GatewayEngineController

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
    val usageViewModel: UsageViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
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

    LaunchedEffect(clientReady, owner) {
        appShell.connectionChanged()
        if (clientReady) usageViewModel.refresh(20) else usageViewModel.clear()
    }
    LaunchedEffect(page, clientReady) {
        if (page == "usage" && clientReady) usageViewModel.loadInsights()
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
                UsageContent(
                    clientReady = clientReady,
                    viewModel = usageViewModel,
                    usageState = usageState,
                )
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
}

@Composable
private fun UsageContent(
    clientReady: Boolean,
    viewModel: UsageViewModel,
    usageState: UsageUiState,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("用量", style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.weight(1f))
        TextButton(
            enabled = clientReady && !usageState.loading,
            onClick = {
                viewModel.refresh(20)
                viewModel.loadInsights()
            },
        ) { Text("刷新") }
    }
    UsageInsightsCard(clientReady = clientReady, viewModel = viewModel, usageState = usageState)
    RecentRequestsCard(clientReady = clientReady, viewModel = viewModel, usageState = usageState)
    ModelSpeedsCard(usageState = usageState)
    CatalogCard(usageState = usageState)
}

private fun statusLabel(phase: GatewayEngineController.Phase): String = when (phase) {
    GatewayEngineController.Phase.STOPPED -> "未运行"
    GatewayEngineController.Phase.STARTING -> "启动中"
    GatewayEngineController.Phase.RUNNING -> "运行中"
    GatewayEngineController.Phase.BACKOFF -> "恢复中"
    GatewayEngineController.Phase.FAILED -> "失败"
}
