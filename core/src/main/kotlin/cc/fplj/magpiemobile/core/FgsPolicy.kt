package cc.fplj.magpiemobile.core

/**
 * 前台服务策略决策表（plan.md §4.6 的可测试核心，纯逻辑无 Android 依赖）。
 *
 * 规则（全部来自已批准计划，Android 官方文档钉住）：
 * - SDK 29..33（Android 10–13）：普通 FGS（无类型强制）
 * - SDK 34+（Android 14+）：specialUse + FOREGROUND_SERVICE_SPECIAL_USE + PROPERTY_SPECIAL_USE_FGS_SUBTYPE
 * - 绝不使用 dataSync 充当全天网关（Android 15+ 6h/24h 限制）
 * - 用途说明固定为"用户主动开启、通知可见可停的本地 AI 网关"（侧载无 Play 审查但语义必须真实）
 */
object FgsPolicy {

    const val SPECIAL_USE_SUBTYPE =
        "user-initiated, notification-persistent, user-stoppable local AI gateway"

    sealed class Policy {
        object LegacyForegroundService : Policy()
        data class SpecialUse(val subtype: String) : Policy()
    }

    fun policyFor(sdkInt: Int): Policy = when {
        sdkInt >= 34 -> Policy.SpecialUse(SPECIAL_USE_SUBTYPE)
        sdkInt >= 29 -> Policy.LegacyForegroundService
        else -> throw IllegalArgumentException("低于 minSdk 29 不支持，got $sdkInt")
    }

    /** 禁止伪装的 FGS 类型黑名单（调用方在 manifest 审查用）。 */
    val forbiddenTypes = setOf("dataSync", "mediaPlayback", "connectedDevice", "phoneCall")
}
