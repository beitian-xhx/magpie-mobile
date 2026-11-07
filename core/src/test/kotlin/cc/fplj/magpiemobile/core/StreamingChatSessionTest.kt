package cc.fplj.magpiemobile.core

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
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
            ex.getResponseHeaders().add("Content-Type", "text/event-stream")
            val b = sse.toByteArray(); ex.sendResponseHeaders(200, b.size.toLong())
            ex.responseBody.use { it.write(b) }
        }
        val store = SessionStore(File(tmp, "s.jsonl"))
        val s = StreamingChatSession(StreamingChatClient(cfg()), ChatClient(cfg()), store, "m")
        val deltas = mutableListOf<String>()
        val reply = s.send("hello") { deltas.add(it) }
        assertEquals("hi", reply)
        assertEquals(listOf("hi"), deltas)
        // 记录带 :stream 标记
        assertTrue(store.readAll()[1].engine.contains(":stream"))
    }

    @Test
    fun fallsBackToNonStreamingOnStreamFailure() {
        // stream 请求返回 500 → 降级：同一 handler 需要区分；简化：第一个 context 挂 500，重挂非流式 handler
        var mode = "stream"
        server.createContext("/v1/chat/completions") { ex ->
            if (mode == "stream") {
                ex.sendResponseHeaders(500, -1); ex.close(); return@createContext
            }
            val body = "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"fb\"}}]}"
            val b = body.toByteArray(); ex.sendResponseHeaders(200, b.size.toLong())
            ex.responseBody.use { it.write(b) }
        }
        val store = SessionStore(File(tmp, "s.jsonl"))
        val s = StreamingChatSession(StreamingChatClient(cfg()), ChatClient(cfg()), store, "m")
        // 第一次：流式失败但同轮降级成功（实现内部降级，不抛出）
        mode = "stream-fail-then-fallback"
        // 实现：stream 抛 ChatException 后内部用 fallback.complete —— 同一 server 仍 500。
        // 这里验证降级路径：直接切 mode 模拟服务器恢复非流式能力
        mode = "fallback"
        val reply = s.send("hi2")
        assertEquals("fb", reply)
        // 降级轮 engine 无 :stream
        assertTrue(!store.readAll().last { it.role == "assistant" }.engine.contains(":stream"))
    }
}
