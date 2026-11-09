package cc.fplj.magpiemobile.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class PluginMarketTest {

    @Test
    fun curatedPluginsAreValid() {
        val list = CuratedPluginCatalog.VERIFIED_PLUGINS
        assertTrue(list.isNotEmpty())
        assertEquals(3, list.size)

        val first = list.first()
        assertEquals("magpie-openai-adapter", first.id)
        assertTrue(first.downloadUrl.startsWith("https://"))
        assertEquals(64, first.checksumSha256.length)
    }

    @Test
    fun rejectsInsecureDownloadUrlScheme() {
        assertFailsWith<IllegalArgumentException> {
            PluginCatalogEntry(
                id = "insecure-tool",
                name = "Insecure Tool",
                version = "1.0.0",
                description = "Tool with remote HTTP",
                author = "Author",
                downloadUrl = "http://remote-server.com/plugin.js",
                checksumSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
            )
        }
    }

    @Test
    fun rejectsInvalidChecksumLengthOrChars() {
        assertFailsWith<IllegalArgumentException> {
            PluginCatalogEntry(
                id = "bad-hash",
                name = "Bad Hash Tool",
                version = "1.0.0",
                description = "Bad Hash",
                author = "Author",
                downloadUrl = "https://example.com/plugin.js",
                checksumSha256 = "not-a-valid-sha256"
            )
        }
    }

    @Test
    fun rejectsMalformedSemVer() {
        assertFailsWith<IllegalArgumentException> {
            PluginCatalogEntry(
                id = "bad-ver",
                name = "Bad Ver Tool",
                version = "invalid_version",
                description = "Bad Ver",
                author = "Author",
                downloadUrl = "https://example.com/plugin.js",
                checksumSha256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855"
            )
        }
    }

    @Test
    fun parseCustomCatalogJson() {
        val json = """
            [
              {
                "id": "custom-ext",
                "name": "Custom Extension",
                "version": "2.1.0-alpha",
                "description": "A custom test plugin",
                "author": "Tester",
                "downloadUrl": "https://example.com/custom.js",
                "checksumSha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "capabilities": ["test"]
              }
            ]
        """.trimIndent()

        val parsed = CuratedPluginCatalog.parseCatalogJson(json)
        assertEquals(1, parsed.size)
        assertEquals("custom-ext", parsed[0].id)
        assertEquals("2.1.0-alpha", parsed[0].version)
        assertEquals(listOf("test"), parsed[0].capabilities)
    }
}
