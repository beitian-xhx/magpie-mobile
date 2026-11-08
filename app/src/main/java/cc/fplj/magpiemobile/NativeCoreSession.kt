package cc.fplj.magpiemobile

import cc.fplj.magpiemobile.core.CoreManagementClient
import cc.fplj.magpiemobile.core.GatewayConfig
import cc.fplj.magpiemobile.core.GatewayCredentialStore
import cc.fplj.magpiemobile.core.GatewayEngineController
import cc.fplj.magpiemobile.core.GatewayKeyProvisioner
import cc.fplj.magpiemobile.core.ManagerCredential
import java.io.File
import java.util.concurrent.Executors
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Private credentials never enter Flow, Intent, healthz, logs, clipboard or Compose state. */
object NativeCoreSession {
    enum class Phase { DISCONNECTED, CONNECTING, READY, ERROR }
    data class Snapshot(val phase: Phase, val message: String) {
        val ready: Boolean get() = phase == Phase.READY
    }
    private data class Binding(val owner: Long, val credential: ManagerCredential) {
        var connecting = false
        var config: GatewayConfig? = null
        var providers: cc.fplj.magpiemobile.core.ProviderManagementClient? = null
    }
    private val lock = Any()
    private var binding: Binding? = null
    private val mutableStatus = MutableStateFlow(Snapshot(Phase.DISCONNECTED, "网关未连接"))
    val status = mutableStatus.asStateFlow()
    // All key provisioning is serial, including cancelled/old Service runs.
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "MagpieCoreConnect").apply { isDaemon = true }
    }

    fun attach(owner: Long, adminKey: String) = synchronized(lock) {
        binding = Binding(owner, ManagerCredential.fromKey(adminKey))
        mutableStatus.value = Snapshot(Phase.DISCONNECTED, "等待网关端口就绪，随后验证核心安全能力")
    }

    fun connect(owner: Long, filesDir: File) {
        val current = synchronized(lock) {
            val current = binding ?: return
            if (current.owner != owner || current.connecting) return
            current.connecting = true
            current.config = null
            mutableStatus.value = Snapshot(Phase.CONNECTING, "正在鉴权管理会话并注册/核对本机客户端密钥…")
            current
        }
        worker.execute {
            try {
                if (!isRunningOwner(current)) return@execute
                val client = CoreManagementClient(current.credential)
                val capabilities = client.capabilities()
                check(capabilities.coreVersion == BuildConfig.CORE_VERSION) { "Core version differs from bundled version" }
                val store = GatewayCredentialStore(File(filesDir.canonicalFile, "app-gateway-credential.json")) // Android user-0 system path may be an alias
                val key = GatewayKeyProvisioner(client, store).provision()
                val config = GatewayConfig(gatewayKey = key.secret)
                val hello = cc.fplj.magpiemobile.core.GatewayHelloClient(config).hello()
                check(hello.version == capabilities.coreVersion) { "Gateway and management core versions differ" }
                synchronized(lock) {
                    if (binding === current && isRunningOwner(current)) {
                        current.config = config
                        mutableStatus.value = Snapshot(Phase.READY, "安全能力验证通过；本机客户端密钥已在核心登记并私有保存")
                    }
                }
            } catch (e: Exception) {
                synchronized(lock) {
                    if (binding === current && isRunningOwner(current)) {
                        // Do not expose arbitrary upstream errors which may carry a credential.
                        current.config = null
                        val reason = when (e) {
                            is cc.fplj.magpiemobile.core.ManagementHttpException -> "管理请求失败：HTTP " + e.httpCode
                            is cc.fplj.magpiemobile.core.ManagementCapabilitiesMismatch -> "核心移动安全能力不兼容，已拒绝注册密钥"
                            is cc.fplj.magpiemobile.core.ManagementExecutionUnknown -> "密钥操作结果未知；下次连接会先重读核心列表，不自动重复写入"
                            is cc.fplj.magpiemobile.core.GatewayKeyProvisioningException -> "密钥已撤销/受限/过期或身份不唯一，不会自动创建替代密钥"
                            is cc.fplj.magpiemobile.core.GatewayCredentialStoreException -> "私有凭据文件异常，未覆盖原文件；请保留数据再排查"
                            else -> "核心安全连接失败（" + e.javaClass.simpleName + "）"
                        }
                        mutableStatus.value = Snapshot(Phase.ERROR, reason)
                    }
                }
            } finally {
                synchronized(lock) { if (binding === current) current.connecting = false }
            }
        }
    }

    fun suspend(owner: Long) = synchronized(lock) {
        val current = binding ?: return
        if (current.owner != owner) return
        current.config = null
        mutableStatus.value = Snapshot(Phase.DISCONNECTED, "网关暂未就绪，暂停客户端请求")
    }

    fun disconnect(owner: Long) = synchronized(lock) {
        if (binding?.owner != owner) return
        binding = null
        mutableStatus.value = Snapshot(Phase.DISCONNECTED, "网关已断开，管理凭据已从会话释放")
    }

    fun gatewayConfig(): GatewayConfig = synchronized(lock) {
        val current = binding ?: error("请先启动网关")
        check(isRunningOwner(current)) { "网关尚未就绪" }
        current.config ?: error("核心密钥尚未核对，不能使用本地占位密钥发送请求")
    }

    fun providerSessionId(): Long? = synchronized(lock) {
        binding?.takeIf { isRunningOwner(it) && it.config != null }?.owner
    }

    fun currentGatewayKey(): String? = synchronized(lock) {
        binding?.takeIf { isRunningOwner(it) }?.config?.gatewayKey
    }

    fun managementClient(): cc.fplj.magpiemobile.core.CoreManagementClient = synchronized(lock) {
        val current = binding ?: error("请先启动网关")
        check(isRunningOwner(current) && current.config != null) { "核心安全连接尚未完成" }
        cc.fplj.magpiemobile.core.CoreManagementClient(current.credential)
    }

    fun providerClient(): cc.fplj.magpiemobile.core.ProviderManagementClient = synchronized(lock) {
        val current = binding ?: error("请先启动网关")
        check(isRunningOwner(current) && current.config != null) { "核心安全连接尚未完成" }
        current.providers ?: cc.fplj.magpiemobile.core.ProviderManagementClient(current.credential).also { current.providers = it }
    }

    fun retry(filesDir: File) {
        val owner = synchronized(lock) { binding?.owner } ?: return
        connect(owner, filesDir)
    }

    private fun isRunningOwner(current: Binding): Boolean = !GatewayRuntime.stopping.value && GatewayRuntime.isCurrent(current.owner) &&
        GatewayRuntime.status.value.phase == GatewayEngineController.Phase.RUNNING
}
