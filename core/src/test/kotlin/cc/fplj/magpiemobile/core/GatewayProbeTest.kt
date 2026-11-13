package cc.fplj.magpiemobile.core

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import org.junit.jupiter.api.Test
import kotlin.test.*

class GatewayProbeTest {
    private fun run(response: String, status: Int = 200): GatewayProbe.Attempt {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { ex ->
            ex.requestBody.use { it.readBytes() }
            ex.responseHeaders.add("Content-Type", "text/event-stream")
            ex.sendResponseHeaders(status, 0)
            ex.responseBody.use { it.write(response.toByteArray()) }
        }
        server.start()
        return try { GatewayProbe("http://127.0.0.1:${server.address.port}", 1000).once("fixture-key", "fixture-model") } finally { server.stop(0) }
    }
    @Test fun roleAndKeepaliveAreNotAnAnswer() {
        val result = run(": keepalive\n\ndata: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n\ndata: [DONE]\n\n")
        assertFalse(result.ok)
        assertNull(result.firstContentMs)
    }
    @Test fun reasoningOnlyIsNotAVisibleReply() {
        val result = run("data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"thinking\"},\"finish_reason\":\"length\"}]}\n\ndata: [DONE]\n\n")
        assertFalse(result.ok)
        assertNotNull(result.firstContentMs)
        assertNull(result.firstTextMs)
    }
    @Test fun contentWithoutCompletionIsNotSuccess() {
        val result = run("data: {\"choices\":[{\"delta\":{\"content\":\"pong\"}}]}\n\n")
        assertFalse(result.ok)
        assertTrue(result.error.contains("提前结束"))
    }
    @Test fun streamErrorAfterContentIsReported() {
        val result = run("data: {\"choices\":[{\"delta\":{\"content\":\"p\"}}]}\n\ndata: {\"error\":{\"message\":\"vendor overloaded\"}}\n\n")
        assertFalse(result.ok)
        assertTrue(result.error.contains("vendor overloaded"))
    }
    @Test fun fullReplyAndServerRefusal() {
        val result = run("data: {\"choices\":[{\"delta\":{\"content\":\"pong\"},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n")
        assertTrue(result.ok)
        assertEquals("pong", result.text)
        assertNotNull(result.firstTextMs)
        assertEquals(429, run("rate limited", 429).status)
    }
    @Test fun firstRoleDoesNotStopConsumptionOrTiming() {
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/v1/chat/completions") { ex ->
            ex.requestBody.close(); ex.sendResponseHeaders(200, 0)
            ex.responseBody.use { stream ->
                stream.write("data: {\"choices\":[{\"delta\":{\"role\":\"assistant\"}}]}\n\n".toByteArray()); stream.flush()
                Thread.sleep(120)
                stream.write("data: {\"choices\":[{\"delta\":{\"content\":\"pong\"}}]}\n\n".toByteArray()); stream.flush()
                Thread.sleep(120)
                stream.write("data: [DONE]\n\n".toByteArray())
            }
        }
        server.start()
        try {
            val result = GatewayProbe("http://127.0.0.1:${server.address.port}", 2000).once("fake", "fake")
            assertTrue(result.ok, result.error)
            assertTrue(result.firstContentMs!! >= 100)
            assertTrue(result.millis - result.firstContentMs >= 100)
        } finally { server.stop(0) }
    }
}
