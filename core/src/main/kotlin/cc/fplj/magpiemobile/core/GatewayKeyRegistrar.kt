package cc.fplj.magpiemobile.core

/**
 * 网关 key 注册命令生成（P2 联调桥块）。
 *
 * 背景：App 持久化的 gateway key（GatewayKeyHolder）必须同步注册到引擎侧，
 * M2 补丁生效后 loopback 请求也需有效 key。注册走核心 CLI 单命令
 * （设备 shell 策略：无管道/重定向）。
 *
 * 注意：gateway-key add 输出的 sk-magpie-key-… 是引擎生成的；
 * App 本地生成的 key 引擎不认识——所以正确流程是“引擎 add → 读回 key → App 保存”，
 * 而不是反向。此组件生成**读取引擎已注册 key** 的命令清单与 App key 对齐检查。
 */
object GatewayKeyRegistrar {

    /** 列出引擎已注册的网关密钥（单命令）。 */
    fun listKeysCmd(exePath: String): String =
        "$exePath gateway-key list"

    /** 注册一个命名 key（引擎生成并打印，App 从 stdout 捕获保存）。 */
    fun addKeyCmd(exePath: String, name: String): String {
        require(name.matches(Regex("[A-Za-z0-9_-]{1,64}"))) { "key 名只允许字母数字-_：$name" }
        return "$exePath gateway-key add \"$name\""
    }

    /** 对齐检查：App 持久化 key 是否在引擎列表输出中。 */
    fun appKeyRegistered(engineListOutput: String, appKey: String): Boolean =
        engineListOutput.contains(appKey)
}
