package cc.fplj.magpiemobile.core

/** A process-only MAGPIE_WEB_KEY. Never use this credential on the gateway. */
class ManagerCredential private constructor(
    val cookieName: String,
    private val cookieValue: String,
    val port: Int,
) {
    /** Explicit native requests use the correct cookie directly; no sign-in redirect is needed. */
    fun cookieHeader(): String = "$cookieName=$cookieValue"

    /** Compatibility display: never exposes the complete key. */
    fun redacted(): String = cookieName + "=" + cookieValue.take(4) + "…"
    override fun toString(): String = redacted()

    companion object {
        fun fromKey(key: String, port: Int = 3430): ManagerCredential {
            require(port in 1..65535) { "管理端口无效" }
            require(key.length in 16..4096) { "管理 key 长度无效" }
            require(key.all { it in 'A'..'Z' || it in 'a'..'z' || it in '0'..'9' || it in "-._~" }) {
                "管理 key 含非法字符"
            }
            return ManagerCredential("magpie_web_$port", key, port)
        }

        /** Kept for existing callers/tests; the name must have a canonical, valid port. */
        fun create(cookieName: String, cookieValue: String): ManagerCredential {
            val suffix = cookieName.removePrefix("magpie_web_")
            val port = suffix.toIntOrNull()
            require(cookieName.startsWith("magpie_web_") && port != null &&
                port in 1..65535 && suffix == port.toString()) { "管理 cookie 名无效" }
            return fromKey(cookieValue, port)
        }
    }
}
