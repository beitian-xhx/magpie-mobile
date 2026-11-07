package cc.fplj.magpiemobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import cc.fplj.magpiemobile.core.EngineCommand
import cc.fplj.magpiemobile.core.EnginePaths
import cc.fplj.magpiemobile.core.EngineProcessRunner
import cc.fplj.magpiemobile.core.EngineSupervisor
import cc.fplj.magpiemobile.core.FgsPolicy
import cc.fplj.magpiemobile.core.PayloadVerifier
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * P1 GatewayService：前台服务持有 Magpie 引擎进程（plan.md §4.6）。
 *
 * 接线（全部 core 组件已测）：
 * - FgsPolicy 按 SDK 决策 FGS 类型（34+ specialUse）
 * - EnginePaths 从 applicationInfo 解析载荷
 * - PayloadVerifier 启动前自检（失败不启动引擎，通知报原因）
 * - EngineCommand 构造 serve 命令；EngineProcessRunner 执行；EngineSupervisor 监督
 *
 * 生命周期纪律：用户停止（onDestroy+stopWithTask）/任务移除 → USER_STOP 不复活；
 * 崩溃 → BackoffPolicy 有限恢复；通知常驻可停止（plan.md §4.6）。
 */
class GatewayService : Service() {

    private val supervisor = EngineSupervisor()
    private val runner = EngineProcessRunner(timeout = java.time.Duration.ofSeconds(20))
    @Volatile private var process: java.lang.Process? = null
    @Volatile private var supervisorThread: Thread? = null
    @Volatile private var userRequestedStop = false

    override fun onCreate() {
        super.onCreate()
        startForegroundWithPolicy()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        userRequestedStop = false
        ensureEngineRunning()
        return START_NOT_STICKY // 重启语义由 EngineSupervisor 管理，不用系统粘性
    }

    private fun startForegroundWithPolicy() {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= 26) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Magpie 网关", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val n: Notification = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Magpie 网关运行中")
            .setContentText("本地 AI 网关 · 点击停止请在应用内操作")
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setOngoing(true)
            .build()

        when (val policy = FgsPolicy.policyFor(Build.VERSION.SDK_INT)) {
            is FgsPolicy.Policy.SpecialUse -> {
                if (Build.VERSION.SDK_INT >= 34) {
                    startForeground(
                        NOTIF_ID,
                        n,
                        ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE,
                    )
                } else {
                    startForeground(NOTIF_ID, n)
                }
            }
            is FgsPolicy.Policy.LegacyForegroundService -> startForeground(NOTIF_ID, n)
        }
    }

    private fun ensureEngineRunning() {
        if (process != null) return
        val decision = supervisor.onUserStart()
        if (decision !is EngineSupervisor.Decision.StartProcess) return

        val layout = EnginePaths.buildLayout(
            applicationInfo.nativeLibraryDir,
            filesDir,
            cacheDir,
        )
        // 启动前自检：任一载荷不合格 → 通知失败，不进入 BACKOFF（属 NotInstalled 语义）
        for (exe in listOf(EnginePaths.magpieExecutable(layout), EnginePaths.bunExecutable(layout))) {
            val v = PayloadVerifier.verify(exe)
            if (v is PayloadVerifier.Verdict.Fail) {
                supervisor.onProcessExited(EngineSupervisor.StopReason.USER_STOP)
                notifyError("载荷自检失败：" + v.reason)
                stopSelf()
                return
            }
        }

        val cmd = EngineCommand.serve(EnginePaths.magpieExecutable(layout).absolutePath)
        val thread = Thread {
            try {
                val runner2 = EngineProcessRunner() // 仅为环境构造复用；进程由本类直管以便流式监督
                val pb = ProcessBuilder(cmd)
                val env = pb.environment()
                env["HOME"] = layout.homeDir.absolutePath
                env["TMPDIR"] = layout.cacheDir.absolutePath
                env["XDG_CONFIG_HOME"] = layout.configDir.absolutePath
                env["XDG_CACHE_HOME"] = layout.cacheDir.absolutePath
                env["MAGPIE_BUN"] = EnginePaths.bunExecutable(layout).absolutePath
                env["PATH"] = "/system/bin:/system/xbin"
                val p = pb.start()
                process = p
                supervisor.onProcessUp()
                p.waitFor() // 长驻：serve 不退出
                val exitedNormally = userRequestedStop
                val reason = if (exitedNormally) EngineSupervisor.StopReason.USER_STOP
                             else EngineSupervisor.StopReason.CRASH
                val d = supervisor.onProcessExited(reason)
                process = null
                when (d) {
                    is EngineSupervisor.Decision.ScheduleRestart -> {
                        notifyError("引擎异常退出，" + d.delayMs / 1000 + "s 后自动恢复")
                        Thread.sleep(d.delayMs)
                        if (!userRequestedStop) ensureEngineRunning()
                    }
                    is EngineSupervisor.Decision.StartProcess -> ensureEngineRunning()
                    else -> notifyError("引擎已停止")
                }
            } catch (e: InterruptedException) {
                // 用户停止：destroy 由 onDestroy 触发
            } catch (e: Exception) {
                supervisor.onProcessExited(EngineSupervisor.StopReason.CRASH)
                notifyError("引擎异常：" + (e.message ?: e.javaClass.simpleName))
            }
        }.also { it.isDaemon = true; it.start() }
        supervisorThread = thread
    }

    private fun stopEngine() {
        userRequestedStop = true
        supervisor.onUserStart() // no-op if running; keeps API honest
        process?.destroy()
        process?.waitFor(5, TimeUnit.SECONDS)
        process?.destroyForcibly()
        process = null
        supervisor.onProcessExited(EngineSupervisor.StopReason.USER_STOP)
    }

    private fun notifyError(text: String) {
        val nm = getSystemService(NotificationManager::class.java)
        val n = Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Magpie 网关")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.stat_notify_error)
            .build()
        nm.notify(NOTIF_ERR_ID, n)
    }

    override fun onDestroy() {
        stopEngine()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL_ID = "magpie_gateway"
        private const val NOTIF_ID = 41
        private const val NOTIF_ERR_ID = 42
    }
}
