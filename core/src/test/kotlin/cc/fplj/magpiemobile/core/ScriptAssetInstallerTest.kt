package cc.fplj.magpiemobile.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ScriptAssetInstallerTest {

    @TempDir
    lateinit var root: File

    @Test
    fun installsJsIntoPrivateRootNoExecBit() {
        val f = ScriptAssetInstaller.install(File(root, "plugins"), "zcode.mjs", "export default {}".toByteArray())
        assertTrue(f.exists())
        assertFalse(f.canExecute())
        assertEquals<Long>("export default {}".toByteArray().size.toLong(), f.length())
    }

    @Test
    fun pathTraversalRejected() {
        for (bad in listOf("../evil.mjs", "/abs/evil.mjs", "a/../../evil.mjs", "sub\\evil.mjs")) {
            assertFailsWith<IllegalArgumentException> {
                ScriptAssetInstaller.install(File(root, "plugins"), bad, "x".toByteArray())
            }
        }
        // 根目录外无残留
        assertEquals(0, root.listFiles { it.isFile }?.size ?: 0)
    }

    @Test
    fun subdirectoryNamesRejected() {
        assertFailsWith<IllegalArgumentException> {
            ScriptAssetInstaller.install(File(root, "plugins"), "sub/x.mjs", "x".toByteArray())
        }
    }

    @Test
    fun oversizeRejected() {
        val big = ByteArray(ScriptAssetInstaller.MAX_BYTES + 1)
        assertFailsWith<IllegalArgumentException> {
            ScriptAssetInstaller.install(File(root, "plugins"), "big.mjs", big)
        }
    }

    @Test
    fun symlinkOverwriteRejected(@TempDir tmp: File) {
        val plugins = File(root, "plugins").apply { mkdirs() }
        val outside = File(tmp, "outside.txt").apply { writeText("victim") }
        val link = File(plugins, "evil.mjs")
        java.nio.file.Files.createSymbolicLink(link.toPath(), outside.toPath())
        assertFailsWith<SecurityException> {
            ScriptAssetInstaller.install(plugins, "evil.mjs", "overwrite".toByteArray())
        }
        assertEquals("victim", outside.readText()) // 外部文件未被覆盖
    }
}
