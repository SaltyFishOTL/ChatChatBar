package com.example.chatbar.domain.image

import com.example.chatbar.data.repository.NovelAiPromptTranslationCacheRepository
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit

enum class NovelAiPromptTranslationSegmentKind {
    TAG,
    NATURAL_LANGUAGE
}

data class NovelAiPromptTranslationSegment(
    val start: Int,
    val end: Int,
    val source: String,
    val lookupText: String,
    val kind: NovelAiPromptTranslationSegmentKind
) {
    val cacheKey: String
        get() = when (kind) {
            NovelAiPromptTranslationSegmentKind.TAG -> {
                "tag:${lookupText.normalizedTagQuery().lowercase(Locale.ROOT)}"
            }
            NovelAiPromptTranslationSegmentKind.NATURAL_LANGUAGE -> {
                "text:${lookupText.normalizedTranslationText().lowercase(Locale.ROOT)}"
            }
        }
}

data class NovelAiPromptAnnotation(
    val start: Int,
    val end: Int,
    val source: String,
    val translation: String
)

data class NovelAiPromptTranslationResult(
    val annotations: List<NovelAiPromptAnnotation>,
    val translations: Map<String, String> = emptyMap(),
    val warning: String? = null
)

object NovelAiPromptTranslationParser {
    fun parse(text: String, naturalLanguage: Boolean): List<NovelAiPromptTranslationSegment> {
        if (text.isBlank()) return emptyList()
        return if (naturalLanguage) parseNaturalLanguage(text) else parseTags(text)
    }

    fun activeSegment(
        text: String,
        cursor: Int,
        naturalLanguage: Boolean
    ): NovelAiPromptTranslationSegment? {
        val segments = parse(text, naturalLanguage)
        if (segments.isEmpty()) return null
        val safeCursor = cursor.coerceIn(0, text.length)
        val candidate = segments.firstOrNull { segment -> safeCursor <= segment.end }
            ?: return null
        val gapEnd = candidate.start.coerceAtLeast(safeCursor)
        val crossesDelimiter = text.substring(safeCursor, gapEnd).any { char ->
            char == ',' || char == '，' || char == '\n'
        }
        return candidate.takeUnless { crossesDelimiter }
    }

    private fun parseNaturalLanguage(text: String): List<NovelAiPromptTranslationSegment> {
        val result = mutableListOf<NovelAiPromptTranslationSegment>()
        var start = 0
        for (index in 0..text.length) {
            if (index == text.length || text[index] == '\n') {
                parseSegment(text, start, index, NovelAiPromptTranslationSegmentKind.NATURAL_LANGUAGE)
                    ?.let(result::add)
                start = index + 1
            }
        }
        return result
    }

    private fun parseTags(text: String): List<NovelAiPromptTranslationSegment> {
        val result = mutableListOf<NovelAiPromptTranslationSegment>()
        var start = 0
        var quoteEnd: Char? = null
        var textBlock = startsWithTextPrefix(text, start)
        var index = 0
        while (index <= text.length) {
            if (index == text.length) {
                parseSegment(text, start, index, segmentKind(textBlock))?.let(result::add)
                break
            }
            val char = text[index]
            if (quoteEnd != null) {
                if (char == quoteEnd) quoteEnd = null
            } else {
                quoteEnd = when (char) {
                    '"' -> '"'
                    '“' -> '”'
                    else -> null
                }
                val delimiter = char == '\n' || (!textBlock && (char == ',' || char == '，'))
                if (delimiter) {
                    parseSegment(text, start, index, segmentKind(textBlock))?.let(result::add)
                    start = index + 1
                    textBlock = startsWithTextPrefix(text, start)
                }
            }
            index++
        }
        return result
    }

    private fun segmentKind(textBlock: Boolean): NovelAiPromptTranslationSegmentKind =
        if (textBlock) NovelAiPromptTranslationSegmentKind.NATURAL_LANGUAGE
        else NovelAiPromptTranslationSegmentKind.TAG

