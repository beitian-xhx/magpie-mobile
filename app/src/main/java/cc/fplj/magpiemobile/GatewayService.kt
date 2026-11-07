package cc.fplj.magpiemobile

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.Log
import cc.fplj.magpiemobile.core.EngineCommand
import cc.fplj.magpiemobile.core.EngineEnvironment
import cc.fplj.magpiemobile.core.EnginePaths
import cc.fplj.magpiemobile.core.GatewayEngineController
import cc.fplj.magpiemobile.core.PayloadVerifier
import java.net.InetSocketAddress
import java.net.Socket

/** A foreground owner, with process lifecycle handled by the regression-tested controller. */
class GatewayService : Service() {
    private var controller: GatewayEngineController? = null
    private var owner = 0L
    @Volatile private var foregroundReady = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val adminKey = ByteArray(32).also(java.security.SecureRandom()::nextBytes)
        .joinToString("") { (it.toInt() and 255).toString(16).padStart(2, '0') }

    override fun onCreate() {
        super.onCreate()
        owner = GatewayRuntime.attach()
        NativeCoreSession.attach(owner, adminKey)
        try {
            getSystemService(NotificationManager::class.java).createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Magpie 网关", NotificationManager.IMPORTANCE_LOW),
            )
            val notification = notification("正在启动网关…")
            if (Build.VERSION.SDK_INT >= 34) {
                startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, notification)
            }
            foregroundReady = true
        } catch (e: Exception) {
            report(GatewayEngineController.Status(
                GatewayEngineController.Phase.FAILED,
                "前台服务启动失败：" + (e.message ?: e.javaClass.simpleName),
            ))
            stopSelf()
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            GatewayRuntime.stoppedWithoutService()
            controller?.stop()
            stopSelf()
            return START_NOT_STICKY
        }
        if (!foregroundReady) return START_NOT_STICKY
        if (controller != null) return START_NOT_STICKY // repeated taps cannot create parallel children
        controller = GatewayEngineController(
            launch = {
                val layout = EnginePaths.buildLayout(applicationInfo.nativeLibraryDir, filesDir, cacheDir)
                val engineEnvironment = EngineEnvironment.prepare(layout) + mapOf(
                    "MAGPIE_WEB_KEY" to adminKey,
                    "MAGPIE_ADDR" to "127.0.0.1:3425",
                )
                for (exe in listOf(EnginePaths.magpieExecutable(layout), EnginePaths.bunExecutable(layout))) {
                    val verdict = PayloadVerifier.verify(exe)
                    check(verdict !is PayloadVerifier.Verdict.Fail) {
                        "载荷自检失败：" + (verdict as PayloadVerifier.Verdict.Fail).reason
                    }
                }
                check(!listening(EngineCommand.DEFAULT_GATEWAY_PORT) && !listening(EngineCommand.DEFAULT_ADMIN_PORT)) {
                    "端口 3425 或 3430 已被占用，不能确认本应用的网关启动；请停止占用该端口的服务后重试"
                }
                ProcessBuilder(EngineCommand.serve(EnginePaths.magpieExecutable(layout).absolutePath))
                    .directory(layout.homeDir)
                    .redirectErrorStream(true)
                    .apply { environment().apply { clear(); putAll(engineEnvironment) } }
                    .start()
            },
            ready = { listening(EngineCommand.DEFAULT_GATEWAY_PORT) && listening(EngineCommand.DEFAULT_ADMIN_PORT) },
            report = ::report,
        ).also { it.start() }
        return START_NOT_STICKY
    }

    private fun report(status: GatewayEngineController.Status) {
        GatewayRuntime.publish(owner, status)
        if (!GatewayRuntime.isCurrent(owner)) return
        val safe = GatewayRuntime.status.value
        if (status.phase == GatewayEngineController.Phase.RUNNING) {
            NativeCoreSession.connect(owner, filesDir)
        } else {
            NativeCoreSession.suspend(owner)
        }
        Log.i("MagpieGateway", safe.phase.name + ": " + safe.message)
        if (foregroundReady) {
            try {
                getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification(safe.message))
            } catch (_: SecurityException) {
                // Denied notifications do not hide the state/error in the app.
            }
        }
        if (status.phase == GatewayEngineController.Phase.FAILED) {
            mainHandler.post {
                if (GatewayRuntime.isCurrent(owner)) stopSelf()
            }
        }
    }

    private fun notification(message: String): Notification {
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val stop = PendingIntent.getService(this, 1, Intent(this, GatewayService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Magpie 网关")
            .setContentText(message.take(250))
            .setStyle(Notification.BigTextStyle().bigText(message.take(1000)))
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_menu_close_clear_cancel, "停止网关", stop).build())
            .build()
    }

    override fun onDestroy() {
        // Preserve startup failures on screen; stopping a failed service must not overwrite FAILED.
        controller?.stop()
        NativeCoreSession.disconnect(owner)
        foregroundReady = false
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        const val ACTION_STOP = "cc.fplj.magpiemobile.STOP_GATEWAY"
        private const val CHANNEL_ID = "magpie_gateway"
        private const val NOTIFICATION_ID = 41

        private fun listening(port: Int): Boolean = try {
            Socket().use { it.connect(InetSocketAddress("127.0.0.1", port), 100) }
            true
        } catch (_: Exception) { false }
    }
}
