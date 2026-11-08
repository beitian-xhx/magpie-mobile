package cc.fplj.magpiemobile

import android.Manifest
import android.app.NotificationManager
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cc.fplj.magpiemobile.core.EnginePaths
import cc.fplj.magpiemobile.core.GatewayEngineController
import cc.fplj.magpiemobile.core.ModelsClient
import cc.fplj.magpiemobile.core.SecretRedactor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    private var healthServer: HealthServer? = null
    private var notificationNotice by mutableStateOf("")
    private val notificationPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) {
        refreshNotificationNotice()
    }

    private fun refreshNotificationNotice() {
        notificationNotice = if (!getSystemService(NotificationManager::class.java).areNotificationsEnabled()) {
            "通知未开启：网关仍可启动；请看下方实时状态和错误，允许通知后可在通知栏停止网关。"
        } else ""
    }

    private fun allowNotifications() {
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        } else {
            startActivity(Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, packageName))
        }
    }

    override fun onResume() {
        super.onResume()
        refreshNotificationNotice()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = EnginePaths.buildLayout(applicationInfo.nativeLibraryDir, filesDir, cacheDir)
        try {
            healthServer = HealthServer(infoProvider = {
                val status = GatewayRuntime.status.value
                mapOf("app" to "magpie-mobile", "version" to BuildConfig.VERSION_NAME,
                    "state" to status.phase.name, "message" to status.message)
            }).also { it.start() }
        } catch (e: Exception) {
            android.util.Log.w("MagpieGateway", "观测端口不可用：" + e.javaClass.simpleName)
        }
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Dashboard(
                        layout = layout,
                        notificationNotice = notificationNotice,
                        onAllowNotifications = ::allowNotifications,
                        onRetryCoreConnection = { NativeCoreSession.retry(filesDir) },
                        onStartGateway = {
                            if (GatewayRuntime.requestStart()) {
                                try { ContextCompat.startForegroundService(this, Intent(this, GatewayService::class.java)) }
                                catch (e: Exception) { GatewayRuntime.requestFailed(e) }
                            }
                        },
                        onStopGateway = {
                            try {
                                // Invalidate callbacks from this run before destruction/late spawn cleanup.
                                val ticket = GatewayRuntime.requestStop()
                                if (!stopService(Intent(this, GatewayService::class.java))) GatewayRuntime.cleanupCompleted(ticket)
                            } catch (e: Exception) { GatewayRuntime.requestFailed(e) }
                        },
                    )
                }
            }
        }
    }

    override fun onDestroy() {
        healthServer?.stop() // this observation endpoint follows Activity, not the gateway lifetime
        super.onDestroy()
    }
}

