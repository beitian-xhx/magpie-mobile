package cc.fplj.magpiemobile.core

/**
 * 退避序列策略（从 EngineSupervisor 提取的独立可测组件，plan.md §4.6）。
 *
 * 序列：baseMs * growth^attempt，封顶 capMs；attempt 从 0 起。
 * 默认 1s → 2s → 4s（growth=2, cap=8s），与 EngineSupervisor 现行为一致。
 * 负值/零 base、growth<1、cap<base 显式拒绝（构造期校验）。
 */
class BackoffPolicy(
    private val baseMs: Long = 1_000,
    private val growth: Int = 2,
    private val capMs: Long = 8_000,
) {
    init {
        require(baseMs > 0) { "baseMs 必须 > 0" }
        require(growth >= 1) { "growth 必须 >= 1" }
        require(capMs >= baseMs) { "capMs 必须 >= baseMs" }
    }

    /** 第 attempt 次（0 起）退避时长，封顶 capMs。 */
    fun delayFor(attempt: Int): Long {
        require(attempt >= 0) { "attempt 必须 >= 0" }
        var d = baseMs
        repeat(attempt) {
            d = (d * growth).coerceAtMost(capMs)
        }
        return d
    }

    /** 序列前 n 项（诊断/测试展示用）。 */
    fun sequence(n: Int): List<Long> = (0 until n).map { delayFor(it) }
}
