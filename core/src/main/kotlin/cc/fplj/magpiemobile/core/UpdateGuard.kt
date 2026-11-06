package cc.fplj.magpiemobile.core

/**
 * 更新安全守则（plan.md §4.1/§4.6 的可测试核心）。
 *
 * 规则：
 * - native 载荷（magpie/bun）只能随签名 APK 升级：任何"运行时下载替换 native"请求一律拒绝
 * - 插件 JS 允许手动更新到已固定版本；更新前必须记录回滚点；固定版本格式 name@1.2.3
 * - 无任何"自动更新引擎"路径
 */
object UpdateGuard {

    sealed class NativeDecision {
        object AllowedViaApkOnly : NativeDecision()
        data class Rejected(val reason: String) : NativeDecision()
    }

    /** native 更新请求裁决：来源必须是 apk。 */
    fun decideNativeUpdate(source: String): NativeDecision =
        when (source) {
            "apk" -> NativeDecision.AllowedViaApkOnly
            "download", "cache", "runtime" -> NativeDecision.Rejected(
                "native 不可从 $source 更新：W^X 与 plan.md §4.1 禁止（仅随签名 APK）",
            )
            else -> NativeDecision.Rejected("未知来源：$source")
        }

    /** 插件固定版本名合法性（name@x.y.z）。 */
    fun isPinnedPluginSpec(spec: String): Boolean {
        val at = spec.lastIndexOf('@') ?: return false
        if (at <= 0 || at == spec.length - 1) return false
        val version = spec.substring(at + 1)
        return version.matches(Regex("\\d+(\\.\\d+){0,2}"))
    }
}
