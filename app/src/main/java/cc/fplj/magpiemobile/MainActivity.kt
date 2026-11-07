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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import cc.fplj.magpiemobile.core.GatewayConfig
import cc.fplj.magpiemobile.core.GatewayEngineController
import cc.fplj.magpiemobile.core.ModelsClient
import cc.fplj.magpiemobile.core.SecretRedactor
import java.io.File
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
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
        ChatSessionHolder.chatFilePath = File(filesDir, "chat.jsonl").absolutePath
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
                            GatewayRuntime.requestStart()
                            try {
                                ContextCompat.startForegroundService(this, Intent(this, GatewayService::class.java))
                            } catch (e: Exception) { GatewayRuntime.requestFailed(e) }
                        },
                        onStopGateway = {
                            try {
                                // Invalidate callbacks from this run before destruction/late spawn cleanup.
                                GatewayRuntime.stoppedWithoutService()
                                stopService(Intent(this, GatewayService::class.java))
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
    val active = gateway.phase in setOf(GatewayEngineController.Phase.STARTING,
        GatewayEngineController.Phase.RUNNING, GatewayEngineController.Phase.BACKOFF)
    val running = gateway.phase == GatewayEngineController.Phase.RUNNING
    val clientReady = running && coreSession.ready
    var log by remember { mutableStateOf("") }
    var modelsText by remember { mutableStateOf("（未加载）") }
    var modelIds by remember { mutableStateOf(emptyList<String>()) }
    var selectedModel by remember { mutableStateOf("") }
    var loadingModels by remember { mutableStateOf(false) }
    var sending by remember { mutableStateOf(false) }
    var chatInput by remember { mutableStateOf("") }
    var chatOut by remember { mutableStateOf("") }
    var modelMenu by remember { mutableStateOf(false) }
    fun append(text: String) { log = (log + "\n" + SecretRedactor.redact(text)).takeLast(8192) }

    Column(
        modifier = Modifier.fillMaxSize().padding(16.dp).verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Magpie Mobile", style = MaterialTheme.typography.headlineMedium)
        Text("版本 " + BuildConfig.VERSION_NAME + " · " + BuildConfig.GIT_SHA, style = MaterialTheme.typography.bodySmall)
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(when (gateway.phase) {
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
            Button(enabled = active, onClick = { append("已请求停止网关"); onStopGateway() }) { Text("停止网关") }
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
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("模型目录", style = MaterialTheme.typography.titleSmall)
                Button(enabled = clientReady && !loadingModels && !sending, onClick = {
                    loadingModels = true
                    scope.launch {
                        try {
                            val cfg = NativeCoreSession.gatewayConfig()
                            val models = withContext(Dispatchers.IO) { ModelsClient(cfg).list() }
                            modelIds = models.map { it.id }
                            if (selectedModel !in modelIds) selectedModel = modelIds.firstOrNull().orEmpty()
                            modelsText = if (modelIds.isEmpty()) "核心没有可用模型，请先配置渠道/账号" else modelIds.take(8).joinToString("\n")
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) { modelsText = "读取失败：" + SecretRedactor.redact(e.message ?: e.javaClass.simpleName) }
                        finally { loadingModels = false }
                    }
                }) { Text(if (loadingModels) "读取中…" else "刷新模型列表") }
                Text(if (clientReady) modelsText else "请先完成核心安全连接；端口就绪不代表已配置渠道和核心密钥", style = MaterialTheme.typography.bodySmall)
            }
        }
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("聊天测试（流式）", style = MaterialTheme.typography.titleSmall)
                Button(enabled = modelIds.isNotEmpty() && !sending, onClick = { modelMenu = true }) {
                    Text(if (selectedModel.isBlank()) "请选择模型" else selectedModel)
                }
                DropdownMenu(expanded = modelMenu, onDismissRequest = { modelMenu = false }) {
                    modelIds.forEach { id ->
                        DropdownMenuItem(text = { Text(id) }, onClick = { selectedModel = id; modelMenu = false })
                    }
                }
                OutlinedTextField(value = chatInput, onValueChange = { chatInput = it }, enabled = !sending,
                    modifier = Modifier.fillMaxWidth(), placeholder = { Text("输入消息…") })
                Button(enabled = clientReady && !sending && chatInput.isNotBlank() && selectedModel in modelIds, onClick = {
                    val text = chatInput
                    val model = selectedModel
                    sending = true
                    chatInput = ""
                    chatOut = ""
                    scope.launch {
                        val deltas = Channel<String>(Channel.UNLIMITED)
                        val render = launch { for (delta in deltas) chatOut += delta }
                        try {
                            val cfg = NativeCoreSession.gatewayConfig()
                            val session = ChatSessionHolder.get(cfg)
                            val reply = withContext(Dispatchers.IO) { session.send(text, model) { delta -> deltas.trySend(delta) } }
                            deltas.close(); render.join() // flush all deltas before final text; no delayed Handler replay
                            chatOut = reply
                        } catch (e: CancellationException) { throw e }
                        catch (e: Exception) {
                            deltas.close(); render.join()
                            chatOut += "\n发送失败（未自动重复发送）：" + SecretRedactor.redact(e.message ?: e.javaClass.simpleName)
                        } finally { deltas.close(); render.cancel(); sending = false }
                    }
                }) { Text(if (sending) "发送中…" else "发送") }
                if (chatOut.isNotEmpty()) Text(chatOut, style = MaterialTheme.typography.bodySmall)
            }
        }
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

object ChatSessionHolder {
    private var cached: cc.fplj.magpiemobile.core.StreamingChatSession? = null
    private var cachedConfig: GatewayConfig? = null
    var chatFilePath: String = ""
    @Synchronized
    fun get(cfg: GatewayConfig): cc.fplj.magpiemobile.core.StreamingChatSession {
        if (cachedConfig == cfg) cached?.let { return it }
        check(chatFilePath.isNotBlank()) { "会话目录尚未初始化" }
        return cc.fplj.magpiemobile.core.StreamingChatSession(
            cc.fplj.magpiemobile.core.StreamingChatClient(cfg), cc.fplj.magpiemobile.core.ChatClient(cfg),
            cc.fplj.magpiemobile.core.SessionStore(File(chatFilePath)), defaultModel = "",
        ).also { cached = it; cachedConfig = cfg }
    }
}
