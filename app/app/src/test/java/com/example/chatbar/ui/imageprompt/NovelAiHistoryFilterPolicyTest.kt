package com.example.chatbar.ui.imageprompt

import com.example.chatbar.domain.image.NovelAiCharacterPromptDraft
import com.example.chatbar.domain.image.NovelAiGenerationHistoryEntry
import com.example.chatbar.domain.image.NovelAiGenerationHistoryImage
import com.example.chatbar.domain.image.NovelAiGenerationRecipe
import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class NovelAiHistoryFilterPolicyTest {
    private val timeZone = TimeZone.getTimeZone("GMT+08:00")

    @Test
    fun flattenSortsNewestBatchFirstAndKeepsImageOrder() {
        val older = entry("older", timestamp(2025, 12, 31), listOf("a", "b"))
        val newer = entry("newer", timestamp(2026, 1, 1), listOf("c", "d"))

        val result = NovelAiHistoryFilterPolicy.filter(
            entries = listOf(older, newer),
            searchQuery = "",
            dateFilter = null,
            timeZone = timeZone
        )

        assertEquals(listOf("c", "d", "a", "b"), result.map { it.image.path })
        assertEquals(listOf(0, 1, 0, 1), result.map { it.batchImageIndex })
    }

    @Test
    fun searchMatchesEveryPositivePromptButNotNegativePrompt() {
        val entries = listOf(
            entry("style", timestamp(2026, 1, 1), listOf("style.png"), style = "Oil PAINTING"),
            entry("base", timestamp(2026, 1, 2), listOf("base.png"), base = "moonlit street"),
            entry("role", timestamp(2026, 1, 3), listOf("role.png"), character = "red-haired girl"),
            entry("negative", timestamp(2026, 1, 4), listOf("negative.png"), negative = "watermark")
        )

        assertEquals(
            listOf("style.png"),
            NovelAiHistoryFilterPolicy.filter(entries, "oil painting", null, timeZone).map { it.image.path }
        )
        assertEquals(
            listOf("base.png"),
            NovelAiHistoryFilterPolicy.filter(entries, "MOONLIT", null, timeZone).map { it.image.path }
        )
        assertEquals(
            listOf("role.png"),
            NovelAiHistoryFilterPolicy.filter(entries, "red-haired", null, timeZone).map { it.image.path }
        )
        assertEquals(
            emptyList<String>(),
            NovelAiHistoryFilterPolicy.filter(entries, "watermark", null, timeZone).map { it.image.path }
        )
    }

    @Test
    fun dayMonthAndYearFiltersUseRequestedTimeZone() {
        val entries = listOf(
            entry("dec", timestamp(2025, 12, 31), listOf("dec.png")),
            entry("jan1", timestamp(2026, 1, 1), listOf("jan1.png")),
            entry("jan2", timestamp(2026, 1, 2), listOf("jan2.png"))
        )

        assertEquals(
            listOf("jan1.png"),
            filter(entries, NovelAiHistoryDateFilter(NovelAiHistoryDateGranularity.DAY, 2026, 1, 1))
        )
        assertEquals(
            listOf("jan2.png", "jan1.png"),
            filter(entries, NovelAiHistoryDateFilter(NovelAiHistoryDateGranularity.MONTH, 2026, 1))
        )
        assertEquals(
            listOf("jan2.png", "jan1.png"),
            filter(entries, NovelAiHistoryDateFilter(NovelAiHistoryDateGranularity.YEAR, 2026))
        )
    }

    @Test
    fun dateAndPromptFiltersIntersect() {
        val entries = listOf(
            entry("match", timestamp(2026, 1, 1), listOf("match.png"), base = "blue sky"),
            entry("wrongPrompt", timestamp(2026, 1, 2), listOf("wrong-prompt.png"), base = "red sky"),
            entry("wrongDate", timestamp(2025, 1, 1), listOf("wrong-date.png"), base = "blue sky")
        )

        val result = NovelAiHistoryFilterPolicy.filter(
            entries,
            "blue",
            NovelAiHistoryDateFilter(NovelAiHistoryDateGranularity.YEAR, 2026),
            timeZone
        )

        assertEquals(listOf("match.png"), result.map { it.image.path })
    }

    private fun filter(
        entries: List<NovelAiGenerationHistoryEntry>,
        dateFilter: NovelAiHistoryDateFilter
    ): List<String> = NovelAiHistoryFilterPolicy.filter(entries, "", dateFilter, timeZone).map { it.image.path }

    private fun entry(
        id: String,
        createdAt: Long,
        paths: List<String>,
        style: String = "",
        base: String = "",
        character: String = "",
        negative: String = ""
    ) = NovelAiGenerationHistoryEntry(
        id = id,
        images = paths.mapIndexed { index, path -> NovelAiGenerationHistoryImage(path, index.toLong()) },
        recipe = NovelAiGenerationRecipe(
            stylePrompt = style,
            basePrompt = base,
            characters = listOf(NovelAiCharacterPromptDraft(prompt = character)),
            negativePrompt = negative
        ),
        createdAt = createdAt
    )

    private fun timestamp(year: Int, month: Int, day: Int): Long = Calendar.getInstance(timeZone).run {
        clear()
        set(year, month - 1, day, 12, 0, 0)
        timeInMillis
    }
}
