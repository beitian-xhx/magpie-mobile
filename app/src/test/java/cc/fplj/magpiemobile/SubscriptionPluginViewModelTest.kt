package cc.fplj.magpiemobile

import cc.fplj.magpiemobile.core.EnginePaths
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SubscriptionPluginViewModelTest {

    @get:Rule
    val tempDir = TemporaryFolder()

    private fun mockLayout(): EnginePaths.Layout = EnginePaths.Layout(
        nativeLibraryDir = tempDir.newFolder("native"),
        homeDir = tempDir.newFolder("home"),
        cacheDir = tempDir.newFolder("cache"),
        configDir = tempDir.newFolder("config"),
    )

    @Test
    fun initializeAndInstallPlugin() {
        val vm = SubscriptionPluginViewModel()
        val layout = mockLayout()
        vm.initialize(layout)

        val initial = vm.state.value
        assertEquals("none", initial.bunStatus.architecture)
        assertTrue(initial.plugins.isEmpty())

        vm.installPlugin("echo-plugin", "Echo Plugin", "1.0.0", "index.js", "console.log('hi');")
        val state = vm.state.value
        assertEquals(1, state.plugins.size)
        assertEquals("echo-plugin", state.plugins[0].id)
        assertTrue(state.notice.contains("Echo Plugin"))
    }

    @Test
    fun toggleAndRollbackPlugin() {
        val vm = SubscriptionPluginViewModel()
        vm.initialize(mockLayout())

        vm.installPlugin("p1", "Plugin One", "1.0.0", "main.js", "// v1")
        vm.installPlugin("p1", "Plugin One", "2.0.0", "main.js", "// v2")
        assertEquals("2.0.0", vm.state.value.plugins[0].version)

        vm.rollbackPlugin("p1")
        assertEquals("1.0.0", vm.state.value.plugins[0].version)
        assertTrue(vm.state.value.notice.contains("回滚"))

        vm.togglePlugin("p1")
        assertFalse(vm.state.value.plugins[0].enabled)
    }

    @Test
    fun installInvalidPluginSetsError() {
        val vm = SubscriptionPluginViewModel()
        vm.initialize(mockLayout())

        vm.installPlugin("bad id with spaces", "Bad", "1.0", "main.js", "")
        assertTrue(vm.state.value.error.contains("插件标识格式无效"))
    }
}
