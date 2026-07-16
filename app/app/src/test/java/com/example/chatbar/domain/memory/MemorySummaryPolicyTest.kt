package com.example.chatbar.domain.memory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemorySummaryPolicyTest {
    @Test
    fun rejectsUnqualifiedCurrentStateWords() {
        assertFalse(MemorySummaryPolicy.hasOnlyQualifiedStateWords("目前两人仍然在车站"))
    }

    @Test
    fun acceptsStateWordsQualifiedByTimelineTurnInSameSentence() {
        assertTrue(MemorySummaryPolicy.hasOnlyQualifiedStateWords("截至 T20，两人仍然在车站。"))
    }
}
