package com.example.chatbar.domain.image

import java.util.Locale
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelAiHistoryExportNamingTest {
    @Test
    fun defaultNameUsesGenerationTimeAndSelectionOrder() {
        val name = NovelAiHistoryExportNaming.displayName(
            sourcePath = "/images/source.png",
            createdAt = 1_773_000_000_123L,
            selectionIndex = 4,
            customTitle = "",
            locale = Locale.US,
            timeZone = TimeZone.getTimeZone("UTC")
        )

        assertEquals("CCBImage_20260308_200000_123_4.png", name)
    }

    @Test
    fun customNameRemovesExtensionAndInvalidCharacters() {
        val name = NovelAiHistoryExportNaming.displayName(
            sourcePath = "/images/source.webp",
            createdAt = 0L,
            selectionIndex = 2,
            customTitle = "  角色:夜景.png  "
        )

        assertEquals("角色_夜景_2.webp", name)
    }

    @Test
    fun conflictComparisonTrimsAndIgnoresCase() {
        assertTrue(NovelAiHistoryExportNaming.sameName(" Demo_1.PNG ", "demo_1.png"))
    }
}
