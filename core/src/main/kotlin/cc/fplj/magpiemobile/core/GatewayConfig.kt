package cc.fplj.magpiemobile.core

import java.time.Duration

/**
 * 网关端点与安全基线（纯数据，plan.md §4.3 的代码化）。
 *
 * 不可变对象：UI/Service 侧不得绕过这些默认值直接拼地址；
 * 管理面（3430）与网关（3425）物理隔离——管理 cookie 永不发给网关，
 * 网关 key 永不发管理面（两端点鉴权不通用，plan.md §4.3）。
 */
data class GatewayConfig(
    /** 管理面：仅 loopback。移动版从不下 LAN。 */
    val adminBaseUrl: String = "http://127.0.0.1:3430",
    /** 网关：loopback；LAN 显式开启时才换绑定地址。 */
    val gatewayBaseUrl: String = "http://127.0.0.1:3425",
    /** 网关客户端 key（App 内聊天用独立 key，不与管理凭据混用）。 */
    val gatewayKey: String,
    /** 管理 cookie（进程内持有，禁止进日志/Intent/分享）。 */
    val adminCookie: String? = null,
    val connectTimeout: Duration = Duration.ofSeconds(5),
    val readTimeout: Duration = Duration.ofSeconds(30),
) {
    init {
        require(gatewayKey.isNotBlank()) { "gatewayKey 不得为空：loopback 也必须鉴权（plan.md §4.3）" }
        require(adminBaseUrl.startsWith("http://127.0.0.1") || adminBaseUrl.startsWith("http://localhost")) {
            "管理面只允许 loopback 绑定，got $adminBaseUrl"
        }
    }

    /** 网关标准模型目录路径（plan.md §4.2：从 /v1/models 读，不内置列表）。 */
    fun modelsPath(): String = "$gatewayBaseUrl/v1/models"

    /** OpenAI 兼容聊天端点。 */
    fun chatCompletionsPath(): String = "$gatewayBaseUrl/v1/chat/completions"
}
