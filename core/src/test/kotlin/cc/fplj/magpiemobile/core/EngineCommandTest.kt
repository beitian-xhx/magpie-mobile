package cc.fplj.magpiemobile.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class EngineCommandTest {

    private val exe = "/data/app/x/lib/arm64/libmagpie_exec.so"

    @Test
    fun serveCommandShape() {
        val cmd = EngineCommand.serve(exe)
        assertEquals(listOf(exe, "serve", "--web=127.0.0.1:3430"), cmd)
    }

    @Test
    fun customPortsValidated() {
        assertEquals(listOf(exe, "serve", "--web=127.0.0.1:4000"), EngineCommand.serve(exe, adminPort = 4000))
        assertFailsWith<IllegalArgumentException> { EngineCommand.serve(exe, adminPort = 80) }
        assertFailsWith<IllegalArgumentException> { EngineCommand.serve(exe, adminPort = 70000) }
        assertFailsWith<IllegalArgumentException> { EngineCommand.serve(exe, adminPort = 3425) }
        assertFailsWith<IllegalArgumentException> { EngineCommand.serve(exe, gatewayPort = 4001) }
    }

    @Test
    fun relativePathRejected() {
        assertFailsWith<IllegalArgumentException> { EngineCommand.serve("libmagpie_exec.so") }
        assertFailsWith<IllegalArgumentException> { EngineCommand.version("bun") }
    }

    @Test
    fun versionCommandShape() {
        assertEquals(listOf(exe, "--version"), EngineCommand.version(exe))
    }
}
