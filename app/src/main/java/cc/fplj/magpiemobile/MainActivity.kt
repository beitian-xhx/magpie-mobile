package cc.fplj.magpiemobile

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cc.fplj.magpiemobile.core.ChatSession
import cc.fplj.magpiemobile.core.EnginePaths
import cc.fplj.magpiemobile.core.GatewayConfig
import cc.fplj.magpiemobile.core.ModelsClient
import cc.fplj.magpiemobile.core.ProvisioningEvaluator
import cc.fplj.magpiemobile.core.ProvisioningState
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * P1 Compose 仪表盘（plan.md §4.4）：
 * 状态头（ProvisioningEvaluator）+ 网关启停 + 模型速览（ModelsClient）+ 诊断。
 * 单页卡片布局；数据流单向：事件 → 协程 → core 组件 → State。
 */
class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val layout = EnginePaths.buildLayout(
            applicationInfo.nativeLibraryDir,
            filesDir,
            cacheDir,
        )
        ChatSessionHolder.chatFilePath = File(filesDir, "chat.jsonl").absolutePath
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Dashboard(
                        layout = layout,
                        onStartGateway = {
                            startForegroundService(Intent(this, GatewayService::class.java))
                        },
                        onStopGateway = {
                            stopService(Intent(this, GatewayService::class.java))
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun Dashboard(
    layout: EnginePaths.Layout,
    onStartGateway: () -> Unit,
    onStopGateway: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    var log by remember { mutableStateOf("nativeLibraryDir: " + layout.nativeLibraryDir.absolutePath) }
    var modelsText by remember { mutableStateOf("（未加载）") }

    fun append(s: String) {
        log = log + "\n" + s
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text("Magpie Mobile", style = MaterialTheme.typography.headlineMedium)

        // ── 状态头 ──
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                val state = ProvisioningEvaluator().evaluate(layout, EngineSupervisorHolder.supervisor)
                Text(
                    text = when (state) {
                        is ProvisioningState.NotInstalled -> "未安装：载荷缺失"
                        is ProvisioningState.NeedsSetup -> "需要初始化"
                        is ProvisioningState.Ready -> "就绪（网关未运行）"
                        is ProvisioningState.Starting -> "启动中…"
                        is ProvisioningState.Running -> "运行中"
                        is ProvisioningState.Failed -> "失败：" + state.lastError
                    },
                    style = MaterialTheme.typography.titleMedium,
                )
            }
        }

        // ── 网关控制 ──
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = {
                append("启动网关（前台服务）…")
                onStartGateway()
            }) { Text("启动网关") }
            Button(onClick = {
                append("停止网关…")
                onStopGateway()
            }) { Text("停止网关") }
        }

        // ── 模型速览 ──
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("模型目录（/v1/models）", style = MaterialTheme.typography.titleSmall)
                Button(onClick = {
                    scope.launch {
                        try {
                            val cfg = GatewayConfig(gatewayKey = GatewayKeyHolder.currentKey)
                            val models = withContext(Dispatchers.IO) { ModelsClient(cfg).list() }
                            modelsText = if (models.isEmpty()) "（网关未运行或无模型）"
                            else models.take(8).joinToString("\n") { it.id }
                        } catch (e: Exception) {
                            modelsText = "读取失败：" + (e.message ?: e.javaClass.simpleName) +
                                "\n（网关未运行时属预期）"
                        }
                    }
                }) { Text("刷新模型列表") }
                Text(modelsText, style = MaterialTheme.typography.bodySmall)
            }
        }

        // ── 聊天测试 ──
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("聊天测试（流式优先，失败自动降级）", style = MaterialTheme.typography.titleSmall)
                var chatInput by remember { mutableStateOf("") }
                var chatOut by remember { mutableStateOf("") }
                androidx.compose.material3.OutlinedTextField(
                    value = chatInput,
                    onValueChange = { chatInput = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("输入消息…") },
                )
                Button(onClick = {
                    val text = chatInput
                    if (text.isNotBlank()) {
                        chatInput = ""
                        scope.launch {
                            try {
                                val cfg = GatewayConfig(gatewayKey = GatewayKeyHolder.currentKey)
                                val session = ChatSessionHolder.get(cfg)
                                chatOut = ""
                                val reply = withContext(Dispatchers.IO) {
                                    session.send(text) { delta ->
                                        android.os.Handler(android.os.Looper.getMainLooper()).post {
                                            chatOut += delta // 流式增量渲染
                                        }
                                    }
                                }
                                chatOut = reply // 最终全文覆盖（含降级路径）
                            } catch (e: Exception) {
                                chatOut = "发送失败：" + (e.message ?: e.javaClass.simpleName)
                            }
                        }
                    }
                }) { Text("发送") }
                if (chatOut.isNotEmpty()) Text(chatOut, style = MaterialTheme.typography.bodySmall)
            }
        }

        // ── 诊断 ──
        Card(modifier = Modifier.fillMaxWidth()) {
            Column(modifier = Modifier.padding(12.dp)) {
                Button(onClick = {
                    scope.launch {
                        val r = withContext(Dispatchers.IO) {
                            try {
                                val runner = cc.fplj.magpiemobile.core.EngineProcessRunner()
                                val mv = runner.run(
                                    EnginePaths.magpieExecutable(layout).absolutePath,
                                    listOf("--version"),
                                    layout.homeDir, layout.cacheDir, layout.configDir,
                                )
                                val bv = runner.run(
                                    EnginePaths.bunExecutable(layout).absolutePath,
                                    listOf("--version"),
                                    layout.homeDir, layout.cacheDir, layout.configDir,
                                )
                                "magpie exit=" + mv.exitCode + ": " + mv.stdout.trim() +
                                    "\nbun exit=" + bv.exitCode + ": " + bv.stdout.trim()
                            } catch (e: Exception) {
                                "诊断异常：" + (e.message ?: e.javaClass.simpleName)
                            }
                        }
                        append(r)
                    }
                }) { Text("一键诊断（两引擎版本）") }
            }
        }

        // ── 日志 ──
        Card(modifier = Modifier.fillMaxWidth()) {
            Text(
                log,
                modifier = Modifier.padding(12.dp),
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

/** 会话级持有：supervisor 与网关 key（P2 换核心签发的真 key）。 */
object EngineSupervisorHolder {
    val supervisor = cc.fplj.magpiemobile.core.EngineSupervisor()
}

object GatewayKeyHolder {
    val currentKey: String = cc.fplj.magpiemobile.core.GatewayKeyGenerator.generate()
}

object ChatSessionHolder {
    @Volatile private var cached: cc.fplj.magpiemobile.core.StreamingChatSession? = null
    @Volatile var chatFilePath: String = "/data/local/tmp/magpie-chat.jsonl"

    fun get(cfg: GatewayConfig): cc.fplj.magpiemobile.core.StreamingChatSession {
        val existing = cached
        if (existing != null) return existing
        val s = cc.fplj.magpiemobile.core.StreamingChatSession(
            cc.fplj.magpiemobile.core.StreamingChatClient(cfg),
            cc.fplj.magpiemobile.core.ChatClient(cfg),
            cc.fplj.magpiemobile.core.SessionStore(java.io.File(chatFilePath)),
            defaultModel = "deepseek/deepseek-chat", // P2 从 /v1/models 动态选
        )
        cached = s
        return s
    }
}
