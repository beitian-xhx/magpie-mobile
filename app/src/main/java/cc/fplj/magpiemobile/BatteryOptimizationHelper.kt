package cc.fplj.magpiemobile

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings

data class BatteryOptimizationStatus(
    val isIgnoringOptimizations: Boolean = false,
    val canRequest: Boolean = false,
    val description: String = "未开启电池优化白名单",
)

/**
 * Checks and guides Battery Optimization / Doze exemptions for background stability (P4-1).
 * Complies with Android foreground service requirements to avoid aggressive LMK/Doze termination.
 */
object BatteryOptimizationHelper {

    fun checkStatus(context: Context): BatteryOptimizationStatus {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm == null) {
            return BatteryOptimizationStatus(
                isIgnoringOptimizations = false,
                canRequest = false,
                description = "系统电源管理器不可用"
            )
        }

        val packageName = context.packageName
        val isIgnoring = pm.isIgnoringBatteryOptimizations(packageName)

        val desc = if (isIgnoring) {
            "已加入电池优化白名单（后台与熄屏运行稳定）"
        } else {
            "未加入白名单（长耗时会话或熄屏可能被系统省电策略终止）"
        }

        return BatteryOptimizationStatus(
            isIgnoringOptimizations = isIgnoring,
            canRequest = !isIgnoring,
            description = desc
        )
    }

    /**
     * Builds safe system settings intent to guide user to battery optimization exemptions.
     * Uses explicit package URI where supported.
     */
    fun createOptimizationSettingsIntent(context: Context): Intent? {
        val packageName = context.packageName
        val specificIntent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:$packageName")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

        val pm = context.packageManager
        if (specificIntent.resolveActivity(pm) != null) {
            return specificIntent
        }

        val generalIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }
        if (generalIntent.resolveActivity(pm) != null) {
            return generalIntent
        }

        return null
    }
}
