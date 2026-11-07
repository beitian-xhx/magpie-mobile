package cc.fplj.magpiemobile.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.io.IOException
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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

    private fun session(store: SessionStore, config: GatewayConfig = cfg()) =
        StreamingChatSession(StreamingChatClient(config), ChatClient(config), store, "m")

    private fun respond(ex: HttpExchange, body: String, contentType: String = "text/event-stream") {
        val bytes = body.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", contentType)
        ex.sendResponseHeaders(200, bytes.size.toLong())
        ex.responseBody.use { it.write(bytes) }
    }

    private fun assertFailedOnly(store: SessionStore, s: StreamingChatSession) {
        assertEquals(listOf("user", "error"), store.readAll().map { it.role })
        assertEquals(listOf("user"), s.messages().map { it.role })
    }

    @Test
    fun streamsWhenSupported() {
        val n = AtomicInteger(0)
        server.createContext("/v1/chat/completions") { ex ->
            n.incrementAndGet()
            respond(ex, "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\ndata: [DONE]\n\n")
        }
        val store = SessionStore(File(tmp, "s.jsonl"))
        val s = session(store)
        val deltas = mutableListOf<String>()
        assertEquals("hi", s.send("hello") { deltas.add(it) })
        assertEquals(listOf("hi"), deltas)
        assertEquals(listOf("user", "assistant"), store.readAll().map { it.role })
        assertTrue(store.readAll()[1].engine.contains(":stream"))
        assertEquals(1, n.get())
    }

    private fun assertFallbackForStatus(code: Int) {
        val n = AtomicInteger(0)
        server.createContext("/v1/chat/completions") { ex ->
            val body = ex.requestBody.bufferedReader().readText()
            if (n.incrementAndGet() == 1) {
                assertTrue(body.contains("\"stream\":true"))
                ex.sendResponseHeaders(code, -1)
                ex.close()
            } else {
                assertFalse(body.contains("\"stream\":true"))
                respond(ex, "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"fb\"}}]}",
                    "application/json")
            }
        }
        val store = SessionStore(File(tmp, "s.jsonl"))
        val s = session(store)
        assertEquals("fb", s.send("hi2"))
        assertEquals(2, n.get())
        assertEquals(listOf("user", "assistant"), store.readAll().map { it.role })
        assertEquals("chat:m:fallback", store.readAll().last().engine)
        assertEquals(listOf("user", "assistant"), s.messages().map { it.role })
    }

    @Test
    fun fallsBackToNonStreamingOnHttp501() {
        // 原 500 降级测试改为明确“不支持”的 501；只有这一类状态能再发一次。
        assertFallbackForStatus(501)
    }

    @Test
    fun fallsBackToNonStreamingOnHttp405() {
        assertFallbackForStatus(405)
    }

    private fun assertHttpErrorNotReplayed(code: Int) {
        val n = AtomicInteger(0)
        server.createContext("/v1/chat/completions") { ex ->
            n.incrementAndGet()
            ex.sendResponseHeaders(code, -1)
            ex.close()
        }
        val store = SessionStore(File(tmp, "s.jsonl"))
        val s = session(store)
        val e = assertFailsWith<ChatException> { s.send("hello") }
        assertEquals(code, e.httpCode)
        assertEquals(1, n.get())
        assertFailedOnly(store, s)
        assertEquals("HTTP " + code, store.readAll().last().text)
        assertTrue(store.readAll().last().engine.endsWith(":stream"))
    }

    @Test
    fun http401DoesNotReplay() {
        assertHttpErrorNotReplayed(401)
    }

    @Test
    fun http429DoesNotReplay() {
        assertHttpErrorNotReplayed(429)
    }

    @Test
    fun http500DoesNotReplay() {
        assertHttpErrorNotReplayed(500)
    }

    @Test
    fun http503DoesNotReplay() {
        assertHttpErrorNotReplayed(503)
    }

    @Test
    fun redirectDoesNotReplayPost() {
        val n = AtomicInteger(0)
        server.createContext("/v1/chat/completions") { ex ->
            n.incrementAndGet()
            ex.responseHeaders.add("Location", "/redirected")
            ex.sendResponseHeaders(307, -1)
            ex.close()
        }
        server.createContext("/redirected") { ex ->
            n.incrementAndGet()
            respond(ex, "data: [DONE]\n\n")
        }
        val store = SessionStore(File(tmp, "s.jsonl"))
        val s = session(store)
        assertEquals(307, assertFailsWith<ChatException> { s.send("hello") }.httpCode)
        assertEquals(1, n.get())
        assertFailedOnly(store, s)
    }

    @Test
    fun fallbackFailureRecordsErrorAndDoesNotTryAgain() {
        val n = AtomicInteger(0)
        server.createContext("/v1/chat/completions") { ex ->
            ex.sendResponseHeaders(if (n.incrementAndGet() == 1) 501 else 401, -1)
            ex.close()
        }
        val store = SessionStore(File(tmp, "s.jsonl"))
        val s = session(store)
        assertEquals(401, assertFailsWith<ChatException> { s.send("hello") }.httpCode)
        assertEquals(2, n.get())
        assertFailedOnly(store, s)
        assertEquals("chat:m:fallback", store.readAll().last().engine)
    }

    @Test
    fun partialEofIsFailureWithoutAssistantOrReplay() {
        val n = AtomicInteger(0)
        server.createContext("/v1/chat/completions") { ex ->
            n.incrementAndGet()
            respond(ex, "data: {\"choices\":[{\"delta\":{\"content\":\"part\"}}]}\n\n")
        }
        val store = SessionStore(File(tmp, "s.jsonl"))
        val s = session(store)
        val deltas = mutableListOf<String>()
        val e = assertFailsWith<StreamingChatException> { s.send("hello", onDelta = deltas::add) }
        assertEquals("part", e.partialText)
        assertEquals(listOf("part"), deltas)
        assertEquals(1, n.get())
        assertFailedOnly(store, s)
        assertTrue(store.readAll().last().text.contains("EOF"))
        s.reset() // 失败也必须释放 sending；reset 只清上下文，不删除记录。
        assertTrue(s.messages().isEmpty())
        assertEquals(2, store.readAll().size)
    }

    @Test
    fun sseErrorIsFailureWithoutReplayAndRedactsStoredError() {
        val n = AtomicInteger(0)
        val key = "opaque-gateway-secret-123456"
        val token = "sk-provider-secret-123456789"
        server.createContext("/v1/chat/completions") { ex ->
            n.incrementAndGet()
            respond(ex, "data: {\"choices\":[{\"delta\":{\"content\":\"part\"}}]}\n\n" +
                "data: {\"error\":{\"message\":\"billing " + key + " Bearer " + token + "\"}}\n\n")
        }
        val store = SessionStore(File(tmp, "s.jsonl"))
        val s = session(store, cfg().copy(gatewayKey = key))
        val e = assertFailsWith<StreamingChatException> { s.send("hello") }
        assertEquals("part", e.partialText)
        assertEquals(1, n.get())
        assertFailedOnly(store, s)
        val diagnostic = store.readAll().last().text
        assertTrue(diagnostic.contains("SSE"))
        assertFalse(diagnostic.contains("billing"))
        assertFalse(diagnostic.contains(key))
        assertFalse(diagnostic.contains(token))
        assertFalse(diagnostic.contains("at cc.fplj"))
    }

    @Test
    fun http200JsonDoesNotReplayOrStoreEmptyAssistant() {
        val n = AtomicInteger(0)
        server.createContext("/v1/chat/completions") { ex ->
            n.incrementAndGet()
            respond(ex, "{\"choices\":[{\"message\":{\"content\":\"json reply\"}}]}", "application/json")
        }
        val store = SessionStore(File(tmp, "s.jsonl"))
        val s = session(store)
        assertFailsWith<StreamingChatException> { s.send("hello") }
        assertEquals(1, n.get())
        assertFailedOnly(store, s)
    }

    @Test
    fun readIOExceptionRecordsErrorWithPartialAndDoesNotReplay() {
        val n = AtomicInteger(0)
        val closeResponse = CountDownLatch(1)
        server.createContext("/v1/chat/completions") { ex ->
            n.incrementAndGet()
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(200, 0)
            try {
                ex.responseBody.write("data: {\"choices\":[{\"delta\":{\"content\":\"part\"}}]}\n\n"
                    .toByteArray(Charsets.UTF_8))
                ex.responseBody.flush()
                closeResponse.await(5, TimeUnit.SECONDS)
            } finally {
                ex.close()
            }
        }
        val store = SessionStore(File(tmp, "s.jsonl"))
        val s = session(store, cfg().copy(readTimeout = Duration.ofSeconds(1)))
        try {
            val e = assertFailsWith<StreamingChatException> { s.send("hello") }
            assertEquals("part", e.partialText)
            assertTrue(e.cause is IOException)
            assertEquals(1, n.get())
            assertFailedOnly(store, s)
            assertTrue(store.readAll().last().text.contains("I/O"))
        } finally {
            closeResponse.countDown()
        }
    }

    @Test
    fun callbackIOExceptionRecordsSafeErrorWithoutReplay() {
        val n = AtomicInteger(0)
        server.createContext("/v1/chat/completions") { ex ->
            n.incrementAndGet()
            respond(ex, "data: {\"choices\":[{\"delta\":{\"content\":\"part\"}}]}\n\ndata: [DONE]\n\n")
        }
        val store = SessionStore(File(tmp, "s.jsonl"))
        val s = session(store)
        val e = assertFailsWith<StreamingChatException> {
            s.send("hello") { throw IOException("password=secret-value-12345") }
        }
        assertEquals("part", e.partialText)
        assertTrue(e.cause is IOException)
        assertEquals(1, n.get())
        assertFailedOnly(store, s)
        assertFalse(store.readAll().last().text.contains("secret-value-12345"))
    }

    @Test
    fun callbackChatException501AfterDeltaCannotFallback() {
        val n = AtomicInteger(0)
        server.createContext("/v1/chat/completions") { ex ->
            n.incrementAndGet()
            respond(ex, "data: {\"choices\":[{\"delta\":{\"content\":\"part\"}}]}\n\ndata: [DONE]\n\n")
        }
        val store = SessionStore(File(tmp, "s.jsonl"))
        val s = session(store)
        val e = assertFailsWith<StreamingChatException> {
            s.send("hello") { throw ChatException("callback failed", 501) }
        }
        assertEquals("part", e.partialText)
        assertEquals(200, e.httpCode)
        assertEquals(1, n.get())
        assertFailedOnly(store, s)
    }

    @Test
    fun concurrentSendAndResetAreRejectedWhileMessagesRemainReadable() {
        val n = AtomicInteger(0)
        val inCallback = CountDownLatch(1)
        val releaseCallback = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        server.createContext("/v1/chat/completions") { ex ->
            n.incrementAndGet()
            respond(ex, "data: {\"choices\":[{\"delta\":{\"content\":\"hi\"}}]}\n\ndata: [DONE]\n\n")
        }
        val store = SessionStore(File(tmp, "s.jsonl"))
        val s = session(store)
        val result = executor.submit<String> {
            s.send("first") {
                inCallback.countDown()
                check(releaseCallback.await(5, TimeUnit.SECONDS))
            }
        }
        try {
            assertTrue(inCallback.await(2, TimeUnit.SECONDS))
            assertEquals(listOf(ChatClient.Message("user", "first")), s.messages())
            assertFailsWith<IllegalStateException> { s.send("second") }
            assertFailsWith<IllegalStateException> { s.reset() }
            assertEquals(1, n.get())
            assertEquals(listOf("user"), store.readAll().map { it.role })
            releaseCallback.countDown()
            assertEquals("hi", result.get(2, TimeUnit.SECONDS))
            assertEquals(listOf("user", "assistant"), s.messages().map { it.role })
            s.reset()
            assertTrue(s.messages().isEmpty())
            assertEquals(listOf("user", "assistant"), store.readAll().map { it.role })
        } finally {
            releaseCallback.countDown()
            result.cancel(true)
            executor.shutdownNow()
        }
    }
}
