package cc.fplj.magpiemobile

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class SystemStabilityUiState(
    val networkState: NetworkState = NetworkState(),
    val batteryStatus: BatteryOptimizationStatus = BatteryOptimizationStatus(),
    val memoryTrimNotice: String = "",
)

/**
 * ViewModel managing Device Stability, Network Hot-Switching, and Power Governance (P4-1).
 */
class StabilityViewModel(application: Application) : AndroidViewModel(application) {

    private val networkMonitor = NetworkConnectivityMonitor(application)
    private val mutable = MutableStateFlow(
        SystemStabilityUiState(
            networkState = networkMonitor.state.value,
            batteryStatus = BatteryOptimizationHelper.checkStatus(application)
        )
    )
    val state = mutable.asStateFlow()

    private val memoryCallback = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            val notice = when (level) {
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL -> "系统可用内存极低 (CRITICAL)，请注意减少多模型并发"
                ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW -> "系统内存处于低位 (LOW)"
                else -> ""
            }
            if (notice.isNotEmpty()) {
                mutable.update { it.copy(memoryTrimNotice = notice) }
            }
        }

        override fun onConfigurationChanged(newConfig: Configuration) {}
        override fun onLowMemory() {
            mutable.update { it.copy(memoryTrimNotice = "系统触发低内存警告 (onLowMemory)") }
        }
    }

    init {
        networkMonitor.startMonitoring()
        application.registerComponentCallbacks(memoryCallback)
        refresh()
    }

    fun refresh() {
        networkMonitor.refresh()
        val currentBattery = BatteryOptimizationHelper.checkStatus(getApplication())
        mutable.update {
            it.copy(
                networkState = networkMonitor.state.value,
                batteryStatus = currentBattery
            )
        }
    }

    fun clearMemoryNotice() {
        mutable.update { it.copy(memoryTrimNotice = "") }
    }

    override fun onCleared() {
        super.onCleared()
        networkMonitor.stopMonitoring()
        getApplication<Application>().unregisterComponentCallbacks(memoryCallback)
    }
}
