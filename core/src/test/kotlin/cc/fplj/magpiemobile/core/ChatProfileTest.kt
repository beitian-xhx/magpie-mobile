package cc.fplj.magpiemobile.core

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatProfileTest {

    @field:TempDir
    lateinit var tempDir: File

    @Test
    fun defaultProfileIsValidAndPresent() {
        val store = ChatProfileStore(File(tempDir, "profiles.json"))
        val list = store.list()
        assertEquals(1, list.size)
        assertEquals("default", list[0].id)
        assertEquals("通用助手", list[0].name)
    }

    @Test
    fun saveAndListCustomProfile() {
        val store = ChatProfileStore(File(tempDir, "profiles.json"))
        val codingProfile = ChatProfile(
            id = "coder",
            name = "代码编程助手",
            systemPrompt = "You are an expert Kotlin and Android engineer.",
            temperature = 0.2,
            topP = 0.95,
            maxTokens = 4096,
            modelAlias = "claude-code"
        )
        store.save(codingProfile)

        val list = store.list()
        assertEquals(2, list.size)
        val fetched = list.first { it.id == "coder" }
        assertEquals("代码编程助手", fetched.name)
        assertEquals(0.2, fetched.temperature)
        assertEquals("claude-code", fetched.modelAlias)
    }

    @Test
    fun defaultProfileCannotBeDeleted() {
        val store = ChatProfileStore(File(tempDir, "profiles.json"))
        assertFalse(store.delete("default"))
        assertEquals(1, store.list().size)
    }

    @Test
    fun validationRejectsOutOfRangeParameters() {
        assertFailsWith<IllegalArgumentException> {
            ChatProfile("test", "Test", temperature = -0.1)
        }
        assertFailsWith<IllegalArgumentException> {
            ChatProfile("test", "Test", temperature = 2.5)
        }
        assertFailsWith<IllegalArgumentException> {
            ChatProfile("test", "Test", topP = 1.5)
        }
        assertFailsWith<IllegalArgumentException> {
            ChatProfile("test", "Test", maxTokens = -10)
        }
        assertFailsWith<IllegalArgumentException> {
            ChatProfile("invalid id with spaces", "Test")
        }
    }
}
