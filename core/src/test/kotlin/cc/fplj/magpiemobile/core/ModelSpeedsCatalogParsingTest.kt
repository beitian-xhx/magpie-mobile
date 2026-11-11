package cc.fplj.magpiemobile.core

import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Test

class ModelSpeedsCatalogParsingTest {

    @Test
    fun modelSpeedsParsesTypicalMap() {
        val raw = """{"deepseek/deepseek-chat":{"ttft":321,"tps":23.5,"n":15},"kimi/k2.5":{"ttft":140,"tps":41.2,"n":7}}"""
        val speeds = parseModelSpeeds(raw)
        assertEquals(2, speeds.size)
        assertEquals(321L, speeds["deepseek/deepseek-chat"]?.ttft)
        assertEquals(23.5, speeds["deepseek/deepseek-chat"]?.tps ?: 0.0, 0.001)
        assertEquals(15, speeds["deepseek/deepseek-chat"]?.n)
    }

    @Test
    fun modelSpeedsMissingFieldsDefault() {
        val raw = """{"a/model":{"ttft":50},"b/model":{"tps":1.2}}"""
        val speeds = parseModelSpeeds(raw)
        assertEquals(0.0, speeds["a/model"]?.tps ?: -1.0, 0.001)
        assertEquals(0L, speeds["b/model"]?.ttft ?: -1L)
    }

    @Test
    fun modelSpeedsMalformedAndBlankReturnEmpty() {
        assertTrue(parseModelSpeeds("").isEmpty())
        assertTrue(parseModelSpeeds("not-json").isEmpty())
        assertTrue(parseModelSpeeds("""{"x": "unexpected-shape"}""").isEmpty())
    }

    @Test
    fun catalogParsesTypicalArray() {
        val raw = """[{"id":"claude/claude-opus-5-5","name":"Claude Opus 5.5","maker":"Anthropic","context":200000,"input":3.0,"cost":15.0,"images":true},{"id":"gpt-5.2","context":400000}]"""
        val models = parseCatalogModels(raw)
        assertEquals(2, models.size)
        assertEquals("claude/claude-opus-5-5", models[0].id)
        assertEquals("Anthropic", models[0].maker)
        assertEquals(200000, models[0].context)
        assertTrue(models[0].images)
        assertEquals("", models[1].name)
        assertEquals(400000, models[1].context)
    }

    @Test
    fun catalogMalformedAndBlankReturnEmpty() {
        assertTrue(parseCatalogModels("").isEmpty())
        assertTrue(parseCatalogModels("not-json").isEmpty())
        assertTrue(parseCatalogModels("""{"models": []}""").isEmpty())
    }
}
