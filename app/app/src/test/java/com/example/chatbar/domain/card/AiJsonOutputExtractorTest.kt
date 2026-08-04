package com.example.chatbar.domain.card

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AiJsonOutputExtractorTest {
    @Test
    fun `unescaped inner quotes are escaped and object still extracts`() {
        val raw = """{"facts":["称呼仁菜为"NI~NA"。","昵称为"Pleia"，谐音"Player"。"],"notes":[]}"""

        val candidates = raw.extractJsonObjectCandidates()

        assertEquals(1, candidates.size)
        assertTrue(candidates.single().contains("称呼仁菜为\\\"NI~NA\\\"。"))
        assertTrue(candidates.single().contains("谐音\\\"Player\\\""))
    }

    @Test
    fun `missing commas between array elements are inserted`() {
        val raw = """{"facts":["a" "b","c" {"d":1}],"notes":[]}"""

        val candidates = raw.extractJsonObjectCandidates()

        assertTrue(candidates.any { it.contains("\"a\", \"b\"") })
        assertTrue(candidates.any { it.contains("\"c\", {") })
    }

    @Test
    fun `truncated json with unclosed brackets is closed and recovered`() {
        val raw = """{"facts":["a", "b", "c"]"""

        val candidates = raw.extractJsonObjectCandidates()

        assertEquals(1, candidates.size)
        assertEquals("""{"facts":["a", "b", "c"]}""", candidates.single())
    }

    @Test
    fun `truncated json mid-string closes the string and brackets`() {
        val raw = """{"facts":["a", "名字是"""

        val candidates = raw.extractJsonObjectCandidates()

        assertEquals(1, candidates.size)
        assertEquals("""{"facts":["a", "名字是"]}""", candidates.single())
    }

    @Test
    fun `repair leaves valid json untouched`() {
        val raw = """{"name":"雨巷","characters":[{"id":"c1","profile":"调查员"}]}"""

        val repaired = raw.repairJsonQuotesAndCommas()

        assertEquals(raw, repaired)
    }

    @Test
    fun `inner quote adjacent to closing quote is escaped`() {
        val raw = """{"profile":"被称为"雾之侦探""}"""

        val repaired = raw.repairJsonQuotesAndCommas()

        assertEquals("""{"profile":"被称为\"雾之侦探\""}""", repaired)
    }

    @Test
    fun `escaped quotes and escapes stay intact`() {
        val raw = """{"text":"她说：\"你好\"，然后\\离开","facts":["a"]}"""

        val repaired = raw.repairJsonQuotesAndCommas()

        assertEquals(raw, repaired)
    }

    @Test
    fun `no json at all still yields no candidates`() {
        assertTrue("not json at all".extractJsonObjectCandidates().isEmpty())
        assertTrue("".extractJsonObjectCandidates().isEmpty())
    }
}
