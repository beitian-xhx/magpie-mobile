package cc.fplj.magpiemobile.core

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ChatClientTest {

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

    private fun config() = GatewayConfig(
        gatewayKey = "test-key",
        gatewayBaseUrl = "http://127.0.0.1:" + port,
    )

    @Test
    fun completeSendsAuthAndModel() {
        var seenAuth: String? = null
        var seenBody = ""
        server.createContext("/v1/chat/completions") { ex ->
            seenAuth = ex.requestHeaders.getFirst("Authorization")
            seenBody = ex.requestBody.bufferedReader().readText()
            val body = """{"id":"x","choices":[{"message":{"role":"assistant","content":"hi"}}]}"""
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, body.toByteArray().size.toLong())
            ex.responseBody.use { it.write(body.toByteArray()) }
        }
        val out = ChatClient(config()).complete(
            "deepseek/deepseek-v4",
            listOf(ChatClient.Message("user", "hello")),
        )
        assertEquals("Bearer test-key", seenAuth)
        assertTrue(seenBody.contains("\"model\":\"deepseek/deepseek-v4\""))
        assertTrue(out.contains("hi"))
    }

    @Test
    fun upstreamErrorPropagatesCode() {
        server.createContext("/v1/chat/completions") { ex ->
            ex.sendResponseHeaders(429, -1)
        }
        val e = assertFailsWith<ChatException> {
            ChatClient(config()).complete("m", listOf(ChatClient.Message("user", "x")))
        }
        assertEquals(429, e.httpCode)
    }
}
