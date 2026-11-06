package cc.fplj.magpiemobile.core

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SseChatParserTest {

    @Test
    fun parsesDeltasAndDone() {
        val deltas = mutableListOf<String>()
        val p = SseChatParser { deltas.add(it) }
        assertFalse(p.feedLine(": keep-alive"))
        assertFalse(p.feedLine("event: ping"))
        assertFalse(p.feedLine("data: {\"choices\":[{\"delta\":{\"content\":\"你\"}}]}"))
        assertFalse(p.feedLine(""))
        assertFalse(p.feedLine("data: {\"choices\":[{\"delta\":{\"content\":\"好\"}}]}"))
        assertTrue(p.feedLine("data: [DONE]"))
        assertEquals(listOf("你", "好"), deltas)
        assertEquals("你好", p.fullText())
    }

    @Test
    fun finalChunkWithoutDeltaContentIgnored() {
        val p = SseChatParser { }
        p.feedLine("data: {\"choices\":[{\"delta\":{\"content\":\"a\"}}]}")
        // 结尾块：只有 finish_reason
        assertFalse(p.feedLine("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}"))
        assertTrue(p.feedLine("data: [DONE]")) // [DONE] 终止流
        assertEquals("a", p.fullText())
    }

    @Test
    fun errorEventThrows() {
        val p = SseChatParser { }
        val e = assertFailsWith<ChatException> {
            p.feedLine("data: {\"error\":{\"message\":\"quota\"}}")
        }
        assertTrue(e.message!!.contains("quota"))
    }

    @Test
    fun unknownEventsSkippedNotFatal() {
        val seen = mutableListOf<String>()
        val p = SseChatParser { seen.add(it) }
        assertFalse(p.feedLine("data: {\"weird_shape\":123}"))
        assertTrue(p.feedLine("data: [DONE]")) // [DONE] 终止流
        assertTrue(seen.isEmpty())
    }

    @Test
    fun partialStreamKeepsReceivedText() {
        // 模拟断流：只收到一半增量，fullText 保留已收内容（plan.md §5）
        val p = SseChatParser { }
        p.feedLine("data: {\"choices\":[{\"delta\":{\"content\":\"part1\"}}]}")
        // 流中断（没有 [DONE]）
        assertEquals("part1", p.fullText())
    }
}
