package cc.fplj.magpiemobile.core

import kotlinx.serialization.json.*
import java.net.URLEncoder
import java.time.ZoneId

/** Exact read contracts of the pinned core's internal/usageapi and provider/quotas. */
data class UsageQuery(
    val period: String = "today", val provider: String = "", val model: String = "",
    val search: String = "", val failed: Boolean = false, val day: String = "",
    val zone: String = ZoneId.systemDefault().id,
) {
    fun parameters(offset: Int? = null, limit: Int = 20): String {
        require(period in setOf("today", "7d", "30d", "all"))
        require(offset == null || offset >= 0)
        require(limit in 1..50)
        require(day.isEmpty() || runCatching { java.time.LocalDate.parse(day) }.isSuccess)
        val values = linkedMapOf("period" to period, "tz" to zone)
        if (provider.isNotBlank()) values["provider"] = provider
        if (model.isNotBlank()) values["model"] = model
        if (search.isNotBlank()) values["q"] = search.take(200)
        if (failed) values["failed"] = "1"
        if (day.isNotBlank()) values["day"] = day
        if (offset != null) { values["offset"] = offset.toString(); values["limit"] = limit.toString() }
        return values.entries.joinToString("&") { (k, v) -> "$k=${URLEncoder.encode(v, "UTF-8")}" }
    }
}

data class UsageNumbers(
    val calls: Long = 0, val errors: Long = 0, val canceled: Long = 0, val retried: Long = 0,
    val input: Long = 0, val output: Long = 0, val cacheRead: Long = 0, val cacheWrite: Long = 0,
    val reasoning: Long = 0, val cost: Double = 0.0, val unpriced: Long = 0,
    val timed: Long = 0, val ttftMs: Long = 0, val decodeMs: Long = 0, val decodeOut: Long = 0,
    val cacheUnknown: Long = 0,
) {
    val tokens get() = input + output + cacheRead + cacheWrite
    val meanTtftMs: Long? get() = if (timed > 0) ttftMs / timed else null
    val tokensPerSecond: Double? get() = if (decodeMs > 0) decodeOut * 1000.0 / decodeMs else null
    val cacheHit: Double? get() = (input + cacheRead + cacheWrite - cacheUnknown).takeIf { it > 0 }?.let { cacheRead * 100.0 / it }
}
data class UsageGroupData(val id: String, val name: String, val provider: String, val model: String, val sub: String, val numbers: UsageNumbers)
data class UsagePointData(val label: String, val time: String, val numbers: UsageNumbers)
data class UsageOverview(val numbers: UsageNumbers, val models: List<UsageGroupData>, val accounts: List<UsageGroupData>, val agents: List<UsageGroupData>, val keys: List<UsageGroupData>, val series: List<UsagePointData>)
data class UsageLedger(val requests: List<UsageRecord>, val total: Int, val numbers: UsageNumbers, val providers: List<Pair<String, String>>, val offset: Int = 0)
data class QuotaWindowData(val name: String, val used: Double?, val unlimited: Boolean, val text: Boolean, val display: String, val amount: Double?, val limit: Double?, val unit: String, val balance: Double?, val resetsAt: String, val resetSecs: Long, val runsOutAt: String)
data class QuotaCardData(val provider: String, val name: String, val user: String, val plan: String, val balance: String, val error: String, val readAt: String, val staleAt: String, val deferred: Boolean, val windows: List<QuotaWindowData>)
data class HeatDayData(val date: String, val calls: Long, val tokens: Long)
data class QuotaHistoryData(val provider: String, val user: String, val name: String, val points: List<Pair<String, Double>>)

