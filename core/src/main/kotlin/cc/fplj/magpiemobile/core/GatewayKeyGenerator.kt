package cc.fplj.magpiemobile.core

import java.security.SecureRandom

/**
 * 网关客户端密钥生成与校验（plan.md §4.3；上游格式 sk-magpie-key-…）。
 *
 * 只做本地生成与格式校验——签发/吊销走核心 CLI/API（plan.md §4.2），
 * 本类不替代核心的密钥存储。生成用 SecureRandom，长度对齐上游 sk-magpie-key- 后 32 hex。
 */
object GatewayKeyGenerator {

    private const val PREFIX = "sk-magpie-key-"
    private const val HEX_CHARS = "0123456789abcdef"
    private const val BODY_LEN = 32

    fun generate(random: SecureRandom = SecureRandom()): String {
        val body = StringBuilder(BODY_LEN)
        repeat(BODY_LEN) { body.append(HEX_CHARS[random.nextInt(HEX_CHARS.length)]) }
        return PREFIX + body
    }

    /** 格式校验（UI 表单与导入预览用）；不检查密钥是否真在核心侧有效。 */
    fun looksValid(key: String): Boolean =
        key.startsWith(PREFIX) &&
            key.length == PREFIX.length + BODY_LEN &&
            key.drop(PREFIX.length).all { it in HEX_CHARS }
}
