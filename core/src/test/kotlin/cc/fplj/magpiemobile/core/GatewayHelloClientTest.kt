package cc.fplj.magpiemobile.core

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class GatewayHelloClientTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { start() }
    private fun config() = GatewayConfig(gatewayKey = "test-client-key", gatewayBaseUrl = "http://127.0.0.1:" + server.address.port)
    @AfterEach fun close() { server.stop(0) }

    @Test fun probeCarriesOnlyClientBearerAndParsesRealShape() {
        var auth: String? = null
        var cookie: String? = null
        server.createContext("/api/hello") { ex ->
            auth = ex.requestHeaders.getFirst("Authorization"); cookie = ex.requestHeaders.getFirst("Cookie")
            val body = "{\"name\":\"magpie\",\"version\":\"0.1.3188-mobile.1\"}".toByteArray()
            ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
        }
        assertEquals("magpie", GatewayHelloClient(config()).hello().name)
        assertEquals("Bearer test-client-key", auth)
        assertEquals(null, cookie)
    }

    @Test fun redirectIsNotFollowedWithAClientKey() {
        var redirected = false
        server.createContext("/api/hello") { ex -> ex.responseHeaders.add("Location", "/other"); ex.sendResponseHeaders(302, -1); ex.close() }
        server.createContext("/other") { ex -> redirected = true; ex.sendResponseHeaders(200, -1); ex.close() }
        assertFailsWith<CoreException> { GatewayHelloClient(config()).hello() }
        assertTrue(!redirected)
    }
}
