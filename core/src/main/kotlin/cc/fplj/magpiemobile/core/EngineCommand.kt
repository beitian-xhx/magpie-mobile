package cc.fplj.magpiemobile.core

/**
 * 引擎启动命令构造（plan.md §4.2 GatewayService 的纯逻辑前置）。
 *
 * 起始命令固定为 magpie serve --web=127.0.0.1:<adminPort>：
 * - 网关自动在 <gatewayPort>（默认 3425）
 * - 管理 API 只绑 loopback（plan.md §4.3；M3 补丁后拒绝非 loopback --web）
 * - 参数用列表（ProcessBuilder 数组语义），不拼 shell 字符串
 * - MAGPIE_BUN 由 EngineProcessRunner 环境白名单注入（此处只产出参数）
 */
object EngineCommand {

    const val DEFAULT_GATEWAY_PORT = 3425
    const val DEFAULT_ADMIN_PORT = 3430

    fun serve(
        magpieExecutablePath: String,
        adminPort: Int = DEFAULT_ADMIN_PORT,
        gatewayPort: Int = DEFAULT_GATEWAY_PORT,
    ): List<String> {
        require(magpieExecutablePath.startsWith("/")) { "载荷路径必须绝对" }
        require(adminPort in 1024..65535 && gatewayPort in 1024..65535) { "端口超出范围" }
        require(gatewayPort == DEFAULT_GATEWAY_PORT) { "当前载荷不支持通过 serve 参数修改网关端口，请勿静默忽略自定义端口" }
        require(adminPort != gatewayPort) { "管理端口与网关端口不得相同" }
        // 移动版固定 loopback --web：管理面不上 LAN（M3 补丁的上游前提）
        return listOf(magpieExecutablePath, "serve", "--web=127.0.0.1:$adminPort")
    }

    /** 健康检查用：引擎版本探测命令。 */
    fun version(magpieExecutablePath: String): List<String> {
        require(magpieExecutablePath.startsWith("/")) { "载荷路径必须绝对" }
        return listOf(magpieExecutablePath, "--version")
    }
}
