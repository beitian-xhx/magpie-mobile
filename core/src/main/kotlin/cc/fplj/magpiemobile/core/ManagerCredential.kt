package cc.fplj.magpiemobile.core

/**
 * 管理凭据的进程内持有模型（plan.md §4.3 的可测试核心）。
 *
 * 约束：管理 cookie 只在本类实例内存中；redacted() 返回安全展示形状，
 * toString 永不泄漏完整值——测试钉死「意外序列化也不泄漏」。
 */
class ManagerCredential private constructor(
    private val cookieName: String,
    private val cookieValue: String,
) {
    fun cookieHeader(): String = "$cookieName=$cookieValue"

    /** 日志/展示用的安全形状：只露前 4 位。 */
    fun redacted(): String = cookieName + "=" + cookieValue.take(4) + "…"

    override fun toString(): String = redacted()

    companion object {
        private val ALLOWED_NAMES = setOf("magpie_web_3430", "magpie_web_3425")

        fun create(cookieName: String, cookieValue: String): ManagerCredential {
            require(cookieName in ALLOWED_NAMES) { "未知管理 cookie 名：$cookieName（白名单外拒绝）" }
            require(cookieValue.length >= 16) { "管理 key 至少 16 字符（上游 CheckWebKey 语义）" }
            require(cookieValue.all { it.isLetterOrDigit() || it in "-._~" }) { "管理 key 含非法字符" }
            return ManagerCredential(cookieName, cookieValue)
        }
    }
}
