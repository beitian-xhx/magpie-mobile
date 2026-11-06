package cc.fplj.magpiemobile.core

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModelsClientTest {

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

    private fun config() = GatewayConfig(gatewayKey = "k", gatewayBaseUrl = "http://127.0.0.1:" + port)

    @Test
    fun parsesKnownFieldsAndIgnoresUnknown() {
        server.createContext("/v1/models") { ex ->
            val body = """
                {"object":"list","data":[
                  {"id":"deepseek/deepseek-v4","display_name":"DeepSeek V4","owned_by":"deepseek",
                   "context_window":200000,"reasoning_levels":[{"effort":"low"}],"future_field":1},
                  {"id":"group/fast","display_name":"Fast","owned_by":"magpie"}
                ]}
            """.trimIndent()
            ex.getResponseHeaders().add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, body.toByteArray().size.toLong())
            ex.responseBody.use { it.write(body.toByteArray()) }
        }
        val models = ModelsClient(config()).list()
        assertEquals(2, models.size)
        assertEquals("deepseek/deepseek-v4", models[0].id)
        assertEquals("DeepSeek V4", models[0].displayName)
        assertEquals(200000L, models[0].contextWindow)
        assertNull(models[1].contextWindow) // 缺失记 null，不猜默认值
    }

    @Test
    fun missingOptionalFieldsBecomeNull() {
        server.createContext("/v1/models") { ex ->
            val body = """{"data":[{"id":"m1"}]}"""
            ex.sendResponseHeaders(200, body.toByteArray().size.toLong())
            ex.responseBody.use { it.write(body.toByteArray()) }
        }
        val models = ModelsClient(config()).list()
        assertEquals("m1", models[0].id)
        assertNull(models[0].displayName)
        assertNull(models[0].ownedBy)
    }

    @Test
    fun missingDataArrayThrows() {
        server.createContext("/v1/models") { ex ->
            val body = """{"object":"list"}"""
            ex.sendResponseHeaders(200, body.toByteArray().size.toLong())
            ex.responseBody.use { it.write(body.toByteArray()) }
        }
        val e = assertFailsWith<ModelsException> { ModelsClient(config()).list() }
        assertTrue(e.message!!.contains("data"))
    }

    @Test
    fun errorPropagatesCode() {
        server.createContext("/v1/models") { ex -> ex.sendResponseHeaders(401, -1) }
        val e = assertFailsWith<ModelsException> { ModelsClient(config()).list() }
        assertEquals(401, e.httpCode)
    }
}
