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
}