    private fun startsWithTextPrefix(text: String, start: Int): Boolean {
        var index = start.coerceAtLeast(0)
        while (index < text.length && (text[index].isWhitespace() || text[index] in OPENING_SYNTAX)) index++
        return text.regionMatches(index, "Text:", 0, 5, ignoreCase = true)
    }

    private fun parseSegment(
        text: String,
        rawStart: Int,
        rawEnd: Int,
        kind: NovelAiPromptTranslationSegmentKind
    ): NovelAiPromptTranslationSegment? {
        var start = rawStart.coerceIn(0, text.length)
        var end = rawEnd.coerceIn(start, text.length)
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        if (start >= end) return null

        var textBlock = false
        var textPrefixStart = start
        while (textPrefixStart < end && text[textPrefixStart] in OPENING_SYNTAX) textPrefixStart++
        if (text.regionMatches(textPrefixStart, "Text:", 0, 5, ignoreCase = true)) {
            start = textPrefixStart + 5
            textBlock = true
            while (start < end && text[start].isWhitespace()) start++
        }
        val annotationEnd = end
        var lookupStart = start
        var lookupEnd = end
        if (kind == NovelAiPromptTranslationSegmentKind.TAG) {
            while (lookupStart < lookupEnd && text[lookupStart] in OPENING_SYNTAX) lookupStart++
            while (lookupEnd > lookupStart && text[lookupEnd - 1] in CLOSING_SYNTAX) lookupEnd--
            while (true) {
                val prefix = WEIGHT_PREFIX.find(text.substring(lookupStart, lookupEnd)) ?: break
                lookupStart += prefix.value.length
            }
            while (lookupEnd - 2 >= lookupStart && text.regionMatches(lookupEnd - 2, "::", 0, 2)) {
                lookupEnd -= 2
            }
        } else if (textBlock) {
            while (lookupEnd > lookupStart && text[lookupEnd - 1] in CLOSING_SYNTAX) lookupEnd--
        }
        while (lookupStart < lookupEnd && text[lookupStart].isWhitespace()) lookupStart++
        while (lookupEnd > lookupStart && text[lookupEnd - 1].isWhitespace()) lookupEnd--
        if (lookupStart >= lookupEnd) return null

        var lookup = text.substring(lookupStart, lookupEnd).trim()
        var quotedText = false
        if ((lookup.startsWith('"') && lookup.endsWith('"')) ||
            (lookup.startsWith('“') && lookup.endsWith('”'))
        ) {
            lookup = lookup.substring(1, lookup.length - 1).trim()
            quotedText = true
        }
        if (!lookup.isEnglishOnly()) return null
        val effectiveKind = if (
            kind == NovelAiPromptTranslationSegmentKind.NATURAL_LANGUAGE ||
            quotedText ||
            lookup.looksLikeNaturalLanguageSentence()
        ) NovelAiPromptTranslationSegmentKind.NATURAL_LANGUAGE
        else NovelAiPromptTranslationSegmentKind.TAG
        return NovelAiPromptTranslationSegment(
            start = lookupStart,
            end = annotationEnd,
            source = text.substring(lookupStart, annotationEnd),
            lookupText = lookup,
            kind = effectiveKind
        )
    }

    private fun String.isEnglishOnly(): Boolean {
        var hasAsciiLetter = false
        for (char in this) {
            if (char.isLetter()) {
                if (char !in 'A'..'Z' && char !in 'a'..'z') return false
                hasAsciiLetter = true
            }
        }
        return hasAsciiLetter
    }

    private fun String.looksLikeNaturalLanguageSentence(): Boolean {
        if (any { it == '.' || it == '!' || it == '?' || it == '。' || it == '！' || it == '？' }) {
            return true
        }
        return ENGLISH_WORD.findAll(this).take(5).count() >= 5
    }

    private val WEIGHT_PREFIX = Regex("^[+-]?(?:\\d+(?:\\.\\d+)?)?::")
    private val ENGLISH_WORD = Regex("[A-Za-z]+(?:'[A-Za-z]+)?")
    private val OPENING_SYNTAX = setOf('{', '[', '(')
    private val CLOSING_SYNTAX = setOf('}', ']', ')')
}

