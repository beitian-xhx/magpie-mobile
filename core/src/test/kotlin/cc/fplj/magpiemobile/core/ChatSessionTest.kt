package cc.fplj.magpiemobile.core

import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.InetSocketAddress
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ChatSessionTest {

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

    private fun chat() = ChatClient(
        GatewayConfig(gatewayKey = "k", gatewayBaseUrl = "http://127.0.0.1:" + port),
    )

    @Test
    fun sendRecordsUserAndAssistant() {
        server.createContext("/v1/chat/completions") { ex ->
            val body = """{"choices":[{"message":{"role":"assistant","content":"re"}}]}"""
            ex.sendResponseHeaders(200, body.toByteArray().size.toLong())
            ex.responseBody.use { it.write(body.toByteArray()) }
        }
        val store = SessionStore(File(tmp, "s.jsonl"))
        val s = ChatSession(chat(), store, "m")
        val reply = s.send("hi")
        assertEquals("re", reply)
        assertEquals(2, store.readAll().size)
        assertEquals("user", store.readAll()[0].role)
        assertEquals("assistant", store.readAll()[1].role)
        assertEquals(2, s.messages().size)
    }

    @Test
    fun multiTurnSendsFullHistory() {
        var seenMessages = ""
        server.createContext("/v1/chat/completions") { ex ->
            seenMessages = ex.requestBody.bufferedReader().readText()
            val body = """{"choices":[{"message":{"role":"assistant","content":"ok"}}]}"""
            ex.sendResponseHeaders(200, body.toByteArray().size.toLong())
            ex.responseBody.use { it.write(body.toByteArray()) }
        }
        val s = ChatSession(chat(), SessionStore(File(tmp, "s.jsonl")), "m")
        s.send("first")
        s.send("second")
        // 第二轮请求应包含第一轮 user+assistant 与第二轮 user 共 3 条
        assertTrue(seenMessages.contains("first"))
        assertTrue(seenMessages.contains("ok"))
        assertTrue(seenMessages.contains("second"))
    }

    @Test
    fun upstreamErrorRecordedAndRethrown() {
        server.createContext("/v1/chat/completions") { ex -> ex.sendResponseHeaders(500, -1) }
        val store = SessionStore(File(tmp, "s.jsonl"))
        val s = ChatSession(chat(), store, "m")
        assertFailsWith<ChatException> { s.send("x") }
        val rec = store.readAll()
        assertEquals(2, rec.size) // user + error
        assertEquals("error", rec[1].role)
        assertTrue(rec[1].text.contains("500"))
    }

    @Test
    fun resetClearsContextNotHistory() {
        server.createContext("/v1/chat/completions") { ex ->
            val body = """{"choices":[{"message":{"role":"assistant","content":"o"}}]}"""
            ex.sendResponseHeaders(200, body.toByteArray().size.toLong())
            ex.responseBody.use { it.write(body.toByteArray()) }
        }
        val store = SessionStore(File(tmp, "s.jsonl"))
        val s = ChatSession(chat(), store, "m")
        s.send("a")
        s.reset()
        assertEquals(0, s.messages().size)   // 上下文清空
        assertEquals(2, store.readAll().size) // 本地记录保留
    }

    @Test
    fun badReplyShapeThrows() {
        server.createContext("/v1/chat/completions") { ex ->
            val body = """{"unexpected":1}"""
            ex.sendResponseHeaders(200, body.toByteArray().size.toLong())
            ex.responseBody.use { it.write(body.toByteArray()) }
        }
        val s = ChatSession(chat(), SessionStore(File(tmp, "s.jsonl")), "m")
        assertFailsWith<ChatException> { s.send("x") }
    }
}
