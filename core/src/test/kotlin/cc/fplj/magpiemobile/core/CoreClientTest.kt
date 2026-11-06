package cc.fplj.magpiemobile.core

import com.sun.net.httpserver.HttpServer
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * CoreClient 契约测试：用 JDK HttpServer 模拟 loopback 核心，
 * 钉住 CoreClient 对上游形状的假设（hello 强校验、state 宽松透传、错误码传播）。
 */
class CoreClientTest {

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

    @Test
    fun helloParsesOfficialShape() {
        server.createContext("/api/hello") { ex ->
            val body = """{"name":"magpie","version":"0.1.3188"}"""
            ex.sendResponseHeaders(200, body.toByteArray().size.toLong())
            ex.responseBody.use { it.write(body.toByteArray()) }
        }
        val h = CoreClient("http://127.0.0.1:$port").hello()
        assertEquals("magpie", h.name)
        assertEquals("0.1.3188", h.version)
    }

    @Test
    fun helloIgnoresUnknownFields() {
        server.createContext("/api/hello") { ex ->
            val body = """{"name":"magpie","version":"0.1.3188","future_field":123}"""
            ex.sendResponseHeaders(200, body.toByteArray().size.toLong())
            ex.responseBody.use { it.write(body.toByteArray()) }
        }
        val h = CoreClient("http://127.0.0.1:$port").hello()
        assertEquals("magpie", h.name)
    }

    @Test
    fun statePassesThroughUnknownShape() {
        server.createContext("/api/state") { ex ->
            val body = """{"anything":{"nested":[1,2,3]},"version":7}"""
            ex.sendResponseHeaders(200, body.toByteArray().size.toLong())
            ex.responseBody.use { it.write(body.toByteArray()) }
        }
        val s = CoreClient("http://127.0.0.1:$port").state()
        assertEquals(7, s["version"]!!.jsonPrimitive.content.toInt())
    }

    @Test
    fun non200ThrowsWithCode() {
        server.createContext("/api/hello") { ex ->
            ex.sendResponseHeaders(503, -1)
        }
        val e = assertFailsWith<CoreException> { CoreClient("http://127.0.0.1:$port").hello() }
        assertTrue(e.message!!.contains("503"))
    }
}
