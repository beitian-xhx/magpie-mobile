package cc.fplj.magpiemobile.core

import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class KeyRestrictionsParsingTest {

    private val noDefaults = Json { encodeDefaults = false }
    private val lenient = Json { ignoreUnknownKeys = true }

    @Test
    fun changePayloadOmitsEmptyFields() {
        val payload = noDefaults.encodeToString(
            CallerKeyChange(key = "abc123", models = listOf("a/model"))
        )
        assertTrue(payload.contains("\"key\":\"abc123\""))
        assertTrue(payload.contains("\"models\":["))
        assertTrue(!payload.contains("aliases"))
        assertTrue(!payload.contains("serviceTier"))
    }

    @Test
    fun emptyModelsFieldIsDropped() {
        val payload = noDefaults.encodeToString(CallerKeyChange(key = "abc123"))
        assertTrue(!payload.contains("models"))
        assertTrue(!payload.contains("aliases"))
    }

    @Test
    fun keysResponseParsesLimitsAndTier() {
        val raw = """{"keys":[{"id":"k1","name":"n","models":["a/b"],"aliases":{"fast":"a/b"},
            "serviceTier":"priority","maxConcurrency":4,
            "limit":{"period":"day","tokens":1000,"cost":0.5,"cacheReads":true},
            "moreLimits":[{"period":"month","cost":12.0}]}]}"""
        val decoded = lenient.decodeFromString<CallerKeysResponseTestHelper>(raw)
        val key = decoded.keys[0]
        assertEquals("k1", key.id)
        assertEquals(listOf("a/b"), key.models)
        assertEquals(mapOf("fast" to "a/b"), key.aliases)
        assertEquals("priority", key.serviceTier)
        assertEquals(4, key.maxConcurrency)
        assertEquals(1000L, key.limit?.tokens)
        assertTrue(key.limit?.cacheReads == true)
        assertEquals(1, key.moreLimits.size)
        assertEquals("month", key.moreLimits[0].period)
        assertNull(key.limit?.days?.takeIf { it != 0 })
    }
}

/** 仅测试用途：直接按 keys 顶层形状解析 RegisteredGatewayKey。 */
@kotlinx.serialization.Serializable
private data class CallerKeysResponseTestHelper(val keys: List<RegisteredGatewayKey>)
