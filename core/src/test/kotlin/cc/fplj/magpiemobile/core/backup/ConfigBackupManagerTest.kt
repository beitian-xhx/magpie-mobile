package cc.fplj.magpiemobile.core.backup

import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.time.Instant
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertFailsWith
import org.junit.jupiter.api.Test

class ConfigBackupManagerTest {

    private val fixedTime = Instant.parse("2026-11-08T12:00:00Z")

    private val sampleProviders = listOf(
        ConfigBackupManager.ExportedProvider(
            id = "custom-openai",
            name = "My Custom OpenAI",
            chatUrl = "https://api.openai.com/v1",
            models = listOf("gpt-4o", "gpt-4o-mini"),
        ),
        ConfigBackupManager.ExportedProvider(
            id = "local-mock",
            name = "Local Mock Gateway",
            chatUrl = "http://127.0.0.1:8080/v1",
            models = listOf("mock-model"),
        )
    )

    private val samplePreferences = ConfigBackupManager.ExportedPreferences(
        theme = "DARK",
        defaultModel = "gpt-4o",
    )

    @Test
    fun normalExportAndImportRoundTrip() {
        val out = ByteArrayOutputStream()
        ConfigBackupManager.export(out, sampleProviders, samplePreferences, fixedTime)

        val exportedBytes = out.toByteArray()
        assertTrue(exportedBytes.isNotEmpty())
        val jsonText = exportedBytes.toString(Charsets.UTF_8)
        assertTrue(jsonText.contains("schemaVersion"))
        assertTrue(jsonText.contains("Magpie-Mobile"))
        assertTrue(jsonText.contains("custom-openai"))
        assertTrue(jsonText.contains("DARK"))
        assertFalse(jsonText.contains("sk-"))

        val inStream = ByteArrayInputStream(exportedBytes)
        val result = ConfigBackupManager.import(inStream)

        assertEquals(1, result.schemaVersion)
        assertEquals(ConfigBackupManager.APP_VERSION, result.appVersion)
        assertEquals("2026-11-08T12:00:00Z", result.exportedAt)
        assertEquals(2, result.providers.size)
        assertEquals("custom-openai", result.providers[0].id)
        assertEquals("My Custom OpenAI", result.providers[0].name)
        assertEquals("https://api.openai.com/v1", result.providers[0].chatUrl)
        assertEquals(listOf("gpt-4o", "gpt-4o-mini"), result.providers[0].models)
        assertEquals("DARK", result.preferences.theme)
        assertEquals("gpt-4o", result.preferences.defaultModel)
        assertTrue(result.sha256.isNotBlank())
    }

    @Test
    fun emptyProvidersAndPreferencesRoundTrip() {
        val out = ByteArrayOutputStream()
        ConfigBackupManager.export(out, emptyList(), ConfigBackupManager.ExportedPreferences(), fixedTime)

        val inStream = ByteArrayInputStream(out.toByteArray())
        val result = ConfigBackupManager.import(inStream)

        assertEquals(0, result.providers.size)
        assertEquals("SYSTEM", result.preferences.theme)
        assertEquals("", result.preferences.defaultModel)
    }

    @Test
    fun exportRejectsSuspectedApiKeyOrSecretInFields() {
        val leakyProviders = listOf(
            ConfigBackupManager.ExportedProvider(
                id = "leaky",
                name = "Leaky sk-abcdef12345678",
                chatUrl = "https://api.example.com",
                models = listOf("m1"),
            )
        )
        assertFailsWith<BackupSecurityException> {
            ConfigBackupManager.export(ByteArrayOutputStream(), leakyProviders, samplePreferences, fixedTime)
        }

        val tokenInUrl = listOf(
            ConfigBackupManager.ExportedProvider(
                id = "leaky-url",
                name = "Provider",
                chatUrl = "https://api.example.com?api_key=secret12345",
                models = listOf("m1"),
            )
        )
        assertFailsWith<Exception> {
            ConfigBackupManager.export(ByteArrayOutputStream(), tokenInUrl, samplePreferences, fixedTime)
        }
    }

    @Test
    fun importRejectsPayloadLargerThan5MB() {
        val largeStream = object : InputStream() {
            var sent = 0
            val limit = ConfigBackupManager.MAX_BACKUP_BYTES + 10
            override fun read(): Int {
                if (sent >= limit) return -1
                sent++
                return 'a'.code
            }
        }
        assertFailsWith<BackupPayloadTooLargeException> {
            ConfigBackupManager.import(largeStream)
        }
    }

