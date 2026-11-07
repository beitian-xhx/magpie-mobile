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
        val admin = java.net.URI(adminBaseUrl)
        require(admin.scheme == "http" && admin.host in setOf("127.0.0.1", "localhost") &&
            admin.userInfo == null && admin.rawQuery == null && admin.rawFragment == null &&
            (admin.path.isNullOrEmpty() || admin.path == "/") && (admin.port == -1 || admin.port in 1..65535)) {
            "管理面只允许 loopback 绑定（精确主机名、无用户信息/路径/查询参数）"
        }
        val gateway = java.net.URI(gatewayBaseUrl)
        require(gateway.scheme in setOf("http", "https") && gateway.host != null &&
            gateway.userInfo == null && gateway.rawQuery == null && gateway.rawFragment == null &&
            (gateway.path.isNullOrEmpty() || gateway.path == "/") && (gateway.port == -1 || gateway.port in 1..65535) &&
            (gateway.scheme == "https" || gateway.host in setOf("127.0.0.1", "localhost"))) {
            "网关明文仅允许本机 loopback，非本机网关须 HTTPS"
        }
    }

    override fun toString(): String = "GatewayConfig(adminBaseUrl=$adminBaseUrl, gatewayBaseUrl=$gatewayBaseUrl, gatewayKey=<redacted>, adminCookie=<redacted>)"

    /** 网关标准模型目录路径（plan.md §4.2：从 /v1/models 读，不内置列表）。 */
    fun modelsPath(): String = "$gatewayBaseUrl/v1/models"

    /** OpenAI 兼容聊天端点。 */
    fun chatCompletionsPath(): String = "$gatewayBaseUrl/v1/chat/completions"
}
