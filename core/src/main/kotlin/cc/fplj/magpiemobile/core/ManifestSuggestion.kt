package cc.fplj.magpiemobile.core

/**
 * AndroidManifest 声明核对器（GatewayService 壳的清单正确性纯逻辑，plan.md §4.1/§4.6）。
 *
 * 用法：壳的 manifest 写好后，把关键声明喂给 check()，缺项列出——
 * CI 可用 instrumented/解析测试跑，防止"忘了 allowBackup=false"这类静默事故。
 */
object ManifestSuggestion {

    data class Required(
        val allowBackup: Boolean = false,
        val fgsPermissionSpecialUse: Boolean = true,   // FOREGROUND_SERVICE_SPECIAL_USE
        val fgsPropertySpecialUseSubtype: Boolean = true,
        val serviceExportedFalse: Boolean = true,
        val internetPermission: Boolean = true,
        val noSystemBackup: Boolean = true,            // android:allowBackup="false"
    )

    data class Actual(
        val allowBackup: Boolean?,
        val hasFgsSpecialUsePermission: Boolean,
        val hasSpecialUseSubtypeProperty: Boolean,
        val serviceExported: Boolean?,
        val hasInternetPermission: Boolean,
    )

    /** 返回缺失/错误项列表；空列表 = manifest 合格。 */
    fun check(actual: Actual): List<String> {
        val problems = mutableListOf<String>()
        if (actual.allowBackup != false) problems.add("allowBackup 必须显式 false（禁系统备份）")
        if (!actual.hasFgsSpecialUsePermission) problems.add("缺 FOREGROUND_SERVICE_SPECIAL_USE 权限")
        if (!actual.hasSpecialUseSubtypeProperty) problems.add("缺 PROPERTY_SPECIAL_USE_FGS_SUBTYPE property")
        if (actual.serviceExported != false) problems.add("GatewayService 必须 exported=false")
        if (!actual.hasInternetPermission) problems.add("缺 INTERNET 权限")
        return problems
    }
}
