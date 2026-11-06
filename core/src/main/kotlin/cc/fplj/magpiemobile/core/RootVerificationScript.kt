package cc.fplj.magpiemobile.core

/**
 * P0-2 root 验证命令清单生成（Lead 的设备自动化用，纯逻辑）。
 *
 * 每条命令独立可执行（设备 shell 策略：仅单条命令、无管道/重定向/脚本）。
 * 验证序列：pm install → am start → 等待 → curl healthz → 判定。
 * 判定规则（P0-2 真机门槛）：
 * - magpie 与 bun 的 healthz 字段均以 "ok" 开头 → PASS
 * - 任一 exit=N（N!=0）或 pending → FAIL（附 stderr 摘要）
 */
object RootVerificationScript {

    const val APK_DEVICE_PATH = "/sdcard/Download/app-debug.apk"
    const val PKG = "cc.fplj.magpiemobile"
    const val HEALTHZ_URL = "http://127.0.0.1:3431/healthz"

    fun installCmd(apkPath: String = APK_DEVICE_PATH): String =
        "pm install -r $apkPath"

    fun launchCmd(): String =
        "am start -n $PKG/.MainActivity"

    fun healthzCmd(): String =
        "curl -s --max-time 5 $HEALTHZ_URL"

    /** 解析 healthz JSON → 判定结果（curl 输出原文，宽松解析）。 */
    fun judge(healthzJson: String): Verdict {
        val magpie = extract(healthzJson, "magpie") ?: return Verdict.Fail("healthz 无 magpie 字段")
        val bun = extract(healthzJson, "bun") ?: return Verdict.Fail("healthz 无 bun 字段")
        return when {
            magpie.startsWith("ok") && bun.startsWith("ok") ->
                Verdict.Pass(magpie, bun)
            else -> Verdict.Fail("magpie=$magpie bun=$bun")
        }
    }

    private fun extract(json: String, key: String): String? {
        // JSON 形状 "key":"…"；用 CHAR 常量避免转义嵌套
        val q: String = "\""
        val marker: String = q + key + q + ":" + q
        val start: Int = json.indexOf(marker).takeIf { it >= 0 } ?: return null
        val valueStart: Int = start + marker.length
        val end: Int = json.indexOf(q, valueStart).takeIf { it >= 0 } ?: return null
        return json.substring(valueStart, end)
    }

    sealed class Verdict {
        data class Pass(val magpie: String, val bun: String) : Verdict()
        data class Fail(val reason: String) : Verdict()
    }
}
