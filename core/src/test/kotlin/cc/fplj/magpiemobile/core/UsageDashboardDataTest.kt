package cc.fplj.magpiemobile.core

import org.junit.jupiter.api.Test
import kotlin.test.*

class UsageDashboardDataTest {
    @Test fun ledgerUsesActualPinnedCoreFields() {
        val page = UsageDashboardParser.ledger("""{"rows":[{"t":"2026-11-13T16:00:00Z","model":"deepseek-v4.1-flash","provider":"workbuddy","ms":2100,"status":429,"in":100,"out":20,"cache_read":200,"cache_write":30,"reasoning":5,"ttft_ms":900,"first_text_ms":1200,"sent_ms":100,"queued_ms":50,"retried_ms":25,"err":"rate limited"}],"total":1,"calls":1,"input":100,"output":20,"cache_read":200,"cache_write":30,"providers":[{"id":"workbuddy","name":"WorkBuddy"}]}""")
        assertEquals(1, page.requests.size)
        val r = page.requests.single()
        assertEquals("2026-11-13T16:00:00Z", r.timestamp)
        assertEquals(2100, r.durationMs)
        assertEquals(350, r.totalTokens)
        assertEquals(429, r.statusCode)
        assertEquals(900, r.ttftMs)
        assertEquals(50, r.queuedMs)
        assertEquals("rate limited", r.error)
        assertEquals(350, page.numbers.tokens)
    }
    @Test fun totalsUseMeasuredTimingAndUnknownCache() {
        val overview = UsageDashboardParser.overview("""{"calls":10,"errors":1,"canceled":2,"input":100,"output":30,"cache_read":80,"cache_write":20,"cache_unknown":40,"timed":4,"ttft_ms":8000,"decode_ms":2000,"decode_out":100,"cost":0.1234,"unpriced":3,"models":[{"id":"workbuddy/m","name":"M","provider":"workbuddy","model":"m","calls":10}],"series":[{"label":"16:00","time":"2026-11-13T16:00:00Z","calls":10}]}""")
        assertEquals(230, overview.numbers.tokens)
        assertEquals(50.0, overview.numbers.cacheHit)
        assertEquals(2000, overview.numbers.meanTtftMs)
        assertEquals(50.0, overview.numbers.tokensPerSecond)
        assertEquals(3, overview.numbers.unpriced)
        assertEquals("m", overview.models.single().model)
        assertEquals("16:00", overview.series.single().label)
        assertNull(UsageNumbers().cacheHit)
        assertNull(UsageNumbers().tokensPerSecond)
    }
    @Test fun quotaWindowsDistinguishTextUnlimitedWalletAndCachedError() {
        val card = UsageDashboardParser.quotas("""[{"provider":"workbuddy","name":"WorkBuddy","user":"fixture","error":"vendor timeout","asOf":"2026-11-13T16:00:00Z","readDeferred":true,"windows":[{"name":"credits","used":25.5,"amount":25.5,"limit":100,"unit":"credits"},{"name":"info","text":true,"display":"not measured"},{"name":"free","unlimited":true},{"name":"wallet","balance":12.3}]}]""").single()
        assertTrue(card.deferred)
        assertEquals("vendor timeout", card.error)
        assertEquals(25.5, card.windows[0].used)
        assertTrue(card.windows[1].text)
        assertTrue(card.windows[2].unlimited)
        assertEquals(12.3, card.windows[3].balance)
        assertNull(card.windows[3].used)
    }
    @Test fun malformedIsNotZeroUsage() {
        assertFailsWith<ManagementProtocolException> { UsageDashboardParser.ledger("""{"requests":[],"total":3}""") }
        assertFailsWith<ManagementProtocolException> { UsageDashboardParser.overview("{}") }
        assertFailsWith<ManagementProtocolException> { UsageDashboardParser.quotas("not json") }
        assertFailsWith<ManagementProtocolException> { UsageDashboardParser.heatmap("{}") }
    }
    @Test fun queryMatchesDesktopPeriodsAndEscapesFilters() {
        assertTrue(UsageQuery(period = "7d", provider = "a/b", search = "a&b + c", zone = "Asia/Shanghai").parameters(20).contains("q=a%26b+%2B+c"))
        assertTrue(UsageQuery(period = "30d").parameters(40).contains("offset=40&limit=20"))
        assertFailsWith<IllegalArgumentException> { UsageQuery(period = "week").parameters() }
        assertFailsWith<IllegalArgumentException> { UsageQuery(day = "2026-02-30").parameters() }
    }
    @Test fun realHeatmapAndHistory() {
        assertEquals(3, UsageDashboardParser.heatmap("""{"from":"2026-11-01","to":"2026-11-13","days":[{"date":"2026-11-13","calls":3,"tokens":123}]}""").single().calls)
        assertEquals(64.5, UsageDashboardParser.history("""[{"provider":"workbuddy","user":"fixture","lines":[{"name":"credits","points":[{"at":"2026-11-13T16:00:00Z","left":64.5}]}]}]""").single().points.single().second)
    }
}
