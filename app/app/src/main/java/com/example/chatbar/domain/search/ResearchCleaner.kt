package com.example.chatbar.domain.search

import java.net.URI

object ResearchCleaner {
    private val blockedInstructionPatterns = listOf(
        Regex("(?i)ignore\\s+(all\\s+)?previous\\s+instructions?"),
        Regex("(?i)system\\s+prompt"),
        Regex("(?i)developer\\s+message"),
        Regex("(?i)you\\s+are\\s+(chatgpt|an\\s+ai|a\\s+language\\s+model)"),
        Regex("(?i)do\\s+not\\s+follow"),
        Regex("(?i)follow\\s+these\\s+instructions")
    )

    fun toResearchSources(
        hits: List<SearchHit>,
        extracts: List<SearchExtract>,
        maxSources: Int = 8,
        maxExcerptChars: Int = 900
    ): List<ResearchSource> {
        val extractsByUrl = extracts.associateBy { canonicalUrl(it.url) }
        return hits
            .filter { it.url.isNotBlank() }
            .groupBy { canonicalUrl(it.url) }
            .mapNotNull { (canonical, grouped) ->
                val best = grouped.maxByOrNull { sourceQualityScore(it.url, it.score) } ?: return@mapNotNull null
                val raw = extractsByUrl[canonical]?.rawContent
                    ?: best.rawContent
                    ?: best.content
                val excerpt = sanitizeText(raw).take(maxExcerptChars).trim()
                if (excerpt.isBlank()) return@mapNotNull null
                ResearchSource(
                    sourceId = "",
                    title = sanitizeText(best.title).take(160),
                    url = canonical.ifBlank { best.url },
                    sourceType = sourceType(best.url),
                    query = best.query,
                    excerpt = excerpt,
                    score = best.score
                )
            }
            .sortedWith(
                compareByDescending<ResearchSource> { sourceTypeWeight(it.sourceType) }
                    .thenByDescending { it.score }
                    .thenBy { it.url.length }
            )
            .take(maxSources)
            .mapIndexed { index, source -> source.copy(sourceId = "S${index + 1}") }
    }

    fun fallbackBrief(
        reason: String,
        queries: List<String>,
        sources: List<ResearchSource>
    ): ResearchBrief? {
        if (sources.isEmpty()) return null
        return ResearchBrief(
            reason = reason,
            queries = queries,
            facts = sources.take(5).map { "[${it.sourceId}] ${it.excerpt.take(260)}" },
            notes = listOf("AI资料整理不可用，以下为未压缩的清洗正文开头。"),
            sources = sources
        )
    }

    fun sanitizeText(text: String): String {
        if (text.isBlank()) return ""
        return text
            .replace(Regex("<script[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<style[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<[^>]+>"), " ")
            .lineSequence()
            .map { line ->
                val normalized = line.replace(Regex("\\s+"), " ").trim()
                if (blockedInstructionPatterns.any { it.containsMatchIn(normalized) }) {
                    "[instruction-like text removed]"
                } else {
                    normalized
                }
            }
            .filter(String::isNotBlank)
            .joinToString("\n")
            .replace(Regex("\\n{3,}"), "\n\n")
            .trim()
    }

    fun canonicalUrl(url: String): String {
        return runCatching {
            val uri = URI(url.trim())
            val scheme = uri.scheme?.lowercase() ?: "https"
            val host = uri.host?.lowercase() ?: return@runCatching url.trim()
            val path = uri.rawPath.orEmpty().trimEnd('/')
            val query = uri.rawQuery
                ?.split('&')
                ?.filterNot { param ->
                    val key = param.substringBefore('=').lowercase()
                    key.startsWith("utm_") || key in trackingParams
                }
                ?.joinToString("&")
                ?.takeIf(String::isNotBlank)
            buildString {
                append(scheme).append("://").append(host)
                if (path.isNotBlank()) append(path)
                if (!query.isNullOrBlank()) append('?').append(query)
            }
        }.getOrElse {
            url.trim().substringBefore('#').trimEnd('/')
        }
    }

    fun sourceType(url: String): String {
        val host = runCatching { URI(url).host?.lowercase().orEmpty() }.getOrDefault(url.lowercase())
        return when {
            host.endsWith(".gov") || host.contains(".gov.") -> "government"
            host.endsWith(".edu") || host.contains(".edu.") -> "academic"
            host.contains("moegirl.org.cn") -> "moegirlpedia"
            host.contains("wikipedia.org") -> "wikipedia"
            host.contains("baike.") -> "encyclopedia"
            host.contains("fandom.com") || host.contains("wiki") -> "fan-wiki"
            host.contains("official") || host.contains("fandom") -> "official-or-fan"
            host.contains("docs.") || host.contains("developer.") -> "documentation"
            host.contains("news") || host.contains("times") || host.contains("nikkei") -> "news"
            host.contains("reddit") || host.contains("nga.") || host.contains("tieba") -> "community"
            else -> "web"
        }
    }

    private fun sourceQualityScore(url: String, score: Double): Double =
        sourceTypeWeight(sourceType(url)) + score.coerceAtLeast(0.0)

    private fun sourceTypeWeight(type: String): Int = when (type) {
        "government" -> 90
        "academic" -> 88
        "documentation" -> 86
        "moegirlpedia" -> 82
        "wikipedia" -> 78
        "encyclopedia" -> 78
        "official-or-fan" -> 72
        "news" -> 64
        "fan-wiki" -> 58
        "community" -> 42
        else -> 50
    }

    private val trackingParams = setOf(
        "fbclid",
        "gclid",
        "igshid",
        "mc_cid",
        "mc_eid",
        "spm",
        "ref",
        "source"
    )
}