object NovelAiPromptWrapPolicy {
    data class Plan(
        val nonBreakingSpaceOffsets: IntArray,
        val breakableCommaOffsets: IntArray
    )

    fun plan(text: String): Plan {
        if (text.isEmpty()) return Plan(IntArray(0), IntArray(0))
        val spaceOffsets = mutableListOf<Int>()
        val commaOffsets = mutableListOf<Int>()
        var segmentStart = 0
        var quoteEnd: Char? = null
        var textBlock = startsWithTextPrefix(text, segmentStart)
        var index = 0
        while (index <= text.length) {
            if (index == text.length) {
                collectTagSpaces(text, segmentStart, index, textBlock, spaceOffsets)
                break
            }
            val char = text[index]
            if (quoteEnd != null) {
                if (char == quoteEnd) quoteEnd = null
            } else {
                quoteEnd = when (char) {
                    '"' -> '"'
                    '“' -> '”'
                    else -> null
                }
                val delimiter = char == '\n' || (!textBlock && (char == ',' || char == '，'))
                if (delimiter) {
                    collectTagSpaces(text, segmentStart, index, textBlock, spaceOffsets)
                    if (char == ',') commaOffsets += index
                    segmentStart = index + 1
                    textBlock = startsWithTextPrefix(text, segmentStart)
                }
            }
            index++
        }
        return Plan(spaceOffsets.toIntArray(), commaOffsets.toIntArray())
    }

    fun nonBreakingSpaceOffsets(text: String): IntArray = plan(text).nonBreakingSpaceOffsets

    private fun collectTagSpaces(
        text: String,
        rawStart: Int,
        rawEnd: Int,
        textBlock: Boolean,
        offsets: MutableList<Int>
    ) {
        if (textBlock) return
        var start = rawStart.coerceIn(0, text.length)
        var end = rawEnd.coerceIn(start, text.length)
        while (start < end && text[start].isWhitespace()) start++
        while (end > start && text[end - 1].isWhitespace()) end--
        for (index in start until end) {
            if (text[index] == ' ') offsets += index
        }
    }

    private fun startsWithTextPrefix(text: String, start: Int): Boolean {
        var index = start.coerceAtLeast(0)
        while (index < text.length && (text[index].isWhitespace() || text[index] in OPENING_SYNTAX)) index++
        return text.regionMatches(index, "Text:", 0, 5, ignoreCase = true)
    }

    private val OPENING_SYNTAX = setOf('{', '[', '(')
}

