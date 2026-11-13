package cc.fplj.magpiemobile.core

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URLDecoder
import org.junit.jupiter.api.Test
import kotlin.test.*

/** Tests the actual management HTTP boundary with the pinned core's JSON schema. */
class UsageDashboardHttpTest {
    @Test fun periodFiltersPaginationAndQuotaRefreshAreSentWithManagerCookieOnly() {
        val seen = mutableListOf<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/usage") { ex ->
            assertNotNull(ex.requestHeaders.getFirst("Cookie"))
            assertNull(ex.requestHeaders.getFirst("Authorization"))
            val url = URLDecoder.decode(ex.requestURI.toString(), "UTF-8")
            seen += url
            val body = when (ex.requestURI.path) {
                "/api/usage/requests" -> """{"rows":[],"total":0,"calls":0,"providers":[]}"""
                "/api/usage/quotas" -> "[]"
                else -> """{"calls":0,"models":[],"series":[]}"""
            }.toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong())
            ex.responseBody.use { it.write(body) }
        }
        server.start()
        try {
            val client = CoreManagementClient(ManagerCredential.fromKey("a".repeat(64), server.address.port), "http://127.0.0.1:${server.address.port}")
            val query = UsageQuery(period = "7d", provider = "workbuddy", model = "deepseek-v4.1-flash", failed = true, zone = "Asia/Shanghai")
            client.usageOverview(query)
            client.usageLedger(query, 20)
            client.usageQuotas(asked = true)
            client.usageQuotas(asked = true, all = true)
            assertTrue(seen[0].contains("period=7d"))
            assertFalse(seen[0].contains("provider=")) // overview is the whole selected period
            assertTrue(seen[1].contains("provider=workbuddy&model=deepseek-v4.1-flash"))
            assertTrue(seen[1].contains("offset=20&limit=20"))
            assertTrue(seen[1].contains("tz=Asia/Shanghai"))
            assertEquals("/api/usage/quotas?asked=1", seen[2])
            assertEquals("/api/usage/quotas?asked=1&all=1", seen[3])
        } finally { server.stop(0) }
    }
    @Test fun authenticatedReadFailureKeepsItsHttpMeaning() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/api/usage") { it.sendResponseHeaders(503, -1); it.close() }
        server.start()
        try {
            val client = CoreManagementClient(ManagerCredential.fromKey("a".repeat(64), server.address.port), "http://127.0.0.1:${server.address.port}")
            assertEquals(503, assertFailsWith<ManagementHttpException> { client.usageOverview(UsageQuery()) }.httpCode)
        } finally { server.stop(0) }
    }
}
