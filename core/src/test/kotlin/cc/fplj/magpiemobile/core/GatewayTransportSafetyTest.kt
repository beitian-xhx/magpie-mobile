package cc.fplj.magpiemobile.core

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.ProxySelector
import java.net.Proxy
import java.net.SocketAddress
import java.net.URI
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GatewayTransportSafetyTest {
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply { start() }
    private fun cfg() = GatewayConfig(gatewayKey = "local-client-test-key", gatewayBaseUrl = "http://127.0.0.1:" + server.address.port)
    @AfterEach fun close() { server.stop(0) }

    @Test fun gatewayClientsNeverSendLocalCredentialsViaSystemProxy() {
        val original = ProxySelector.getDefault()
        val selected = AtomicInteger()
        ProxySelector.setDefault(object : ProxySelector() {
            override fun select(uri: URI): List<Proxy> { selected.incrementAndGet(); throw AssertionError("System proxy must not receive a gateway request") }
            override fun connectFailed(uri: URI, sa: SocketAddress, ioe: IOException) = Unit
        })
        try {
            server.createContext("/v1/models") { ex ->
                val body = "{\"data\":[]}".toByteArray()
                ex.sendResponseHeaders(200, body.size.toLong()); ex.responseBody.use { it.write(body) }
            }
            assertEquals(emptyList<ModelEntry>(), ModelsClient(cfg()).list())
            assertEquals(0, selected.get())
        } finally { ProxySelector.setDefault(original) }
    }

    @Test fun nonStreamingChatDoesNotFollowRedirectOrReplayPost() {
        val calls = AtomicInteger()
        server.createContext("/v1/chat/completions") { ex ->
            calls.incrementAndGet(); ex.requestBody.readBytes()
            ex.responseHeaders.add("Location", "/elsewhere"); ex.sendResponseHeaders(307, -1); ex.close()
        }
        server.createContext("/elsewhere") { ex -> calls.incrementAndGet(); ex.sendResponseHeaders(200, -1); ex.close() }
        assertFailsWith<ChatException> { ChatClient(cfg()).complete("m", listOf(ChatClient.Message("user", "probe"))) }
        assertEquals(1, calls.get())
    }
}
