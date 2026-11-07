package cc.fplj.magpiemobile.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class StreamingChatClientTest {

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

    private fun respond(ex: HttpExchange, sse: String) {
        val bytes = sse.toByteArray()
        ex.getResponseHeaders().add("Content-Type", "text/event-stream")
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    @Test
    fun streamCollectsDeltasUntilDone() {
        server.createContext("/v1/chat/completions") { ex ->
            respond(ex, "data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}\n\n" +
                "data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}\n\n" +
                "data: [DONE]\n\n")
        }
        val deltas = mutableListOf<String>()
        val full = StreamingChatClient(config()).stream(
            "m", listOf(ChatClient.Message("user", "hi")), deltas::add,
        )
        assertEquals(listOf("你", "好"), deltas)
        assertEquals("你好", full)
    }

    @Test
    fun streamWithoutDoneKeepsPartialText() {
        server.createContext("/v1/chat/completions") { ex ->
            respond(ex, "data: {\"choices\":[{\"delta\":{\"content\":\"part\"}}]}\n\n")
            // 无 [DONE]，连接关闭
        }
        val full = StreamingChatClient(config()).stream(
            "m", listOf(ChatClient.Message("user", "x")),
        )
        assertEquals("part", full) // 断流保留已收文本（plan.md §5）
    }

    @Test
    fun httpErrorPropagates() {
        server.createContext("/v1/chat/completions") { ex -> ex.sendResponseHeaders(503, -1) }
        val e = assertFailsWith<ChatException> {
            StreamingChatClient(config()).stream("m", listOf(ChatClient.Message("user", "x")))
        }
        assertEquals(503, e.httpCode)
    }

    @Test
    fun sseErrorEventPropagates() {
        server.createContext("/v1/chat/completions") { ex ->
            respond(ex, "data: {\"error\":{\"message\":\"billing\"}}\n\n")
        }
        val e = assertFailsWith<ChatException> {
            StreamingChatClient(config()).stream("m", listOf(ChatClient.Message("user", "x")))
        }
        assertTrue(e.message!!.contains("billing"))
    }

    @Test
    fun requestHasStreamTrueAndAcceptSse() {
        var seenBody = ""
        var seenAccept: String? = null
        server.createContext("/v1/chat/completions") { ex ->
            seenAccept = ex.getRequestHeaders().getFirst("Accept")
            seenBody = ex.requestBody.bufferedReader().readText()
            respond(ex, "data: [DONE]\n\n")
        }
        StreamingChatClient(config()).stream("m", listOf(ChatClient.Message("user", "x")))
        assertTrue(seenBody.contains("\"stream\":true"))
        assertEquals("text/event-stream", seenAccept)
    }
}
