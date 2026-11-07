package cc.fplj.magpiemobile

import java.net.Socket
import org.junit.Assert.assertTrue
import org.junit.Test

class HealthServerTest {
    private fun request(server: HealthServer, path: String): String = Socket("127.0.0.1", server.boundPort).use {
        it.soTimeout = 1000
        it.getOutputStream().write(("GET " + path + " HTTP/1.1\r\nHost: localhost\r\n\r\n").toByteArray())
        it.getInputStream().bufferedReader().readText()
    }
    @Test fun healthzReturnsStatusButOtherPathsDoNot() {
        val server = HealthServer(infoProvider = { mapOf("state" to "STARTING") }, port = 0)
        server.start()
        try {
            assertTrue(request(server, "/healthz").contains("STARTING"))
            assertTrue(request(server, "/other").startsWith("HTTP/1.1 404"))
        } finally { server.stop() }
    }
    @Test fun diagnosticControlCharactersAreJsonEscaped() {
        val server = HealthServer(infoProvider = { mapOf("message" to "line1\nline2\tquoted\"\\") }, port = 0)
        server.start()
        try {
            val body = request(server, "/healthz").substringAfter("\r\n\r\n")
            assertTrue(body.contains("\\n") && body.contains("\\t") && body.contains("\\\""))
            assertTrue(!body.contains('\n'))
        } finally { server.stop() }
    }
}
