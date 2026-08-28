package com.example.chatbar.ui.imageprompt

import org.junit.Assert.assertEquals
import org.junit.Test

class NovelAiHistorySelectionPolicyTest {
    @Test
    fun deselectCompactsOrderAndReselectAppends() {
        val selected = listOf("first", "second", "third")
        val compacted = NovelAiHistorySelectionPolicy.toggle(selected, "second")
        val reselected = NovelAiHistorySelectionPolicy.toggle(compacted, "second")

        assertEquals(listOf("first", "third"), compacted)
        assertEquals(listOf("first", "third", "second"), reselected)
    }

    @Test
    fun retainDropsImagesRemovedFromHistory() {
        assertEquals(
            listOf("second"),
            NovelAiHistorySelectionPolicy.retain(
                listOf("first", "second", "third"),
                setOf("second", "fourth")
            )
        )
    }
}
