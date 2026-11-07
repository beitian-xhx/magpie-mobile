package cc.fplj.magpiemobile.core

import java.io.File
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EngineEnvironmentTest {
    @TempDir lateinit var root: File

    @Test fun freshInstallCreatesDirectoriesAndUsesBundledBun() {
        val layout = EnginePaths.buildLayout(File(root, "lib").absolutePath, File(root, "files"), File(root, "cache"))
        val env = EngineEnvironment.prepare(layout)
        assertTrue(layout.configDir.isDirectory)
        assertTrue(layout.cacheDir.isDirectory)
        assertTrue(layout.homeDir.isDirectory)
        assertEquals(EnginePaths.bunExecutable(layout).absolutePath, env["MAGPIE_BUN"])
        assertEquals(setOf("HOME", "TMPDIR", "XDG_CONFIG_HOME", "XDG_CACHE_HOME", "MAGPIE_BUN", "PATH"), env.keys)
    }

    @Test fun existingDirectoriesAreReusable() {
        val layout = EnginePaths.buildLayout(File(root, "lib").absolutePath, root, File(root, "cache"))
        assertEquals(EngineEnvironment.prepare(layout), EngineEnvironment.prepare(layout))
    }

    @Test fun fileInPlaceOfDirectoryFailsExplicitly() {
        val blocked = File(root, "blocked").apply { writeText("existing file") }
        val layout = EnginePaths.Layout(root, root, blocked, File(root, "config"))
        assertFailsWith<IllegalStateException> { EngineEnvironment.prepare(layout) }
        assertEquals("existing file", blocked.readText())
    }
}
