package cc.fplj.magpiemobile.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class GatewayKeyRegistrarTest {
    private val exe = "/data/app/example/lib/arm64/libmagpie_exec.so"
    @Test fun listUsesArgvNotShellText() {
        assertEquals(listOf(exe, "gateway-key", "list"), GatewayKeyRegistrar.listKeysArguments(exe))
    }
    @Test fun addUsesAnUnquotedArgvElement() {
        assertEquals(listOf(exe, "gateway-key", "add", "phone-app"), GatewayKeyRegistrar.addKeyArguments(exe, "phone-app"))
    }
    @Test fun invalidInputIsRejected() {
        assertFailsWith<IllegalArgumentException> { GatewayKeyRegistrar.addKeyArguments(exe, "a;rm") }
        assertFailsWith<IllegalArgumentException> { GatewayKeyRegistrar.addKeyArguments("relative", "app") }
        assertFailsWith<IllegalArgumentException> { GatewayKeyRegistrar.addKeyArguments(exe, "") }
    }
    @Suppress("DEPRECATION")
    @Test fun aMaskedTableCannotProveRegistration() {
        assertFailsWith<UnsupportedOperationException> { GatewayKeyRegistrar.appKeyRegistered("***cdef", "sk-magpie-key-abcdef") }
    }
}
