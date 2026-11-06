package cc.fplj.magpiemobile.core

import org.junit.jupiter.api.Test
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class EngineProcessRunnerTest {

    private val tmp = File(System.getProperty("java.io.tmpdir"), "runner-test").apply { mkdirs() }

    @Test
    fun relativePathRejected() {
        val e = assertFailsWith<IllegalArgumentException> {
            EngineProcessRunner().run("bin/tool", emptyList(), tmp, tmp, tmp)
        }
        assertTrue(e.message!!.contains("绝对路径"))
    }

    @Test
    fun missingBinaryRejected() {
        val e = assertFailsWith<IllegalArgumentException> {
            EngineProcessRunner().run(tmp.absolutePath + "/no-such-bin", emptyList(), tmp, tmp, tmp)
        }
        assertTrue(e.message!!.contains("不存在"))
    }

    @Test
    fun runsRealBinaryWithIsolatedEnv() {
        // /bin/sh 是绝对路径真实 ELF（JVM 测试环境），验证环境注入与输出捕获
        val r = EngineProcessRunner().run(
            "/bin/sh",
            listOf("-c", "echo HOME=\$HOME; echo PATH=\$PATH"),
            homeDir = File(tmp, "home"),
            cacheDir = File(tmp, "cache"),
            configDir = File(tmp, "config"),
        )
        assertEquals(0, r.exitCode)
        assertTrue(r.stdout.contains("HOME="))
        // 环境白名单：PATH 固定为系统路径，不含宿主任意变量
        assertTrue(r.stdout.contains("PATH=/system/bin:/system/xbin"))
    }

    @Test
    fun exitCodePropagates() {
        val r = EngineProcessRunner().run(
            "/bin/sh", listOf("-c", "exit 3"),
            tmp, tmp, tmp,
        )
        assertEquals(3, r.exitCode)
    }
}
