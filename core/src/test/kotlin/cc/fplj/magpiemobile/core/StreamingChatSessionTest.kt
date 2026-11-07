package cc.fplj.magpiemobile.core

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class StreamingChatSessionTest {

    @TempDir
    lateinit var tmp: File

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

    private fun cfg() = GatewayConfig(gatewayKey = "k", gatewayBaseUrl = "http://127.0.0.1:" + port)

    @Test
    fun streamsWhenSupported() {
        server.createContext("/v1/chat/completions") { ex ->
            val sse = "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\ndata: [DONE]\n\n"
            val b = sse.toByteArray(); ex.getResponseHeaders().add("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(200, b.size.toLong())
            ex.responseBody.use { it.write(b) }
        }
        val store = SessionStore(File(tmp, "s.jsonl"))
        val s = StreamingChatSession(StreamingChatClient(cfg()), ChatClient(cfg()), store, "m")
        val deltas = mutableListOf<String>()
        val reply = s.send("hello") { deltas.add(it) }
        assertEquals("hi", reply)
        assertEquals(listOf("hi"), deltas)
        assertTrue(store.readAll()[1].engine.contains(":stream"))
    }

    @Test
    fun fallsBackToNonStreamingOnStreamFailure() {
        // 第 1 次请求 500（流式失败）→ 实现内部降级发起第 2 次请求 → 200 JSON
        val n = AtomicInteger(0)
        server.createContext("/v1/chat/completions") { ex ->
            if (n.incrementAndGet() == 1) {
                ex.sendResponseHeaders(500, -1); ex.close(); return@createContext
            }
            val body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"fb\"}}]}"
            val b = body.toByteArray(); ex.sendResponseHeaders(200, b.size.toLong())
            ex.responseBody.use { it.write(b) }
        }
        val store = SessionStore(File(tmp, "s.jsonl"))
        val s = StreamingChatSession(StreamingChatClient(cfg()), ChatClient(cfg()), store, "m")
        val reply = s.send("hi2")
        assertEquals("fb", reply)
        // 降级轮 engine 无 :stream
        assertTrue(!store.readAll().last { it.role == "assistant" }.engine.contains(":stream"))
    }
}
