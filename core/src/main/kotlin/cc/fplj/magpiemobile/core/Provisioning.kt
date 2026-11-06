package cc.fplj.magpiemobile.core

import java.io.File

/**
 * 引擎就绪状态判定（plan.md §4.4 仪表盘“状态头”的数据源，纯逻辑无 Android 依赖）。
 *
 * 规则：
 * - 载荷缺失 → NotInstalled（引导安装/说明）
 * - 载荷在但配置目录未初始化 → NeedsSetup（首次启动向导）
 * - supervisor RUNNING → Running；STARTING/BACKOFF → Starting；
 *   FAILED → Failed(reason)；STOPPED → Ready
 * UI 侧按 sealed 类型渲染，不做隐式字符串映射。
 */
sealed class ProvisioningState {
    object NotInstalled : ProvisioningState()
    object NeedsSetup : ProvisioningState()
    object Ready : ProvisioningState()
    object Starting : ProvisioningState()
    data class Running(val uptimeHintMs: Long?) : ProvisioningState()
    data class Failed(val lastError: String) : ProvisioningState()
}

class ProvisioningEvaluator {

    fun evaluate(
        layout: EnginePaths.Layout?,
        supervisor: EngineSupervisor,
    ): ProvisioningState {
        if (layout == null) return ProvisioningState.NotInstalled
        val magpie = EnginePaths.magpieExecutable(layout)
        val bun = EnginePaths.bunExecutable(layout)
        if (!magpie.exists() || !bun.exists()) return ProvisioningState.NotInstalled
        if (!layout.configDir.isDirectory) return ProvisioningState.NeedsSetup

        return when (supervisor.snapshot().state) {
            EngineSupervisor.State.RUNNING -> ProvisioningState.Running(null)
            EngineSupervisor.State.STARTING, EngineSupervisor.State.BACKOFF -> ProvisioningState.Starting
            EngineSupervisor.State.FAILED -> ProvisioningState.Failed("引擎连续崩溃，已停止自动恢复")
            EngineSupervisor.State.STOPPED -> ProvisioningState.Ready
        }
    }
}
