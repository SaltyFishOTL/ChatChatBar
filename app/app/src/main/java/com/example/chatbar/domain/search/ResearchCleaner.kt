package com.example.chatbar.domain.search

import java.net.URI

object ResearchCleaner {
    private val blockedInstructionPatterns = listOf(
        Regex("(?i)ignore\\s+(all\\s+)?previous\\s+instructions?"),
        Regex("(?i)system\\s+prompt"),
        Regex("(?i)developer\\s+message"),
        Regex("(?i)you\\s+are\\s+(chatgpt|an\\s+ai|a\\s+language\\s+model)"),
        Regex("(?i)do\\s+not\\s+follow"),
        Regex("(?i)follow\\s+these\\s+instructions"),
        Regex("(忽略|无视).{0,20}(之前|此前|以上|先前|原有|所有).{0,20}(指令|提示词|要求)"),
        Regex("(系统提示词|开发者消息|开发者指令)"),
        Regex("(输出|透露|泄露|显示).{0,12}(系统提示词|开发者消息|开发者指令)"),
        Regex("(不要|无需).{0,12}遵循.{0,20}(之前|以上|原有).{0,12}(指令|提示词)"),
        Regex("请.{0,12}(遵循|执行)以下.{0,12}(指令|提示词)"),
        Regex("(以前|これまで|上記)の.{0,12}(指示|命令).{0,12}(無視|従わ)"),
        Regex("(システムプロンプト|開発者メッセージ)"),
        Regex("次の.{0,12}(指示|命令)に従")
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
                if (
                    excerpt.isBlank() ||
                    excerpt.lineSequence().none { it != REMOVED_INSTRUCTION_MARKER }
                ) {
                    return@mapNotNull null
                }
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
                    REMOVED_INSTRUCTION_MARKER
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
                if (uri.port >= 0 && !isDefaultPort(scheme, uri.port)) {
                    append(':').append(uri.port)
                }
                if (path.isNotBlank()) append(path)
                if (!query.isNullOrBlank()) append('?').append(query)
            }
        }.getOrElse {
            url.trim().substringBefore('#').trimEnd('/')
        }
    }

    private fun isDefaultPort(scheme: String, port: Int): Boolean =
        (scheme == "http" && port == 80) || (scheme == "https" && port == 443)

    private const val REMOVED_INSTRUCTION_MARKER = "[instruction-like text removed]"

    fun sourceType(url: String): String {
        val uri = runCatching { URI(url) }.getOrNull()
        if (uri?.scheme.equals("reference-document", ignoreCase = true)) {
            return "reference-document"
        }
        val host = uri?.host?.lowercase().orEmpty().ifBlank { url.lowercase() }
        return when {
            host.endsWith(".gov") || host.contains(".gov.") -> "government"
            host.endsWith(".edu") || host.contains(".edu.") -> "academic"
            host.contains("moegirl.org.cn") -> "moegirlpedia"
            host.contains("wikipedia.org") -> "wikipedia"
            host.contains("dic.pixiv.net") -> "encyclopedia"
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
        "reference-document" -> 92
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
