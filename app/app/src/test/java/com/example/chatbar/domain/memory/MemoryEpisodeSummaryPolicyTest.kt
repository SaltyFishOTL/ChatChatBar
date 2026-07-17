package com.example.chatbar.domain.memory

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryEpisodeSummaryPolicyTest {
    @Test
    fun promptTargetStartsAtFiftyAndAddsTwentyPerTurn() {
        assertEquals(50, MemoryEpisodeSummaryPolicy.promptMaxChars(1))
        assertEquals(70, MemoryEpisodeSummaryPolicy.promptMaxChars(2))
        assertEquals(90, MemoryEpisodeSummaryPolicy.promptMaxChars(3))
        assertEquals(110, MemoryEpisodeSummaryPolicy.promptMaxChars(4))
        assertEquals(130, MemoryEpisodeSummaryPolicy.promptMaxChars(5))
        assertEquals(150, MemoryEpisodeSummaryPolicy.promptMaxChars(6))
    }

    @Test
    fun hardLimitIsTwicePromptTargetAndCountsUnicodeCharacters() {
        assertEquals(100, MemoryEpisodeSummaryPolicy.hardMaxChars(1))
        assertEquals(140, MemoryEpisodeSummaryPolicy.hardMaxChars(2))
        assertEquals(300, MemoryEpisodeSummaryPolicy.hardMaxChars(6))
        assertEquals(2, MemoryEpisodeSummaryPolicy.characterCount("人🙂"))
        assertTrue(MemoryEpisodeSummaryPolicy.isWithinHardLimit("事".repeat(140), 2))
        assertFalse(MemoryEpisodeSummaryPolicy.isWithinHardLimit("事".repeat(141), 2))
    }
}
