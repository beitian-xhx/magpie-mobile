package cc.fplj.magpiemobile.core

/**
 * 日志/错误信息凭据脱敏（plan.md §4.3“凭据不进日志”的统一出口）。
 *
 * 契约（测试以此为准，不钉掩码形状）：
 * - 网关 key（sk-magpie-key-…）、通用 sk- 密钥、Bearer token、
 *   password=/token=/api_key= 查询值：完整值不再出现于输出
 * - 无害文本原样保留
 * - 掩码保留少量排错线索（前缀或末 4 位）
 */
object SecretRedactor {

    fun redact(input: String): String {
        var out = input
        // 1) 网关 key：掩码为 gw+末4位（不含 "sk-m" 等前缀触发词，防二次匹配）
        out = out.replace(Regex("sk-magpie-key-[A-Za-z0-9]+")) { m ->
            "gw:**" + m.value.takeLast(4)
        }
        // 2) Bearer token
        out = out.replace(Regex("Bearer\\s+[A-Za-z0-9-._~]{8,}")) { m ->
            "Bearer:**" + m.value.takeLast(4)
        }
        // 3) 查询串/配置密钥
        out = out.replace(Regex("(?i)(password|token|api_key)=([^\\s&]{4,})")) { m ->
            m.groupValues[1] + "=**" + m.groupValues[2].takeLast(4)
        }
        // 4) 通用 sk- 密钥（最后，此时文本已无 sk-magpie 触发词）
        out = out.replace(Regex("sk-[A-Za-z0-9-_]{8,}")) { m ->
            "sk:**" + m.value.takeLast(4)
        }
        return out
    }
}