class NovelAiPromptTranslationService(
    private val cacheRepository: NovelAiPromptTranslationCacheRepository,
    private val wordDictionary: NovelAiPromptWordDictionary,
    private val tagSearchClient: NovelAiTagSearchClient
) {
    private val tagSearchSemaphore = Semaphore(4)

    suspend fun immediateTranslations(
        segments: List<NovelAiPromptTranslationSegment>
    ): Map<String, String> {
        val persisted = persistedTranslations(segments)
        return buildMap {
            putAll(persisted)
            segments.distinctBy(NovelAiPromptTranslationSegment::cacheKey).forEach { segment ->
                if (!containsKey(segment.cacheKey)) {
                    localWordTranslation(segment.lookupText)?.let { put(segment.cacheKey, it) }
                }
            }
        }
    }

    private suspend fun persistedTranslations(
        segments: List<NovelAiPromptTranslationSegment>
    ): Map<String, String> {
        val distinct = segments.distinctBy(NovelAiPromptTranslationSegment::cacheKey)
        val cached = cacheRepository.getAll(distinct.map(NovelAiPromptTranslationSegment::cacheKey))
        return distinct.mapNotNull { segment ->
            cached[segment.cacheKey]
                ?.takeIf { it.isReliableChineseTranslationOf(segment.lookupText) }
                ?.let { segment.cacheKey to it }
        }.toMap()
    }

    suspend fun resolve(segments: List<NovelAiPromptTranslationSegment>): NovelAiPromptTranslationResult {
        if (segments.isEmpty()) return NovelAiPromptTranslationResult(emptyList())
        val distinct = segments.distinctBy(NovelAiPromptTranslationSegment::cacheKey)
        val cached = persistedTranslations(distinct)
        val resolutions = supervisorScope {
            distinct
                .filterNot { cached.containsKey(it.cacheKey) }
                .map { segment ->
                    async { resolveSegment(segment) }
                }
                .awaitAll()
        }
        val tagSuggestTranslations = resolutions.mapNotNull { resolution ->
            resolution.tagSuggestTranslation?.let { resolution.segment.cacheKey to it }
        }.toMap()
        if (tagSuggestTranslations.isNotEmpty()) {
            cacheRepository.putAll(tagSuggestTranslations)
        }
        val resolved = cached + resolutions.mapNotNull { resolution ->
            resolution.translation?.let { resolution.segment.cacheKey to it }
        }.toMap()
        val warning = resolutions.firstNotNullOfOrNull(TagResolution::failureReason)?.let { reason ->
            "TagSuggest 翻译查询失败，已使用本地词典：$reason"
        }
        return NovelAiPromptTranslationResult(
            annotations = segments.mapNotNull { segment ->
                resolved[segment.cacheKey]?.let { translation ->
                    NovelAiPromptAnnotation(segment.start, segment.end, segment.source, translation)
                }
            },
            translations = resolved,
            warning = warning
        )
    }

    private suspend fun resolveSegment(segment: NovelAiPromptTranslationSegment): TagResolution {
        val local = localWordTranslation(segment.lookupText)
        if (segment.kind != NovelAiPromptTranslationSegmentKind.TAG) {
            return TagResolution(segment = segment, translation = local)
        }
        val query = segment.lookupText.normalizedTagQuery()
        if (query.length !in TAG_SUGGEST_QUERY_LENGTH) {
            return TagResolution(segment = segment, translation = local)
        }
        return runCatching {
            tagSearchSemaphore.withPermit {
                tagSearchClient.search(query).exactChineseTranslation(query)
            }
        }.fold(
            onSuccess = { tagSuggestTranslation ->
                TagResolution(
                    segment = segment,
                    translation = tagSuggestTranslation ?: local,
                    tagSuggestTranslation = tagSuggestTranslation
                )
            },
            onFailure = { error ->
                if (error is CancellationException) throw error
                TagResolution(
                    segment = segment,
                    translation = local,
                    failureReason = error.message ?: error::class.java.simpleName
                )
            }
        )
    }

    private fun localWordTranslation(source: String): String? {
        val translations = wordDictionary.tokens(source)
            .mapNotNull { token ->
                wordDictionary.localTranslation(token.normalized)
                    ?.let { token.normalized to it }
            }
            .toMap()
        if (translations.isEmpty()) return null
        return wordDictionary.compose(source, translations)
            .takeIf { it.isReliableChineseTranslationOf(source) }
    }

    private data class TagResolution(
        val segment: NovelAiPromptTranslationSegment,
        val translation: String?,
        val tagSuggestTranslation: String? = null,
        val failureReason: String? = null
    )

}

internal fun String.normalizedTagQuery(): String =
    trim().replace(Regex("\\s+"), "_")

internal fun NovelAiTagSearchOutcome.exactChineseTranslation(query: String): String? {
    val normalizedQuery = query.lowercase(Locale.ROOT)
    return candidates.firstOrNull { candidate ->
        candidate.name.lowercase(Locale.ROOT) == normalizedQuery
    }?.translatedName
        ?.trim()
        ?.takeIf { it.isReliableChineseTranslationOf(query) }
}

private fun String.normalizedTranslationText(): String =
    replace(Regex("\\s+"), " ").trim()

private fun String.isReliableChineseTranslationOf(source: String): Boolean {
    if (isBlank() || equals(source, ignoreCase = true)) return false
    return any { char ->
        char.code in 0x3400..0x9FFF || char.code in 0xF900..0xFAFF
    }
}

private val TAG_SUGGEST_QUERY_LENGTH = 2..80
