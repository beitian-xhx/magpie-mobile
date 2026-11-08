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
            val appShell: AppShellViewModel = androidx.lifecycle.viewmodel.compose.viewModel()
            val shell by appShell.state.collectAsStateWithLifecycle()
            val dark = when (shell.theme) {
                cc.fplj.magpiemobile.preferences.ThemeMode.SYSTEM -> androidx.compose.foundation.isSystemInDarkTheme()
                cc.fplj.magpiemobile.preferences.ThemeMode.LIGHT -> false
                cc.fplj.magpiemobile.preferences.ThemeMode.DARK -> true
            }
            MaterialTheme(colorScheme = if (dark) androidx.compose.material3.darkColorScheme() else androidx.compose.material3.lightColorScheme()) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Dashboard(
                        layout = layout,
                        appShell = appShell,
                        notificationNotice = notificationNotice,
                        onAllowNotifications = ::allowNotifications,
                        onRetryCoreConnection = { NativeCoreSession.retry(filesDir) },
                        onStartGateway = {
                            if (GatewayRuntime.requestStart()) {
                                try {
                                    ContextCompat.startForegroundService(this, Intent(this, GatewayService::class.java).setAction(GatewayService.ACTION_START))
                                    runCatching { cc.fplj.magpiemobile.preferences.AppUiPreferences.open(application).recordGatewayIntent(cc.fplj.magpiemobile.preferences.GatewayIntent.START) }
                                } catch (e: Exception) { GatewayRuntime.requestFailed(e) }
                            }
                        },
                        onStopGateway = {
                            try {
                                // Invalidate callbacks from this run before destruction/late spawn cleanup.
                                val ticket = GatewayRuntime.requestStop()
                                if (!stopService(Intent(this, GatewayService::class.java))) GatewayRuntime.cleanupCompleted(ticket)
                            } catch (e: Exception) { GatewayRuntime.requestFailed(e) }
                            // 停止后尽力记录停止意图；不阻塞停止，异常不影响已执行的停止
                            runCatching { cc.fplj.magpiemobile.preferences.AppUiPreferences.open(application).recordGatewayIntent(cc.fplj.magpiemobile.preferences.GatewayIntent.STOP) }
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
