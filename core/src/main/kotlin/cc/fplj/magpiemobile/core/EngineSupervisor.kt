package cc.fplj.magpiemobile.core

/**
 * 引擎监督状态机（纯 JVM，不依赖 Android）。
 *
 * 职责（plan.md §4.6 的可测试核心）：
 * - 区分 [StopReason]：用户停止不自动复活；崩溃/异常退出按有限退避恢复
 * - 默认退避序列：1s → 2s → 4s，最大 3 次自动恢复；超过即进入 FAILED，
 *   等待用户显式启动（不无限重试，避免违背用户停止意图或耗电）
 * - 状态迁移由外部执行器（Android 端 Service）驱动；本类只做决策
 *
 * 契约基线：Magpie CLI 以进程方式长期运行（magpie serve），
 * 退出码非 0 视为异常退出；被外部 destroy 视为用户停止。
 */
class EngineSupervisor(
    private val maxAutoRestart: Int = 3,
    private val clock: () -> Long = System::nanoTime,
) {
    enum class State { STOPPED, STARTING, RUNNING, BACKOFF, FAILED }

    enum class StopReason { USER_STOP, CRASH, TIMEOUT }

    data class Snapshot(
        val state: State,
        val restartCount: Int,
        val nextRestartDelayMs: Long?,
    )

    private var state: State = State.STOPPED
    private var restartCount: Int = 0
    private var lastTransitionAt: Long = clock()
    private var scheduledRestartDelayMs: Long? = null

    init {
        require(maxAutoRestart >= 0) { "maxAutoRestart 必须 >= 0" }
    }

    @Synchronized
    fun onUserStart(): Decision {
        lastTransitionAt = clock()
        return when (state) {
            State.RUNNING, State.STARTING -> Decision.None
            else -> {
                restartCount = 0
                scheduledRestartDelayMs = null
                state = State.STARTING
                Decision.StartProcess(0)
            }
        }
    }

    @Synchronized
    fun onProcessUp(): Decision {
        lastTransitionAt = clock()
        if (state != State.STARTING) return Decision.None
        scheduledRestartDelayMs = null
        state = State.RUNNING
        return Decision.None
    }

    @Synchronized
    fun onProcessExited(reason: StopReason): Decision {
        lastTransitionAt = clock()
        when (reason) {
            // 用户停止/超时强杀：绝不自动复活（plan.md §4.6）
            StopReason.USER_STOP, StopReason.TIMEOUT -> {
                scheduledRestartDelayMs = null
                state = State.STOPPED
                return Decision.None
            }
            StopReason.CRASH -> {
                // 忽略停止后的迟到退出与同一次退出的重复通知。
                if (state != State.STARTING && state != State.RUNNING) return Decision.None
                if (restartCount >= maxAutoRestart) {
                    scheduledRestartDelayMs = null
                    state = State.FAILED
                    return Decision.None
                }
                val delay = backoffMillis(restartCount)
                restartCount += 1
                scheduledRestartDelayMs = delay
                state = State.BACKOFF
                return Decision.ScheduleRestart(delay)
            }
        }
    }

    /** 确定性启动失败（例如未就绪超时）；保留 FAILED，不进入恢复排期。 */
    @Synchronized
    fun onPermanentFailure(): Decision {
        if (state == State.STOPPED) return Decision.None
        lastTransitionAt = clock()
        scheduledRestartDelayMs = null
        state = State.FAILED
        return Decision.None
    }

    @Synchronized
    fun onBackoffElapsed(): Decision {
        if (state != State.BACKOFF) return Decision.None
        lastTransitionAt = clock()
        scheduledRestartDelayMs = null
        state = State.STARTING
        return Decision.StartProcess(restartCount)
    }

    @Synchronized
    fun snapshot(): Snapshot = Snapshot(state, restartCount, scheduledRestartDelayMs)

    private fun backoffMillis(attempt: Int): Long = (1000L shl attempt.coerceIn(0, 3))

    sealed class Decision {
        object None : Decision()
        data class StartProcess(val attempt: Int) : Decision()
        data class ScheduleRestart(val delayMs: Long) : Decision()
    }
}
