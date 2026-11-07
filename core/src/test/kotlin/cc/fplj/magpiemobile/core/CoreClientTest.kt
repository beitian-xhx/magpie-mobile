package cc.fplj.magpiemobile.core

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import java.net.HttpURLConnection
import java.net.InetSocketAddress
import java.net.URL
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class CoreClientTest {
    private val servers = mutableListOf<HttpServer>()
    @AfterEach fun stop() { servers.forEach { it.stop(0) } }

    private fun gateway(port: Int = 0, status: Int = 200, body: String = """{"name":"magpie","version":"0.1.3188"}"""): HttpServer {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", port), 0)
        servers += server
        server.createContext("/api/hello") { exchange ->
            val bytes = body.toByteArray(Charsets.UTF_8)
            exchange.sendResponseHeaders(status, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }
        server.start()
        return server
    }

    @Test fun helloParsesOfficialGatewayShape() {
        val server = gateway()
        val hello = CoreClient("http://127.0.0.1:" + server.address.port).hello()
        assertEquals("magpie", hello.name)
        assertEquals("0.1.3188", hello.version)
    }

    @Test fun helloIgnoresUnknownFields() {
        val server = gateway(body = """{"name":"magpie","version":"0.1.3188","future_field":123}""")
        assertEquals("magpie", CoreClient("http://127.0.0.1:" + server.address.port).hello().name)
    }

    @Test fun defaultHelloGoesToGateway3425AndNeverCarriesManagerCookie() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 3425), 0)
        servers += server
        val calls = AtomicInteger()
        server.createContext("/api/hello") { exchange ->
            if (exchange.requestHeaders.getFirst("Cookie") != null) exchange.sendResponseHeaders(401, -1)
            else {
                calls.incrementAndGet()
                val bytes = """{"name":"magpie","version":"gateway-only"}""".toByteArray()
                exchange.sendResponseHeaders(200, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
            }
        }
        server.start()
        assertEquals("gateway-only", CoreClient().hello().version)
        assertEquals(1, calls.get())
    }

    @Test fun guiHasNoHelloEvenWithCorrectCookie() {
        ManagementApiFixture().use { gui ->
            val error = assertFailsWith<CoreException> { CoreClient(gui.baseUrl).hello() }
            // The gateway probe sends no cookie. Real GUI webGuard turns it away.
            assertTrue(error.message!!.contains("401"))
            assertTrue(gui.requests.all { it.cookie == null })
            assertEquals(404, assertFailsWith<ManagementHttpException> {
                BoundedCoreHttp(LoopbackEndpoint(gui.baseUrl), java.time.Duration.ofSeconds(2), gui.credential.cookieHeader())
                    .request("GET", "/api/hello")
            }.httpCode)
        }
    }

    @Test fun stateIsAuthenticatedManagementAndPassesThroughUnknownShape() {
        ManagementApiFixture().use { gui ->
            @Suppress("DEPRECATION")
            val state = CoreClient().state(gui.credential, gui.baseUrl)
            assertEquals(7, state["version"]!!.jsonPrimitive.content.toInt())
            assertEquals("/api/state", gui.requests.single().path)
            assertEquals(gui.credential.cookieHeader(), gui.requests.single().cookie)
        }
    }

    @Test fun stateMockActuallyRejectsUnauthenticatedRequests() {
        ManagementApiFixture().use { gui ->
            val connection = URL(gui.baseUrl + "/api/state").openConnection() as HttpURLConnection
            connection.instanceFollowRedirects = false
            try { assertEquals(401, connection.responseCode) } finally { connection.disconnect() }
            assertTrue(CoreClient::class.java.methods.filter { it.name == "state" }.all { it.parameterCount > 0 })
        }
    }

    @Test fun non200ThrowsOnlyFixedCodeNotFreeErrorText() {
        val server = gateway(status = 503, body = "untrusted " + ManagementApiFixture.SECRET)
        val error = assertFailsWith<CoreException> { CoreClient("http://127.0.0.1:" + server.address.port).hello() }
        assertTrue(error.message!!.contains("503"))
        assertTrue(!error.stackTraceToString().contains(ManagementApiFixture.SECRET))
    }
}
