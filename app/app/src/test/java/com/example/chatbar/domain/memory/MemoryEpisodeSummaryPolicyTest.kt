package com.example.chatbar.domain.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryEpisodeSummaryPolicyTest {
    @Test
    fun maxLengthStartsAtFiftyAndAddsTwentyPerTurn() {
        assertEquals(50, MemoryEpisodeSummaryPolicy.maxChars(1))
        assertEquals(70, MemoryEpisodeSummaryPolicy.maxChars(2))
        assertEquals(90, MemoryEpisodeSummaryPolicy.maxChars(3))
        assertEquals(110, MemoryEpisodeSummaryPolicy.maxChars(4))
        assertEquals(130, MemoryEpisodeSummaryPolicy.maxChars(5))
        assertEquals(150, MemoryEpisodeSummaryPolicy.maxChars(6))
    }

    @Test
    fun hardLimitCountsUnicodeCharacters() {
        assertEquals(2, MemoryEpisodeSummaryPolicy.characterCount("人🙂"))
        assertTrue(MemoryEpisodeSummaryPolicy.isWithinLimit("事".repeat(70), 2))
        assertFalse(MemoryEpisodeSummaryPolicy.isWithinLimit("事".repeat(71), 2))
    }
}
