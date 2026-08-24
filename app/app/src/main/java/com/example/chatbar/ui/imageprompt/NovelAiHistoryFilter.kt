package com.example.chatbar.ui.imageprompt

import com.example.chatbar.domain.image.NovelAiGenerationHistoryEntry
import com.example.chatbar.domain.image.NovelAiGenerationHistoryImage
import java.util.Calendar
import java.util.TimeZone

enum class NovelAiHistoryDateGranularity { DAY, MONTH, YEAR }

data class NovelAiHistoryDateFilter(
    val granularity: NovelAiHistoryDateGranularity,
    val year: Int,
    val month: Int? = null,
    val day: Int? = null
)

data class NovelAiHistoryImageItem(
    val entry: NovelAiGenerationHistoryEntry,
    val image: NovelAiGenerationHistoryImage,
    val batchImageIndex: Int
) {
    val key: String get() = "${entry.id}\u0000${image.path}"
}

object NovelAiHistoryFilterPolicy {
    fun filter(
        entries: List<NovelAiGenerationHistoryEntry>,
        searchQuery: String,
        dateFilter: NovelAiHistoryDateFilter?,
        timeZone: TimeZone = TimeZone.getDefault()
    ): List<NovelAiHistoryImageItem> {
        val query = searchQuery.trim()
        return entries
            .sortedByDescending(NovelAiGenerationHistoryEntry::createdAt)
            .asSequence()
            .filter { entry -> query.isEmpty() || entry.matchesPositivePrompt(query) }
            .filter { entry -> dateFilter == null || dateFilter.matches(entry.createdAt, timeZone) }
            .flatMap { entry ->
                entry.images.asSequence().mapIndexed { index, image ->
                    NovelAiHistoryImageItem(entry, image, index)
                }
            }
            .toList()
    }

    fun dateParts(
        timestamp: Long,
        timeZone: TimeZone = TimeZone.getDefault()
    ): Triple<Int, Int, Int> = Calendar.getInstance(timeZone).run {
        timeInMillis = timestamp
        Triple(
            get(Calendar.YEAR),
            get(Calendar.MONTH) + 1,
            get(Calendar.DAY_OF_MONTH)
        )
    }

    private fun NovelAiGenerationHistoryEntry.matchesPositivePrompt(query: String): Boolean =
        sequenceOf(recipe.stylePrompt, recipe.basePrompt)
            .plus(recipe.characters.asSequence().map { it.prompt })
            .any { prompt -> prompt.contains(query, ignoreCase = true) }

    private fun NovelAiHistoryDateFilter.matches(timestamp: Long, timeZone: TimeZone): Boolean {
        val (actualYear, actualMonth, actualDay) = dateParts(timestamp, timeZone)
        return actualYear == year && when (granularity) {
            NovelAiHistoryDateGranularity.YEAR -> true
            NovelAiHistoryDateGranularity.MONTH -> actualMonth == month
            NovelAiHistoryDateGranularity.DAY -> actualMonth == month && actualDay == day
        }
    }
}

fun NovelAiHistoryDateFilter.displayLabel(): String = when (granularity) {
    NovelAiHistoryDateGranularity.YEAR -> "${year}年"
    NovelAiHistoryDateGranularity.MONTH -> "%04d-%02d".format(year, month ?: 1)
    NovelAiHistoryDateGranularity.DAY -> "%04d-%02d-%02d".format(year, month ?: 1, day ?: 1)
}
