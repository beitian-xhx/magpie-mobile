package cc.fplj.magpiemobile.core

import com.sun.net.httpserver.HttpExchange
import com.sun.net.httpserver.HttpServer
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.IOException
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
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
        val bytes = sse.toByteArray(Charsets.UTF_8)
        ex.responseHeaders.add("Content-Type", "text/event-stream; charset=utf-8")
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
    fun streamWithoutDoneFailsAndKeepsPartialText() {
        server.createContext("/v1/chat/completions") { ex ->
            respond(ex, "data: {\"choices\":[{\"delta\":{\"content\":\"part\"}}]}\n\n")
        }
        val deltas = mutableListOf<String>()
        val e = assertFailsWith<StreamingChatException> {
            StreamingChatClient(config()).stream("m", listOf(ChatClient.Message("user", "x")), deltas::add)
        }
        assertEquals("part", e.partialText)
        assertEquals(listOf("part"), deltas)
        assertEquals(200, e.httpCode)
        assertTrue(e.message!!.contains("EOF"))
    }

    @Test
    fun emptyStreamWithoutDoneFails() {
        server.createContext("/v1/chat/completions") { ex -> respond(ex, ": keep-alive\n\n") }
        val e = assertFailsWith<StreamingChatException> {
            StreamingChatClient(config()).stream("m", listOf(ChatClient.Message("user", "x")))
        }
        assertEquals("", e.partialText)
        assertTrue(e.message!!.contains("EOF"))
    }

    @Test
    fun doneReturnsBeforeLongConnectionCloses() {
        val sentDone = CountDownLatch(1)
        val closeResponse = CountDownLatch(1)
        val executor = Executors.newSingleThreadExecutor()
        server.createContext("/v1/chat/completions") { ex ->
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(200, 0) // chunked；DONE 后既不追加空行也不立即关闭
            try {
                ex.responseBody.write(("data: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n" +
                    "data: [DONE]\n").toByteArray(Charsets.UTF_8))
                ex.responseBody.flush()
                sentDone.countDown()
                closeResponse.await(5, TimeUnit.SECONDS)
            } finally {
                ex.close()
            }
        }
        val result = executor.submit<String> {
            StreamingChatClient(config().copy(readTimeout = Duration.ofSeconds(10)))
                .stream("m", listOf(ChatClient.Message("user", "x")))
        }
        try {
            assertTrue(sentDone.await(2, TimeUnit.SECONDS))
            // 服务端仍等 closeResponse；若 DONE 后又 readLine，这里超时而不是返回。
            assertEquals("ok", result.get(2, TimeUnit.SECONDS))
            assertEquals(1L, closeResponse.count)
        } finally {
            closeResponse.countDown()
            result.cancel(true)
            executor.shutdownNow()
        }
    }

    @Test
    fun readIOExceptionKeepsPartialText() {
        val closeResponse = CountDownLatch(1)
        server.createContext("/v1/chat/completions") { ex ->
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
        try {
            val e = assertFailsWith<StreamingChatException> {
                StreamingChatClient(config().copy(readTimeout = Duration.ofSeconds(1)))
                    .stream("m", listOf(ChatClient.Message("user", "x")))
            }
            assertEquals("part", e.partialText)
            assertTrue(e.cause is IOException)
            assertTrue(e.message!!.contains("I/O"))
        } finally {
            closeResponse.countDown()
        }
    }

    @Test
    fun nonSseJsonResponseIsNotSuccessfulEmptyText() {
        server.createContext("/v1/chat/completions") { ex ->
            val bytes = "{\"choices\":[{\"message\":{\"content\":\"json reply\"}}]}".toByteArray()
            ex.responseHeaders.add("Content-Type", "application/json")
            ex.sendResponseHeaders(200, bytes.size.toLong())
            ex.responseBody.use { it.write(bytes) }
        }
        val e = assertFailsWith<StreamingChatException> {
            StreamingChatClient(config()).stream("m", listOf(ChatClient.Message("user", "x")))
        }
        assertEquals("", e.partialText)
        assertEquals(200, e.httpCode)
        assertTrue(e.message!!.contains("text/event-stream"))
    }

    @Test
    fun httpErrorPropagates() {
        server.createContext("/v1/chat/completions") { ex -> ex.sendResponseHeaders(503, -1); ex.close() }
        val e = assertFailsWith<ChatException> {
            StreamingChatClient(config()).stream("m", listOf(ChatClient.Message("user", "x")))
        }
        assertEquals(503, e.httpCode)
    }

    @Test
    fun sseErrorEventFailsAndKeepsPartialText() {
        server.createContext("/v1/chat/completions") { ex ->
            respond(ex, "data: {\"choices\":[{\"delta\":{\"content\":\"part\"}}]}\n\n" +
                "data: {\"error\":{\"message\":\"billing\"}}\n\n")
        }
        val e = assertFailsWith<StreamingChatException> {
            StreamingChatClient(config()).stream("m", listOf(ChatClient.Message("user", "x")))
        }
        assertEquals("part", e.partialText)
        assertEquals(200, e.httpCode)
        assertTrue(e.cause is ChatException)
        assertTrue(e.message!!.contains("billing"))
    }

    @Test
    fun sseErrorMessageRedactsActualGatewayKeyAndKnownSecrets() {
        val key = "opaque-gateway-secret-123456"
        val token = "sk-provider-secret-123456789"
        server.createContext("/v1/chat/completions") { ex ->
            respond(ex, "data: {\"error\":{\"message\":\"billing $key Bearer $token\"}}\n\n")
        }
        val e = assertFailsWith<StreamingChatException> {
            StreamingChatClient(config().copy(gatewayKey = key))
                .stream("m", listOf(ChatClient.Message("user", "x")))
        }
        assertFalse(e.message!!.contains(key))
        assertFalse(e.message!!.contains(token))
        assertTrue(e.message!!.contains("billing"))
    }

    @Test
    fun requestHasStreamTrueAndAcceptSse() {
        var seenBody = ""
        var seenAccept: String? = null
        server.createContext("/v1/chat/completions") { ex ->
            seenAccept = ex.requestHeaders.getFirst("Accept")
            seenBody = ex.requestBody.bufferedReader().readText()
            respond(ex, "data: [DONE]\n\n")
        }
        StreamingChatClient(config()).stream("m", listOf(ChatClient.Message("user", "x")))
        assertTrue(seenBody.contains("\"stream\":true"))
        assertEquals("text/event-stream", seenAccept)
    }
}
