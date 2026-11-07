package cc.fplj.magpiemobile.core

/**
 * 日志/错误信息凭据脱敏（plan.md §4.3"凭据不进日志"的统一出口）。
 *
 * 覆盖模式（含上游与常见格式）：
 * - sk-magpie-key-…（网关 key）
 * - sk-…（OpenAI/Anthropic 等）
 * - Bearer <token>（HTTP 头）
 * - password=… / token=… / api_key=…（查询串/配置）
 * 值部分保留前 4 位便于排错，其余以 … 替换。无凭据时原样返回。
 */
object SecretRedactor {

    fun redact(input: String): String {
        var out = input
        out = out.replace(Regex("sk-magpie-key-[A-Za-z0-9]+")) { m -> m.value.take(4 + 13 + 4) + "…" }
        out = out.replace(Regex("sk-[A-Za-z0-9-_]{8,}")) { m -> m.value.take(7) + "…" }
        out = out.replace(Regex("Bearer\\s+[A-Za-z0-9-._~]{8,}")) { m -> "Bearer " + m.value.drop(7).take(4) + "…" }
        out = out.replace(Regex("(?i)(password|token|api_key)=([^\\s&]{4,})")) { m ->
            m.groupValues[1] + "=" + m.groupValues[2].take(4) + "…"
        }
        return out
    }
}
