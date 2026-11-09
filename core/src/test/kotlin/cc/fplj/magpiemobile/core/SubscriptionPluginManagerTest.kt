package cc.fplj.magpiemobile.core

import java.io.File
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SubscriptionPluginManagerTest {

    @field:TempDir
    lateinit var tempDir: File

    @Test
    fun installAndListSubscriptionPlugin() {
        val manager = SubscriptionPluginManager(File(tempDir, "plugins"))
        val plugin = SubscriptionPlugin(
            id = "mock-filter",
            name = "Mock Filter Plugin",
            version = "1.0.0",
            entrypoint = "index.js",
        )
        val script = "console.log('Plugin loaded');"

        assertTrue(manager.install(plugin, script))

        val list = manager.list()
        assertEquals(1, list.size)
        assertEquals("mock-filter", list[0].id)
        assertEquals("1.0.0", list[0].version)
        assertTrue(list[0].checksum.isNotEmpty())
        assertTrue(list[0].enabled)
    }

    @Test
    fun upgradeAndRollbackRestoresPreviousVersion() {
        val manager = SubscriptionPluginManager(File(tempDir, "plugins"))
        val v1 = SubscriptionPlugin("sub-plugin", "Sub Plugin", "1.0.0", "main.js")
        manager.install(v1, "// version 1")

        val v2 = SubscriptionPlugin("sub-plugin", "Sub Plugin", "2.0.0", "main.js")
        manager.install(v2, "// version 2")

        assertEquals("2.0.0", manager.list().single().version)

        assertTrue(manager.rollback("sub-plugin"))
        assertEquals("1.0.0", manager.list().single().version)
    }

    @Test
    fun toggleAndRemovePlugin() {
        val manager = SubscriptionPluginManager(File(tempDir, "plugins"))
        val plugin = SubscriptionPlugin("toggle-test", "Toggle Test", "1.0", "run.js")
        manager.install(plugin, "code")

        assertTrue(manager.toggle("toggle-test"))
        assertFalse(manager.list().single().enabled)

        assertTrue(manager.remove("toggle-test"))
        assertTrue(manager.list().isEmpty())
    }

    @Test
    fun invalidPluginIdOrPathTraversalThrowsException() {
        assertFailsWith<IllegalArgumentException> {
            SubscriptionPlugin("../evil", "Evil", "1.0", "index.js")
        }
        assertFailsWith<IllegalArgumentException> {
            SubscriptionPlugin("valid-id", "Evil", "1.0", "../escape.js")
        }
        assertFailsWith<IllegalArgumentException> {
            SubscriptionPlugin("valid-id", "Evil", "1.0", "/root/escape.js")
        }
    }
}
