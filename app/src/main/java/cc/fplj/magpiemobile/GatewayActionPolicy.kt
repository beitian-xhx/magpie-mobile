package cc.fplj.magpiemobile

/**
 * Pure Service command gate. Persisted START/STOP are UI metadata, not launch permissions.
 * Only a fresh, exact START may create a controller; STOP and the runtime stop barrier win.
 */
object GatewayActionPolicy {
    const val ACTION_START = "cc.fplj.magpiemobile.START_GATEWAY"
    const val ACTION_STOP = "cc.fplj.magpiemobile.STOP_GATEWAY"

    enum class Operation { START, STOP, KEEP_RUNNING, STOP_SELF }

    data class Decision(val operation: Operation, val recordStopIntent: Boolean = false)

    fun decide(
        action: String?,
        mustStop: Boolean,
        foregroundReady: Boolean,
        hasController: Boolean,
        isReplay: Boolean = false,
    ): Decision = when {
        action == ACTION_STOP -> Decision(Operation.STOP, recordStopIntent = true)
        mustStop -> Decision(Operation.STOP)
        hasController -> Decision(Operation.KEEP_RUNNING)
        action == ACTION_START && foregroundReady && !isReplay -> Decision(Operation.START)
        else -> Decision(Operation.STOP_SELF)
    }
}
