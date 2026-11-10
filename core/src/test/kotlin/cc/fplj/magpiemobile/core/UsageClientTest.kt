package cc.fplj.magpiemobile.core

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class UsageClientTest {

    private lateinit var server: HttpServer
    private var port: Int = 0

    @BeforeEach
    fun setUp() {
        server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.start()
        port = server.address.port
    }

    @AfterEach
    fun tearDown() {
        server.stop(0)
    }

    private fun config(withCookie: Boolean = true) = GatewayConfig(
        gatewayKey = "gw-key",
        adminBaseUrl = "http://127.0.0.1:" + port,
        adminCookie = if (withCookie) "sess-abc" else null,
    )

    @Test
    fun sendsAdminCookieNotGatewayKey() {
        var seenCookie: String? = null
        var seenAuth: String? = null
        server.createContext("/api/usage/requests") { ex ->
            seenCookie = ex.requestHeaders.getFirst("Cookie")
            seenAuth = ex.requestHeaders.getFirst("Authorization")
            val body = """{"requests":[],"total":0}"""
            ex.sendResponseHeaders(200, body.toByteArray().size.toLong())
            ex.responseBody.use { it.write(body.toByteArray()) }
        }
        UsageClient(config()).recentRequests(5)
        assertEquals("magpie_web_3430=sess-abc", seenCookie)
        // 管理面请求绝不带网关 key（两套凭据不通用，plan.md §4.3）
        assertEquals(null, seenAuth)
    }

    @Test
    fun limitIsEnforced() {
        assertFailsWith<IllegalArgumentException> { UsageClient(config()).recentRequests(0) }
        assertFailsWith<IllegalArgumentException> { UsageClient(config()).recentRequests(51) }
    }

    @Test
    fun missingCookieFailsFastWithoutNetwork() {
        val e = assertFailsWith<UsageException> { UsageClient(config(withCookie = false)).recentRequests() }
        assertTrue(e.message!!.contains("cookie"))
        assertEquals(0, e.httpCode)
    }

    @Test
    fun errorPropagatesCode() {
        server.createContext("/api/usage/requests") { ex -> ex.sendResponseHeaders(403, -1) }
        val e = assertFailsWith<UsageException> { UsageClient(config()).recentRequests() }
        assertEquals(403, e.httpCode)
    }

    @Test
    fun usageInsightHitsExactPathWithAdminCookie() {
        for (endpoint in listOf(UsageInsightEndpoint.HEATMAP, UsageInsightEndpoint.QUOTAS, UsageInsightEndpoint.QUOTAS_HISTORY)) {
            var seenPath: String? = null
            var seenCookie: String? = null
            server.createContext(endpoint.path) { ex ->
                seenPath = ex.requestURI.path
                seenCookie = ex.requestHeaders.getFirst("Cookie")
                val body = """{"ok":true}"""
                ex.sendResponseHeaders(200, body.toByteArray().size.toLong())
                ex.responseBody.use { it.write(body.toByteArray()) }
            }
            val body = UsageClient(config()).usageInsight(endpoint)
            assertEquals(endpoint.path, seenPath)
            assertEquals("magpie_web_3430=sess-abc", seenCookie)
            assertEquals("""{"ok":true}""", body)
        }
    }

    @Test
    fun usageInsightMissingCookieFailsFast() {
        val e = assertFailsWith<UsageException> { UsageClient(config(withCookie = false)).usageInsight(UsageInsightEndpoint.HEATMAP) }
        assertTrue(e.message!!.contains("cookie"))
        assertEquals(0, e.httpCode)
    }

    @Test
    fun usageInsightErrorPropagatesCode() {
        server.createContext("/api/usage/heatmap") { ex -> ex.sendResponseHeaders(500, -1) }
        val e = assertFailsWith<UsageException> { UsageClient(config()).usageInsight(UsageInsightEndpoint.HEATMAP) }
        assertEquals(500, e.httpCode)
    }

    @Test
    fun parseUsageRecordsParsesNestedRequestsAndTokens() {
        val sampleJson = """
            {
              "requests": [
                {
                  "id": "req-1",
                  "time": "2026-11-08T15:00:00Z",
                  "model": "gpt-4o",
                  "provider": "openai",
                  "status": 200,
                  "duration": 450,
                  "tokens": { "total": 128 }
                },
                {
                  "id": "req-2",
                  "created_at": "2026-11-08T15:05:00Z",
                  "model_id": "claude-3-5-sonnet",
                  "provider_id": "anthropic",
                  "statusCode": 429,
                  "durationMs": 120,
                  "total_tokens": 50
                }
              ],
              "total": 2
            }
        """.trimIndent()

        val records = UsageClient.parseUsageRecords(sampleJson)
        assertEquals(2, records.size)

        assertEquals("req-1", records[0].id)
        assertEquals("2026-11-08T15:00:00Z", records[0].timestamp)
        assertEquals("gpt-4o", records[0].model)
        assertEquals("openai", records[0].provider)
        assertEquals(200, records[0].statusCode)
        assertEquals(450L, records[0].durationMs)
        assertEquals(128, records[0].totalTokens)

        assertEquals("req-2", records[1].id)
        assertEquals("2026-11-08T15:05:00Z", records[1].timestamp)
        assertEquals("claude-3-5-sonnet", records[1].model)
        assertEquals("anthropic", records[1].provider)
        assertEquals(429, records[1].statusCode)
        assertEquals(120L, records[1].durationMs)
        assertEquals(50, records[1].totalTokens)
    }

    @Test
    fun parseUsageRecordsHandlesEmptyOrMalformedGracefully() {
        assertEquals(emptyList(), UsageClient.parseUsageRecords(""))
        assertEquals(emptyList(), UsageClient.parseUsageRecords("not a json"))
        assertEquals(emptyList(), UsageClient.parseUsageRecords("{}"))
        assertEquals(emptyList(), UsageClient.parseUsageRecords("""{"requests": "not an array"}"""))
    }

    @Test
    fun recentRecordsReturnsParsedRecordsFromHttp() {
        server.createContext("/api/usage/requests") { ex ->
            val body = """{"requests":[{"id":"test-1","model":"qwen-max","status":200,"duration":320,"total_tokens":64}]}"""
            ex.sendResponseHeaders(200, body.toByteArray().size.toLong())
            ex.responseBody.use { it.write(body.toByteArray()) }
        }
        val records = UsageClient(config()).recentRecords(5)
        assertEquals(1, records.size)
        assertEquals("test-1", records[0].id)
        assertEquals("qwen-max", records[0].model)
        assertEquals(200, records[0].statusCode)
        assertEquals(320L, records[0].durationMs)
        assertEquals(64, records[0].totalTokens)
    }
}

