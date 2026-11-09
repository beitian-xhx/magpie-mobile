package cc.fplj.magpiemobile

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class NetworkType {
    WIFI,
    CELLULAR,
    ETHERNET,
    OTHER,
    NONE,
}

data class NetworkState(
    val connected: Boolean = false,
    val type: NetworkType = NetworkType.NONE,
    val validated: Boolean = false,
    val description: String = "未连接网络",
)

/**
 * Monitors network availability and hot-switching on Android devices (P4-1).
 * Detects transitions between Wi-Fi and Cellular to prevent billing retries during network dropouts.
 */
class NetworkConnectivityMonitor(private val context: Context) {

    private val mutableState = MutableStateFlow(NetworkState())
    val state = mutableState.asStateFlow()

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private var callback: ConnectivityManager.NetworkCallback? = null

    init {
        refresh()
    }

    @Synchronized
    fun startMonitoring() {
        if (callback != null || connectivityManager == null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                updateFromActiveNetwork()
            }

            override fun onLost(network: Network) {
                updateFromActiveNetwork()
            }

            override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
                updateFromActiveNetwork()
            }
        }
        try {
            val request = NetworkRequest.Builder()
                .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                .build()
            connectivityManager.registerNetworkCallback(request, cb)
            callback = cb
        } catch (_: Exception) {
            // In restricted test environments or permission limits, fallback to poll
        }
        updateFromActiveNetwork()
    }

    @Synchronized
    fun stopMonitoring() {
        val cb = callback ?: return
        try {
            connectivityManager?.unregisterNetworkCallback(cb)
        } catch (_: Exception) {}
        callback = null
    }

    fun refresh() {
        updateFromActiveNetwork()
    }

    private fun updateFromActiveNetwork() {
        val cm = connectivityManager
        if (cm == null) {
            mutableState.value = NetworkState(
                connected = false,
                type = NetworkType.NONE,
                validated = false,
                description = "系统网络管理器不可用"
            )
            return
        }

        try {
            val activeNetwork = cm.activeNetwork
            val capabilities = cm.getNetworkCapabilities(activeNetwork)
            mutableState.value = evaluateNetworkState(capabilities)
        } catch (e: Exception) {
            mutableState.value = NetworkState(
                connected = false,
                type = NetworkType.NONE,
                validated = false,
                description = "检测网络状态异常: " + (e.message ?: "未知错误")
            )
        }
    }

    companion object {
        fun evaluateNetworkState(capabilities: NetworkCapabilities?): NetworkState {
            if (capabilities == null) {
                return NetworkState(connected = false, type = NetworkType.NONE, validated = false, description = "无网络连接")
            }

            val hasInternet = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            val isValidated = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)

            val type = when {
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
                capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
                else -> NetworkType.OTHER
            }

            val desc = buildString {
                append(when (type) {
                    NetworkType.WIFI -> "Wi-Fi 网络"
                    NetworkType.CELLULAR -> "移动蜂窝网络"
                    NetworkType.ETHERNET -> "以太网"
                    NetworkType.OTHER -> "其他网络"
                    NetworkType.NONE -> "无连接"
                })
                if (isValidated) append(" (已连接互联网)")
                else if (hasInternet) append(" (待验证连接)")
                else append(" (不可访问互联网)")
            }

            return NetworkState(
                connected = hasInternet,
                type = type,
                validated = isValidated,
                description = desc
            )
        }
    }
}
