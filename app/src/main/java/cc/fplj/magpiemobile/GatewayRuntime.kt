package cc.fplj.magpiemobile

import cc.fplj.magpiemobile.core.GatewayEngineController
import cc.fplj.magpiemobile.core.SecretRedactor
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Service owns the engine. Activity and healthz observe the same immutable status. */
object GatewayRuntime {
    private val mutableStatus = MutableStateFlow(
        GatewayEngineController.Status(GatewayEngineController.Phase.STOPPED, "网关未运行"),
    )
    val status = mutableStatus.asStateFlow()
    private var generation = 0L

    @Synchronized
    fun attach(): Long {
        generation += 1
        mutableStatus.value = GatewayEngineController.Status(
            GatewayEngineController.Phase.STARTING, "前台服务已创建，正在检查载荷…",
        )
        return generation
    }

    @Synchronized
    fun isCurrent(owner: Long): Boolean = owner == generation

    @Synchronized
    fun publish(owner: Long, status: GatewayEngineController.Status) {
        if (owner != generation) return // cancelled/old service cannot overwrite a newer start
        if (mutableStatus.value.phase == GatewayEngineController.Phase.FAILED &&
            status.phase == GatewayEngineController.Phase.STOPPED) return // keep the actual error visible
        mutableStatus.value = status.copy(
            message = SecretRedactor.redact(status.message),
            outputTail = SecretRedactor.redact(status.outputTail),
        )
    }

    @Synchronized
    fun requestStart() {
        val phase = mutableStatus.value.phase
        if (phase == GatewayEngineController.Phase.RUNNING ||
            phase == GatewayEngineController.Phase.STARTING ||
            phase == GatewayEngineController.Phase.BACKOFF) return
        mutableStatus.value = GatewayEngineController.Status(
            GatewayEngineController.Phase.STARTING, "已收到启动请求，正在创建前台服务…",
        )
    }

    @Synchronized
    fun requestFailed(error: Exception) {
        mutableStatus.value = GatewayEngineController.Status(
            GatewayEngineController.Phase.FAILED,
            SecretRedactor.redact("启动请求失败：" + (error.message ?: error.javaClass.simpleName)),
        )
    }

    @Synchronized
    fun stoppedWithoutService() {
        generation += 1
        mutableStatus.value = GatewayEngineController.Status(
            GatewayEngineController.Phase.STOPPED, "网关已停止",
        )
    }
}
