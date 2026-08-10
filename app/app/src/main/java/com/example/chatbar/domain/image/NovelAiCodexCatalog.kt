package com.example.chatbar.domain.image

import android.content.Context
import java.text.Normalizer
import java.util.Locale
import kotlin.math.ln
import kotlin.math.sqrt
import kotlin.random.Random
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

const val NOVEL_AI_CODEX_ASSET_PATH = "presets/novelai/nai-codex-v1.json"

private const val SUPPORTED_NOVEL_AI_CODEX_SCHEMA_VERSION = 1
private const val MAX_CODEX_SELECTED_REFERENCES = 5
private const val MAX_CODEX_CANDIDATES_PER_QUERY = 48

@Serializable
enum class NovelAiCodexKind {
    COMPOSITION,
    R18,
    WARDROBE,
    CORE,
    COMPONENT
}

@Serializable
enum class NovelAiTagRewriteMode {
    REPLACE,
    EXPAND,
    AMBIGUOUS
}

@Serializable
data class NovelAiCodexEntry(
    val id: String = "",
    val kind: NovelAiCodexKind = NovelAiCodexKind.COMPONENT,
    val title: String = "",
    val category: String = "",
    val prompt: String = "",
    val searchText: String = "",
    val source: String = ""
)

@Serializable
data class NovelAiTagRewriteRule(
    val aliases: List<String> = emptyList(),
    val replacements: List<String> = emptyList(),
    val mode: NovelAiTagRewriteMode = NovelAiTagRewriteMode.REPLACE
)

@Serializable
data class NovelAiCodexCatalog(
    val schemaVersion: Int = 0,
    val sourceVersion: String = "",
    val entries: List<NovelAiCodexEntry> = emptyList(),
    val rewriteRules: List<NovelAiTagRewriteRule> = emptyList()
) {
    companion object {
        val EMPTY = NovelAiCodexCatalog(schemaVersion = SUPPORTED_NOVEL_AI_CODEX_SCHEMA_VERSION)
    }
}

data class NovelAiCodexCatalogLoadResult(
    val catalog: NovelAiCodexCatalog = NovelAiCodexCatalog.EMPTY,
    val errors: List<String> = emptyList(),
    val fatalError: String? = null
)

class NovelAiCodexCatalogParser(
    private val json: Json
) {
    fun parse(rawJson: String): NovelAiCodexCatalogLoadResult {
        val source = try {
            json.decodeFromString<NovelAiCodexCatalog>(rawJson)
        } catch (error: Exception) {
            return NovelAiCodexCatalogLoadResult(
                fatalError = "NovelAI 法典读取失败：${error.message ?: error::class.simpleName.orEmpty()}"
            )
        }
        if (source.schemaVersion != SUPPORTED_NOVEL_AI_CODEX_SCHEMA_VERSION) {
            return NovelAiCodexCatalogLoadResult(
                fatalError = "不支持的 NovelAI 法典版本：${source.schemaVersion}"
            )
        }

        val errors = mutableListOf<String>()
        val seenIds = mutableSetOf<String>()
        val entries = source.entries.mapNotNull { entry ->
            val normalized = entry.copy(
                id = entry.id.trim(),
                title = entry.title.trim(),
                category = entry.category.trim(),
                prompt = entry.prompt.trim(),
                searchText = entry.searchText.trim(),
                source = entry.source.trim()
            )
            when {
                normalized.id.isBlank() || normalized.title.isBlank() || normalized.prompt.isBlank() -> {
                    errors += "法典条目缺少 id/title/prompt：${entry.id.ifBlank { "(blank)" }}"
                    null
                }
                !seenIds.add(normalized.id) -> {
                    errors += "法典条目 id 重复，保留首项：${normalized.id}"
                    null
                }
                else -> normalized
            }
        }

        val rewriteRules = source.rewriteRules.mapNotNull { rule ->
            val aliases = rule.aliases.map(String::trim).filter(String::isNotBlank).distinct()
            val replacements = rule.replacements.map(String::trim).filter(String::isNotBlank).distinct()
            if (aliases.isEmpty() || replacements.isEmpty()) {
                errors += "法典 tag 校正规则缺少 aliases/replacements"
                null
            } else {
                rule.copy(aliases = aliases, replacements = replacements)
            }
        }
        return NovelAiCodexCatalogLoadResult(
            catalog = source.copy(entries = entries, rewriteRules = rewriteRules),
            errors = errors
        )
    }
}

class NovelAiCodexCatalogService(
    private val context: Context,
    json: Json
) {
    private val parser = NovelAiCodexCatalogParser(json)

    fun load(): NovelAiCodexCatalogLoadResult {
        val raw = try {
            context.assets.open(NOVEL_AI_CODEX_ASSET_PATH).bufferedReader().use { it.readText() }
        } catch (error: Exception) {
            return NovelAiCodexCatalogLoadResult(
                fatalError = "NovelAI 法典加载失败：${error.message ?: error::class.simpleName.orEmpty()}"
            )
        }
        return parser.parse(raw)
    }
}