object UsageDashboardParser {
    private fun parse(raw: String): JsonElement = try { Json.parseToJsonElement(raw) } catch (_: Exception) { throw ManagementProtocolException() }
    private fun objectOf(raw: String): JsonObject = parse(raw) as? JsonObject ?: throw ManagementProtocolException()
    private fun JsonObject.s(k: String) = (get(k) as? JsonPrimitive)?.contentOrNull.orEmpty()
    private fun JsonObject.n(k: String) = (get(k) as? JsonPrimitive)?.longOrNull ?: 0L
    private fun JsonObject.d(k: String) = (get(k) as? JsonPrimitive)?.doubleOrNull?.takeIf { it.isFinite() }
    private fun JsonObject.b(k: String) = (get(k) as? JsonPrimitive)?.booleanOrNull == true
    private fun JsonObject.objects(k: String): List<JsonObject> = when (val a = get(k)) {
        null, JsonNull -> emptyList()
        is JsonArray -> a.map { it as? JsonObject ?: throw ManagementProtocolException() }
        else -> throw ManagementProtocolException()
    }
    private fun numbers(o: JsonObject) = with(o) {
        UsageNumbers(n("calls"), n("errors"), n("canceled"), n("retried"), n("input"), n("output"), n("cache_read"), n("cache_write"), n("reasoning"), d("cost") ?: 0.0, n("unpriced"), n("timed"), n("ttft_ms"), n("decode_ms"), n("decode_out"), n("cache_unknown"))
    }
    private fun groups(o: JsonObject, k: String) = o.objects(k).map { UsageGroupData(it.s("id"), it.s("name"), it.s("provider"), it.s("model"), it.s("sub"), numbers(it)) }
    fun overview(raw: String): UsageOverview {
        val o = objectOf(raw)
        if ((o["calls"] as? JsonPrimitive)?.longOrNull == null) throw ManagementProtocolException()
        return UsageOverview(numbers(o), groups(o, "models"), groups(o, "accounts"), groups(o, "agents"), groups(o, "callerKeys"), o.objects("series").map { UsagePointData(it.s("label"), it.s("time"), numbers(it)) })
    }
    fun ledger(raw: String): UsageLedger {
        val o = objectOf(raw)
        if (o["rows"] !is JsonArray || (o["total"] as? JsonPrimitive)?.intOrNull == null) throw ManagementProtocolException()
        return UsageLedger(UsageClient.parseUsageRecords(raw), o.n("total").toInt(), numbers(o), o.objects("providers").map { it.s("id") to it.s("name") }, o.n("offset").toInt())
    }
    fun quotas(raw: String): List<QuotaCardData> {
        val element = parse(raw)
        if (element == JsonNull) return emptyList()
        val array = element as? JsonArray ?: throw ManagementProtocolException()
        return array.map { item ->
            val o = item as? JsonObject ?: throw ManagementProtocolException()
            if (o.s("provider").isBlank()) throw ManagementProtocolException()
            QuotaCardData(o.s("provider"), o.s("name"), o.s("user"), o.s("plan"), o.s("balance"), SecretRedactor.redact(o.s("error")), o.s("readAt"), o.s("asOf"), o.b("readDeferred"), o.objects("windows").map { w ->
                QuotaWindowData(w.s("name"), w.d("used"), w.b("unlimited"), w.b("text"), w.s("display"), w.d("amount"), w.d("limit"), w.s("unit"), w.d("balance"), w.s("resetsAt"), w.n("resetSecs"), w.s("runsOutAt"))
            })
        }
    }
    fun heatmap(raw: String): List<HeatDayData> {
        val o = objectOf(raw)
        if (o["days"] !is JsonArray && o["days"] != JsonNull) throw ManagementProtocolException()
        return o.objects("days").map { HeatDayData(it.s("date"), it.n("calls"), it.n("tokens")) }
    }
    fun history(raw: String): List<QuotaHistoryData> {
        val element = parse(raw)
        if (element == JsonNull) return emptyList()
        val array = element as? JsonArray ?: throw ManagementProtocolException()
        return array.flatMap { item ->
            val o = item as? JsonObject ?: throw ManagementProtocolException()
            o.objects("lines").map { line ->
                QuotaHistoryData(o.s("provider"), o.s("user"), line.s("name"), line.objects("points").map { p -> p.s("at") to (p.d("left") ?: throw ManagementProtocolException()) })
            }
        }
    }
}
