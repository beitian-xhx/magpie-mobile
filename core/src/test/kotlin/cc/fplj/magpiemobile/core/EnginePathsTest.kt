package cc.fplj.magpiemobile.core

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EnginePathsTest {

    private val filesDir = File("/data/user/0/cc.fplj.magpiemobile/files")
    private val cacheDir = File("/data/user/0/cc.fplj.magpiemobile/cache")

    @Test
    fun buildsPathsFromNativeLibraryDir() {
        val l = EnginePaths.buildLayout("/data/app/~~x/lib/arm64", filesDir, cacheDir)
        assertEquals(File("/data/app/~~x/lib/arm64/libmagpie_exec.so"), EnginePaths.magpieExecutable(l))
        assertEquals(File("/data/app/~~x/lib/arm64/libbun_exec.so"), EnginePaths.bunExecutable(l))
        assertEquals(File("/data/user/0/cc.fplj.magpiemobile/files/config"), l.configDir)
        assertEquals(File("/data/user/0/cc.fplj.magpiemobile/cache/xdg-cache"), l.cacheDir)
    }

    @Test
    fun nullOrBlankNativeLibraryDirRejected() {
        val e = assertFailsWith<IllegalArgumentException> {
            EnginePaths.buildLayout(null, filesDir, cacheDir)
        }
        assertTrue(e.message!!.contains("native"))
        assertFailsWith<IllegalArgumentException> {
            EnginePaths.buildLayout("", filesDir, cacheDir)
        }
    }

    @Test
    fun relativeNativeLibraryDirRejected() {
        assertFailsWith<IllegalArgumentException> {
            EnginePaths.buildLayout("lib/arm64", filesDir, cacheDir)
        }
    }

    @Test
    fun namingConstantsFollowJniLibsConvention() {
        assertTrue(EnginePaths.MAGPIE_LIB.startsWith("lib") && EnginePaths.MAGPIE_LIB.endsWith(".so"))
        assertTrue(EnginePaths.BUN_LIB.startsWith("lib") && EnginePaths.BUN_LIB.endsWith(".so"))
    }
}
