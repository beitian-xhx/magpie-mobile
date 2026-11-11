package cc.fplj.magpiemobile.core

import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ProviderServerPinTest {

    private val pin64 = "a".repeat(64)

    @Test
    fun pinValidationAcceptsEmptyAndLowerHex() {
        assertEquals("", providerServerPin(""))
        assertEquals("", providerServerPin("  "))
        assertEquals(pin64, providerServerPin(pin64))
    }

    @Test
    fun pinValidationRejectsBadValues() {
        assertFailsWith<IllegalArgumentException> { providerServerPin("ABC") }
        assertFailsWith<IllegalArgumentException> { providerServerPin("g".repeat(64)) }
        assertFailsWith<IllegalArgumentException> { providerServerPin("A".repeat(64)) }
        assertFailsWith<IllegalArgumentException> { providerServerPin("123") }
        assertFailsWith<IllegalArgumentException> { providerServerPin("a".repeat(63)) }
    }

    @Test
    fun createDraftEmbedsPinOnlyWhenPresent() {
        val with = ProviderCreateDraft("demo", "Demo", "https://api.example.com", "sk-x", listOf("m"), false, pin64)
        assertTrue(with.requestBody().toString().contains("\"serverPin\":\"" + pin64 + "\""))
        val without = ProviderCreateDraft("demo", "Demo", "https://api.example.com", "sk-x", listOf("m"))
        assertTrue(!without.requestBody().toString().contains("serverPin"))
    }

    @Test
    fun editDraftMarksPinDirtyOnlyOnChange() {
        val snapshot = ProviderEditSnapshot("demo", "a".repeat(64), "Demo", "https://api.example.com", listOf("m"), false, ProviderKeyMetadata(true, false, true), pin64)
        val same = ProviderEditDraft(snapshot, serverPin = pin64)
        assertFailsWith<ProviderEditPreconditionException> { same.validate() }
        val changed = ProviderEditDraft(snapshot, serverPin = "b".repeat(64))
        val body = changed.requestBody().toString()
        assertTrue(body.contains("\"serverPin\":\"" + "b".repeat(64) + "\""))
        val cleared = ProviderEditDraft(snapshot, serverPin = "")
        assertTrue(cleared.requestBody().toString().contains("\"serverPin\":\"\""))
    }
}
