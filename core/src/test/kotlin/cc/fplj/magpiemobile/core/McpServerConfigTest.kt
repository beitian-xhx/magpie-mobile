package cc.fplj.magpiemobile.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class McpServerConfigTest {

    @field:TempDir
    lateinit var tempDir: File

    @Test
    fun validHttpLoopbackAndHttpsEndpointsAreAccepted() {
        val loopback = McpServerConfig(
            id = "local-math",
            name = "Local Math Tools",
            transport = McpTransport.HTTP,
            endpoint = "http://127.0.0.1:8080/mcp",
            description = "Math solver on loopback"
        )
        assertEquals("local-math", loopback.id)
        assertTrue(loopback.enabled)

        val remoteHttps = McpServerConfig(
            id = "github-mcp",
            name = "GitHub Context Provider",
            transport = McpTransport.SSE,
            endpoint = "https://mcp.example.com/sse",
            description = "Remote MCP server over TLS"
        )
        assertEquals("github-mcp", remoteHttps.id)
    }

    @Test
    fun plainHttpOnRemoteHostIsRejectedForSecurity() {
        assertFailsWith<IllegalArgumentException> {
            McpServerConfig(
                id = "insecure-remote",
                name = "Insecure Remote",
                transport = McpTransport.HTTP,
                endpoint = "http://api.example.com/mcp"
            )
        }
    }

    @Test
    fun credentialsOrTokensInEndpointAreRejected() {
        assertFailsWith<IllegalArgumentException> {
            McpServerConfig(
                id = "userinfo-leak",
                name = "Userinfo Leak",
                transport = McpTransport.HTTP,
                endpoint = "https://user:password@example.com/mcp"
            )
        }

        assertFailsWith<IllegalArgumentException> {
            McpServerConfig(
                id = "query-leak",
                name = "Query Leak",
                transport = McpTransport.HTTP,
                endpoint = "https://127.0.0.1:8080/mcp?api_key=sk-12345"
            )
        }
    }

    @Test
    fun stdioTraversalOrRelativePathIsRejected() {
        assertFailsWith<IllegalArgumentException> {
            McpServerConfig(
                id = "stdio-rel",
                name = "Relative Path",
                transport = McpTransport.STDIO,
                endpoint = "bin/my_mcp"
            )
        }

        assertFailsWith<IllegalArgumentException> {
            McpServerConfig(
                id = "stdio-traversal",
                name = "Traversal Path",
                transport = McpTransport.STDIO,
                endpoint = "/data/data/cc.fplj.magpiemobile/../evil"
            )
        }
    }

    @Test
    fun storeCrudAndToggleOperations() {
        val store = McpServerStore(File(tempDir, "mcp.json"))
        assertEquals(0, store.list().size)

        val s1 = McpServerConfig(
            id = "server-1",
            name = "Server 1",
            transport = McpTransport.HTTP,
            endpoint = "http://localhost:3000/mcp"
        )
        store.save(s1)
        assertEquals(1, store.list().size)
        assertTrue(store.list()[0].enabled)

        assertTrue(store.toggleEnabled("server-1"))
        assertFalse(store.list()[0].enabled)

        assertTrue(store.delete("server-1"))
        assertEquals(0, store.list().size)
        assertFalse(store.delete("server-1"))
    }
}