data class NovelAiCodexMatch(
    val entry: NovelAiCodexEntry,
    val score: Double,
    val matchedQueries: List<String>
)

data class NovelAiCodexSearchResult(
    val matches: List<NovelAiCodexMatch> = emptyList(),
    val failureReason: String = ""
)

fun interface NovelAiCodexSearcher {
    fun search(
        queries: List<String>,
        sceneDescription: String,
        diversityKey: String
    ): NovelAiCodexSearchResult
}

class NovelAiCodexSearchEngine(
    catalog: NovelAiCodexCatalog,
    private val unavailableReason: String = "",
    private val nextDouble: () -> Double = { Random.Default.nextDouble() }
) : NovelAiCodexSearcher {
    private data class IndexedEntry(
        val entry: NovelAiCodexEntry,
        val searchGrams: Set<String>
    )

    private data class ScoredEntry(
        val indexed: IndexedEntry,
        var score: Double = 0.0,
        val matchedQueries: MutableSet<String> = linkedSetOf()
    )

    private data class QuerySpec(
        val text: String,
        val weight: Double,
        val matchLabel: String
    )

    private val indexedEntries = catalog.entries.map { entry ->
        IndexedEntry(
            entry = entry,
            searchGrams = entry.searchText
                .ifBlank { "${entry.title} ${entry.category}" }
                .codexChineseGrams()
        )
    }
    private val gramIdf = buildMap {
        val documentCount = indexedEntries.size.toDouble()
        indexedEntries
            .flatMap(IndexedEntry::searchGrams)
            .groupingBy { it }
            .eachCount()
            .forEach { (gram, documentFrequency) ->
                put(
                    gram,
                    ln(1.0 + (documentCount - documentFrequency + 0.5) / (documentFrequency + 0.5))
                )
            }
    }

    override fun search(
        queries: List<String>,
        sceneDescription: String,
        @Suppress("UNUSED_PARAMETER")
        diversityKey: String
    ): NovelAiCodexSearchResult {
        if (unavailableReason.isNotBlank()) {
            return NovelAiCodexSearchResult(failureReason = unavailableReason)
        }
        if (indexedEntries.isEmpty()) return NovelAiCodexSearchResult()

        val querySpecs = buildList {
            sceneDescription.trim().takeIf(String::isNotBlank)?.let {
                add(QuerySpec(it, 0.8, "画面描述"))
            }
            queries.map(String::trim).filter(String::isNotBlank).distinct().forEach {
                add(QuerySpec(it, 1.0, it))
            }
        }
        if (querySpecs.isEmpty()) return NovelAiCodexSearchResult()

        val combined = linkedMapOf<String, ScoredEntry>()
        querySpecs.forEach { spec ->
            val ranked = indexedEntries.asSequence()
                .map { indexed -> indexed to score(spec.text, indexed) }
                .filter { (_, score) -> score > 0.0 }
                .sortedByDescending { (_, score) -> score }
                .take(MAX_CODEX_CANDIDATES_PER_QUERY)
                .toList()
            ranked.forEach { (indexed, rawScore) ->
                val scored = combined.getOrPut(indexed.entry.id) { ScoredEntry(indexed) }
                scored.score += spec.weight * rawScore
                scored.matchedQueries += spec.matchLabel
            }
        }

        val selected = combined.values
            .sortedByDescending(ScoredEntry::score)
            .take(MAX_CODEX_SELECTED_REFERENCES)
            .map { nextDouble() to it }
            .sortedBy { (randomOrder, _) -> randomOrder }
            .map { (_, candidate) -> candidate }
        return NovelAiCodexSearchResult(
            matches = selected.map { candidate ->
                NovelAiCodexMatch(
                    entry = candidate.indexed.entry,
                    score = candidate.score,
                    matchedQueries = candidate.matchedQueries.toList()
                )
            }
        )
    }

    private fun score(rawQuery: String, indexed: IndexedEntry): Double {
        val queryGrams = rawQuery.codexChineseGrams()
        if (queryGrams.isEmpty()) return 0.0
        val overlapWeight = queryGrams.asSequence()
            .filter(indexed.searchGrams::contains)
            .sumOf { gram ->
                val gramLengthWeight = if (gram.length == 3) 1.35 else 1.0
                gramIdf.getValue(gram) * gramLengthWeight
            }
        return overlapWeight / sqrt(queryGrams.size.toDouble())
    }
}

internal fun String.normalizeTagLookupKey(): String =
    Normalizer.normalize(this, Normalizer.Form.NFKC)
        .lowercase(Locale.ROOT)
        .trim()
        .replace(Regex("[\\s_]+"), " ")

private fun String.codexChineseGrams(): Set<String> {
    return buildSet {
        Regex("[\\u3400-\\u9fff]+").findAll(this@codexChineseGrams).forEach { match ->
            val run = match.value
            if (run.length == 1) return@forEach
            run.windowed(2).forEach(::add)
            if (run.length >= 3) run.windowed(3).forEach(::add)
        }
    }
}
