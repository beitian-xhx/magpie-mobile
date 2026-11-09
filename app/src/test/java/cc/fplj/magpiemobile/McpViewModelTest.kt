package cc.fplj.magpiemobile

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import cc.fplj.magpiemobile.core.McpTransport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class McpViewModelTest {

    @Test
    fun initiallyEmptyAndAddValidServer() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = McpViewModel(app)

        assertEquals(0, vm.state.value.servers.size)

        vm.addServer(
            id = "loopback-tool",
            name = "Local Tool",
            transport = McpTransport.HTTP,
            endpoint = "http://127.0.0.1:8080/mcp",
            description = "Loopback tool service"
        )

        val state = vm.state.value
        assertEquals(1, state.servers.size)
        assertEquals(1, state.activeCount)
        assertEquals("Local Tool", state.servers[0].name)
        assertTrue(state.notice.contains("Local Tool"))
    }

    @Test
    fun addServerWithInsecureEndpointSetsError() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = McpViewModel(app)

        vm.addServer(
            id = "bad-remote",
            name = "Bad Remote",
            transport = McpTransport.HTTP,
            endpoint = "http://remote.example.com/mcp"
        )

        val state = vm.state.value
        assertEquals(0, state.servers.size)
        assertTrue(state.error.contains("必须使用加密 HTTPS"))
    }

    @Test
    fun toggleAndDeleteServer() {
        val app = ApplicationProvider.getApplicationContext<Application>()
        val vm = McpViewModel(app)

        vm.addServer(
            id = "toggle-test",
            name = "Toggle Test",
            transport = McpTransport.SSE,
            endpoint = "https://sse.example.com/mcp"
        )
        assertEquals(1, vm.state.value.activeCount)

        vm.toggleServer("toggle-test")
        assertEquals(0, vm.state.value.activeCount)

        vm.deleteServer("toggle-test")
        assertEquals(0, vm.state.value.servers.size)
    }
}