@Composable
fun Dashboard(
    layout: EnginePaths.Layout,
    notificationNotice: String,
    onAllowNotifications: () -> Unit,
    onRetryCoreConnection: () -> Unit,
    onStartGateway: () -> Unit,
    onStopGateway: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val gateway by GatewayRuntime.status.collectAsStateWithLifecycle()
    val coreSession by NativeCoreSession.status.collectAsStateWithLifecycle()
    val stopping by GatewayRuntime.stopping.collectAsStateWithLifecycle()
    val active = stopping || gateway.phase in setOf(GatewayEngineController.Phase.STARTING,
        GatewayEngineController.Phase.RUNNING, GatewayEngineController.Phase.BACKOFF)
    val running = gateway.phase == GatewayEngineController.Phase.RUNNING
    val clientReady = running && !stopping && coreSession.ready
    var log by remember { mutableStateOf("") }
    var modelsText by remember { mutableStateOf("（未加载）") }
    var modelIds by remember { mutableStateOf(emptyList<String>()) }
    val chatViewModel: ChatViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
    val chat by chatViewModel.state.collectAsStateWithLifecycle()
    val sending = chat.sending
    var selectedModel by androidx.compose.runtime.saveable.rememberSaveable { mutableStateOf("") }
    var loadingModels by remember { mutableStateOf(false) }
    var providerBusy by remember { mutableStateOf(false) }
    var catalogRevision by remember { mutableStateOf(0) }
    var catalogRequest by remember { mutableStateOf(0L) }
    var initialCatalog by remember { mutableStateOf(true) }
    var catalogValid by remember { mutableStateOf(false) }
    var modelLabels by remember { mutableStateOf(emptyMap<String, String>()) }
    fun append(text: String) { log = (log + "\n" + SecretRedactor.redact(text)).takeLast(8192) }

    val modelOwner = if (clientReady) NativeCoreSession.providerSessionId() else null
    LaunchedEffect(clientReady, modelOwner, catalogRevision) {
        val request = ++catalogRequest
        catalogValid = false
        if (!clientReady || modelOwner == null) { loadingModels = false; return@LaunchedEffect }
        loadingModels = true
        try {
            val entries = withContext(Dispatchers.IO) { ModelsClient(NativeCoreSession.gatewayConfig()).list() }
            if (request == catalogRequest && modelOwner == NativeCoreSession.providerSessionId()) {
                val old = selectedModel
                modelIds = entries.map { it.id }
                modelLabels = entries.associate { it.id to (it.displayName?.takeIf(String::isNotBlank) ?: it.id) }
                if (old.isNotBlank() && old !in modelIds) {
                    selectedModel = ""; modelsText = "之前选择的模型已不可用，请重新选择。"
                } else {
                    if (old.isBlank() && initialCatalog) selectedModel = modelIds.firstOrNull().orEmpty()
                    modelsText = if (modelIds.isEmpty()) "没有已配置模型，请添加提供商" else "可选模型 " + modelIds.size
                }
                initialCatalog = false; catalogValid = true
            }
        } catch (e: CancellationException) { throw e }
        catch (_: Exception) { if (request == catalogRequest) modelsText = "模型目录读取失败，缓存未确认；请点击刷新重试。" }
        finally { if (request == catalogRequest) loadingModels = false }
    }
    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Magpie Mobile", style = MaterialTheme.typography.headlineMedium)
        Text("版本 " + BuildConfig.VERSION_NAME + " · " + BuildConfig.GIT_SHA, style = MaterialTheme.typography.bodySmall)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(if (stopping) "正在停止并回收进程…" else when (gateway.phase) {
                    GatewayEngineController.Phase.STOPPED -> "网关未运行"
                    GatewayEngineController.Phase.STARTING -> "正在启动…"
                    GatewayEngineController.Phase.RUNNING -> "运行中（本机端口已就绪）"
                    GatewayEngineController.Phase.BACKOFF -> "异常退出，有限恢复中…"
                    GatewayEngineController.Phase.FAILED -> "网关启动/运行失败"
                }, style = MaterialTheme.typography.titleMedium)
                Text(gateway.message, style = MaterialTheme.typography.bodySmall)
                if (notificationNotice.isNotEmpty()) {
                    Text(notificationNotice, style = MaterialTheme.typography.bodySmall)
                    Button(onClick = onAllowNotifications) { Text("允许通知") }
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(enabled = !active, onClick = { append("已点击启动网关"); onStartGateway() }) { Text("启动网关") }
            Button(enabled = active && !stopping, onClick = { append("已请求停止网关"); onStopGateway() }) { Text("停止网关") }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("核心安全连接", style = MaterialTheme.typography.titleSmall)
                Text(coreSession.message, style = MaterialTheme.typography.bodySmall)
                if (running && !coreSession.ready && coreSession.phase != NativeCoreSession.Phase.CONNECTING) {
                    Button(onClick = onRetryCoreConnection) { Text("重新核对连接与核心密钥") }
                }
                Text("管理凭据仅留在进程内；客户端密钥由核心签发并私有保存。连接成功不代表已配置 API 渠道。", style = MaterialTheme.typography.bodySmall)
            }
        }
        ProvidersPanel(enabled = clientReady, operationBusy = sending, onBusyChanged = { providerBusy = it }, onProvidersChanged = { catalogValid = false; catalogRevision++ })
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("模型目录", style = MaterialTheme.typography.titleSmall)
                Button(enabled = clientReady && !loadingModels && !sending, onClick = { catalogRevision++ }) { Text(if (loadingModels) "读取中…" else "刷新模型列表") }
                Text(if (clientReady) modelsText else "请先完成核心安全连接；端口就绪不代表已配置渠道和核心密钥", style = MaterialTheme.typography.bodySmall)
            }
        }
        ChatPanel(chatViewModel, modelIds, modelLabels, selectedModel, { selectedModel = it }, clientReady && catalogValid, providerBusy)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Button(onClick = {
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            try {
                                val runner = cc.fplj.magpiemobile.core.EngineProcessRunner()
                                val magpie = runner.run(EnginePaths.magpieExecutable(layout).absolutePath,
                                    listOf("--version"), layout.homeDir, layout.cacheDir, layout.configDir)
                                val bun = runner.run(EnginePaths.bunExecutable(layout).absolutePath,
                                    listOf("--version"), layout.homeDir, layout.cacheDir, layout.configDir)
                                "magpie exit=" + magpie.exitCode + ": " + magpie.stdout.trim() + "\n" + magpie.stderr.take(1500) +
                                    "\nbun exit=" + bun.exitCode + ": " + bun.stdout.trim() + "\n" + bun.stderr.take(1500)
                            } catch (e: Exception) { "诊断异常：" + (e.message ?: e.javaClass.simpleName) }
                        }
                        append(result)
                    }
                }) { Text("一键诊断（两引擎版本）") }
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("启动结果与引擎输出", style = MaterialTheme.typography.titleSmall)
                Text("状态：" + gateway.phase + "\n" + gateway.message, style = MaterialTheme.typography.bodySmall)
                if (gateway.outputTail.isNotEmpty()) Text(gateway.outputTail, style = MaterialTheme.typography.bodySmall)
                if (log.isNotEmpty()) Text(log, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}