    @Test
    fun importRejectsTamperedSha256Checksum() {
        val out = ByteArrayOutputStream()
        ConfigBackupManager.export(out, sampleProviders, samplePreferences, fixedTime)
        val normalText = out.toString(Charsets.UTF_8)

        // Modify model name without updating sha256
        val tamperedText = normalText.replace("My Custom OpenAI", "Hacked Provider")
        assertFailsWith<BackupChecksumMismatchException> {
            ConfigBackupManager.import(ByteArrayInputStream(tamperedText.toByteArray(Charsets.UTF_8)))
        }

        // Tamper only sha256 value
        val tamperedSha = normalText.replaceFirst("\"sha256\": \"", "\"sha256\": \"0000000000")
        assertFailsWith<BackupChecksumMismatchException> {
            ConfigBackupManager.import(ByteArrayInputStream(tamperedSha.toByteArray(Charsets.UTF_8)))
        }
    }

    @Test
    fun importRejectsInvalidJsonAndEmptyStream() {
        assertFailsWith<BackupValidationException> {
            ConfigBackupManager.import(ByteArrayInputStream(ByteArray(0)))
        }
        assertFailsWith<BackupValidationException> {
            ConfigBackupManager.import(ByteArrayInputStream("not a json".toByteArray(Charsets.UTF_8)))
        }
        assertFailsWith<BackupValidationException> {
            ConfigBackupManager.import(ByteArrayInputStream("[1, 2, 3]".toByteArray(Charsets.UTF_8)))
        }
    }

    @Test
    fun importRejectsUnsupportedSchemaVersion() {
        val out = ByteArrayOutputStream()
        ConfigBackupManager.export(out, sampleProviders, samplePreferences, fixedTime)
        val normalText = out.toString(Charsets.UTF_8)
        val futureSchema = normalText.replaceFirst("\"schemaVersion\": 1", "\"schemaVersion\": 2")
        assertFailsWith<BackupValidationException> {
            ConfigBackupManager.import(ByteArrayInputStream(futureSchema.toByteArray(Charsets.UTF_8)))
        }
    }

    @Test
    fun importRejectsInvalidUrlSchemeOrControlChars() {
        val badJson = """
            {
              "schemaVersion": 1,
              "appName": "Magpie-Mobile",
              "appVersion": "0.1.23",
              "exportedAt": "2026-11-08T12:00:00Z",
              "providers": [
                {
                  "id": "p1",
                  "name": "Bad Protocol",
                  "chatUrl": "javascript:alert(1)",
                  "models": ["m1"]
                }
              ],
              "preferences": {
                "theme": "SYSTEM",
                "defaultModel": ""
              },
              "sha256": "dummy"
            }
        """.trimIndent()
        assertFailsWith<BackupValidationException> {
            ConfigBackupManager.import(ByteArrayInputStream(badJson.toByteArray(Charsets.UTF_8)))
        }

        val fileSchemeJson = badJson.replace("javascript:alert(1)", "file:///data/data/cc.fplj.magpiemobile/secrets")
        assertFailsWith<BackupValidationException> {
            ConfigBackupManager.import(ByteArrayInputStream(fileSchemeJson.toByteArray(Charsets.UTF_8)))
        }
    }

    @Test
    fun importRejectsInvalidProviderIdAndControlChars() {
        val badIdJson = """
            {
              "schemaVersion": 1,
              "appName": "Magpie-Mobile",
              "appVersion": "0.1.23",
              "exportedAt": "2026-11-08T12:00:00Z",
              "providers": [
                {
                  "id": "INVALID ID WITH SPACES",
                  "name": "Bad ID",
                  "chatUrl": "https://api.openai.com/v1",
                  "models": ["m1"]
                }
              ],
              "preferences": {
                "theme": "SYSTEM",
                "defaultModel": ""
              },
              "sha256": "dummy"
            }
        """.trimIndent()
        assertFailsWith<BackupValidationException> {
            ConfigBackupManager.import(ByteArrayInputStream(badIdJson.toByteArray(Charsets.UTF_8)))
        }
    }
}
