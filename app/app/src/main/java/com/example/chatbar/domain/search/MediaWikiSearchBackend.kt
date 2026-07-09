package com.example.chatbar.domain.search

import com.example.chatbar.domain.ProxyAwareClient
import java.net.URI
import java.net.URLDecoder
import java.net.URLEncoder
import java.util.Locale
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.Request

class MediaWikiSearchBackend : SearchBackend {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
    private val client = ProxyAwareClient.builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(25, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()

    private val moegirlSites = listOf(
        MediaWikiSite(
            id = "moegirl-zh",
            language = "zh",
            host = "zh.moegirl.org.cn",
            label = "中文萌娘百科",
            apiPath = "/api.php",
            articlePathPrefix = "/",
            priority = 200.0
        ),
        MediaWikiSite(
            id = "moegirl-en",
            language = "en",
            host = "en.moegirl.org.cn",
            label = "English Moegirlpedia",
            apiPath = "/api.php",
            articlePathPrefix = "/",
            priority = 198.0
        ),
        MediaWikiSite(
            id = "moegirl-ja",
            language = "ja",
            host = "ja.moegirl.org.cn",
            label = "日本語萌娘百科",
            apiPath = "/api.php",
            articlePathPrefix = "/",
            priority = 196.0
        )
    )
    private val wikipediaSites = listOf(
        MediaWikiSite(
            id = "wikipedia-zh",
            language = "zh",
            host = "zh.wikipedia.org",
            label = "中文 Wikipedia",
            apiPath = "/w/api.php",
            articlePathPrefix = "/wiki/",
            priority = 100.0
        ),
        MediaWikiSite(
            id = "wikipedia-en",
            language = "en",
            host = "en.wikipedia.org",
            label = "English Wikipedia",
            apiPath = "/w/api.php",
            articlePathPrefix = "/wiki/",
            priority = 98.0
        ),
        MediaWikiSite(
            id = "wikipedia-ja",
            language = "ja",
            host = "ja.wikipedia.org",
            label = "日本語 Wikipedia",
            apiPath = "/w/api.php",
            articlePathPrefix = "/wiki/",
            priority = 96.0
        )
    )
    private val allSites = moegirlSites + wikipediaSites

    override suspend fun search(query: SearchBackendQuery): List<SearchHit> = withContext(Dispatchers.IO) {
        val errors = mutableListOf<String>()
        val siteGroups = listOf(
            sitesForQuery(query.query, moegirlSites),
            sitesForQuery(query.query, wikipediaSites)
        )

        for (sites in siteGroups) {
            val hits = sites.flatMapIndexed { siteIndex, site ->
                runCatching {
                    searchSite(site, siteIndex, query)
                }.getOrElse { error ->
                    errors += "${site.id}:${error.message ?: error::class.java.simpleName}"
                    emptyList()
                }
            }
            val cleaned = hits
                .distinctBy { ResearchCleaner.canonicalUrl(it.url) }
                .sortedByDescending { it.score }
                .take(query.maxResults)
            if (cleaned.isNotEmpty()) {
                return@withContext cleaned
            }
        }

        if (errors.isNotEmpty()) {
            error("MediaWiki request failed: ${errors.joinToString("; ").take(240)}")
        }
        emptyList()
    }

    override suspend fun extract(urls: List<String>, maxPages: Int): List<SearchExtract> = withContext(Dispatchers.IO) {
        val refs = urls.mapNotNull(::parseMediaWikiUrl)
            .distinctBy { "${it.site.id}:${normalizeTitle(it.title)}" }
            .take(maxPages.coerceAtLeast(0))
        if (refs.isEmpty()) return@withContext emptyList()

        val errors = mutableListOf<String>()
        val groups = refs.groupBy { it.site }
        val extracts = groups.flatMap { (site, pages) ->
            runCatching {
                extractSite(site, pages)
            }.getOrElse { error ->
                errors += "${site.id}:${error.message ?: error::class.java.simpleName}"
                emptyList()
            }
        }
        if (extracts.isEmpty() && errors.size == groups.size) {
            error("MediaWiki extract failed: ${errors.joinToString("; ").take(240)}")
        }
        extracts
    }

    private fun searchSite(
        site: MediaWikiSite,
        siteIndex: Int,
        query: SearchBackendQuery
    ): List<SearchHit> {
        val root = getJson(
            apiUrl(
                site,
                mapOf(
                    "action" to "query",
                    "generator" to "search",
                    "gsrsearch" to query.query,
                    "gsrlimit" to query.maxResults.coerceIn(1, MAX_RESULTS_PER_QUERY).toString(),
                    "gsrnamespace" to "0",
                    "prop" to "extracts|info",
                    "exintro" to "1",
                    "explaintext" to "1",
                    "exchars" to "1200",
                    "inprop" to "url",
                    "redirects" to "1",
                    "format" to "json",
                    "formatversion" to "2",
                    "utf8" to "1"
                )
            )
        )
        val pages = root["query"]?.jsonObject?.get("pages")?.jsonArray.orEmpty()
        val baseScore = site.priority - siteIndex
        return pages.mapNotNull { element ->
            val page = element.jsonObject
            val title = page.string("title")
            if (title.isBlank()) return@mapNotNull null
            val url = page.string("fullurl").ifBlank { articleUrl(site, title) }
            val extract = page.string("extract")
            val index = page["index"]?.jsonPrimitive?.intOrNull ?: 999
            SearchHit(
                title = "$title (${site.label})",
                url = url,
                content = extract,
                rawContent = extract.takeIf(String::isNotBlank),
                score = baseScore - (index * 0.01),
                query = query.query
            )
        }
    }

    private fun extractSite(site: MediaWikiSite, pages: List<MediaWikiPageRef>): List<SearchExtract> {
        val root = getJson(
            apiUrl(
                site,
                mapOf(
                    "action" to "query",
                    "titles" to pages.joinToString("|") { it.title },
                    "prop" to "extracts|info",
                    "explaintext" to "1",
                    "exsectionformat" to "plain",
                    "inprop" to "url",
                    "redirects" to "1",
                    "format" to "json",
                    "formatversion" to "2",
                    "utf8" to "1"
                )
            )
        )
        val apiExtracts = root["query"]?.jsonObject?.get("pages")?.jsonArray.orEmpty().mapNotNull { element ->
            val page = element.jsonObject
            val title = page.string("title")
            val raw = page.string("extract")
            if (title.isBlank() || raw.isBlank()) null else normalizeTitle(title) to raw
        }.toMap()

        return pages.mapNotNull { ref ->
            val apiRaw = apiExtractFor(ref, apiExtracts)
            val htmlRaw = if (shouldFetchArticleHtml(site, apiRaw)) {
                runCatching { fetchArticleText(ref.url.ifBlank { articleUrl(site, ref.title) }) }
                    .getOrDefault("")
            } else {
                ""
            }
            val raw = listOf(apiRaw, htmlRaw)
                .map(ResearchCleaner::sanitizeText)
                .filter(String::isNotBlank)
                .maxByOrNull { it.length }
                .orEmpty()
            if (raw.isBlank()) return@mapNotNull null
            SearchExtract(
                url = ref.url,
                rawContent = raw.take(MAX_EXTRACT_CHARS)
            )
        }
    }

    private fun getJson(url: String): JsonObject {
        return json.parseToJsonElement(getString(url, "application/json")).jsonObject
    }

    private fun getString(url: String, accept: String): String {
        val request = Request.Builder()
            .url(url)
            .header("Accept", accept)
            .header("User-Agent", USER_AGENT)
            .get()
            .build()
        client.newCall(request).execute().use { response ->
            val responseBody = response.body?.string().orEmpty()
            if (!response.isSuccessful) {
                error("HTTP ${response.code}: ${responseBody.take(400)}")
            }
            return responseBody
        }
    }

    private fun apiExtractFor(ref: MediaWikiPageRef, extracts: Map<String, String>): String {
        val normalized = normalizeTitle(ref.title)
        return extracts[normalized]
            ?: extracts.entries.firstOrNull { (title, _) ->
                title.contains(normalized) || normalized.contains(title)
            }?.value
            ?: ""
    }

    private fun shouldFetchArticleHtml(site: MediaWikiSite, apiRaw: String): Boolean =
        site.id.startsWith("moegirl") || apiRaw.length < MIN_DEEP_EXTRACT_CHARS

    private fun fetchArticleText(url: String): String =
        htmlToArticleText(getString(url, "text/html"))

    private fun htmlToArticleText(html: String): String {
        var body = articleBodyHtml(html)
        body = body
            .replace(Regex("<script\\b[\\s\\S]*?</script>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<style\\b[\\s\\S]*?</style>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<noscript\\b[\\s\\S]*?</noscript>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<svg\\b[\\s\\S]*?</svg>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<span[^>]*mw-editsection[^>]*>[\\s\\S]*?</span>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<sup[^>]*reference[^>]*>[\\s\\S]*?</sup>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<div[^>]*id=[\"']toc[\"'][\\s\\S]*?</div>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<br\\s*/?>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</(p|div|section|article|li|tr|table|h[1-6])\\s*>", RegexOption.IGNORE_CASE), "\n")
            .replace(Regex("</t[dh]\\s*>", RegexOption.IGNORE_CASE), " ")
            .replace(Regex("<[^>]+>"), " ")

        val lines = decodeHtmlEntities(body)
            .lineSequence()
            .map { it.replace(Regex("\\s+"), " ").trim() }
            .filter { line ->
                line.length >= 2 &&
                    !line.equals("编辑", ignoreCase = true) &&
                    !line.startsWith("参见") &&
                    !line.startsWith("参考资料")
            }
            .fold(mutableListOf<String>()) { acc, line ->
                if (acc.lastOrNull() != line) acc += line
                acc
            }
        return ResearchCleaner.sanitizeText(lines.joinToString("\n"))
    }

    private fun articleBodyHtml(html: String): String {
        val startMarkers = listOf(
            "<div id=\"mw-content-text\"",
            "<main",
            "<article"
        )
        val start = startMarkers
            .map { html.indexOf(it, ignoreCase = true) }
            .filter { it >= 0 }
            .minOrNull()
            ?: 0
        val body = html.substring(start)
        val endMarkers = listOf(
            "<div class=\"printfooter\"",
            "<div id=\"catlinks\"",
            "<footer",
            "</main>",
            "</article>"
        )
        val end = endMarkers
            .map { body.indexOf(it, ignoreCase = true) }
            .filter { it > 0 }
            .minOrNull()
        return if (end == null) body else body.take(end)
    }

    private fun decodeHtmlEntities(text: String): String {
        val named = text
            .replace("&nbsp;", " ")
            .replace("&amp;", "&")
            .replace("&lt;", "<")
            .replace("&gt;", ">")
            .replace("&quot;", "\"")
            .replace("&#39;", "'")
            .replace("&apos;", "'")
            .replace("&mdash;", "-")
            .replace("&ndash;", "-")
        return Regex("&#(x?[0-9a-fA-F]+);").replace(named) { match ->
            val token = match.groupValues[1]
            val codePoint = if (token.startsWith("x", ignoreCase = true)) {
                token.drop(1).toIntOrNull(16)
            } else {
                token.toIntOrNull()
            }
            codePoint
                ?.takeIf { it in 0..0x10FFFF }
                ?.let { String(Character.toChars(it)) }
                ?: match.value
        }
    }

    private fun apiUrl(site: MediaWikiSite, params: Map<String, String>): String =
        "https://${site.host}${site.apiPath}?" + params.entries.joinToString("&") { (key, value) ->
            "${encode(key)}=${encode(value)}"
        }

    private fun articleUrl(site: MediaWikiSite, title: String): String =
        "https://${site.host}${site.articlePathPrefix}${encodePath(title.replace(' ', '_'))}"

    private fun parseMediaWikiUrl(rawUrl: String): MediaWikiPageRef? = runCatching {
        val trimmed = rawUrl.trim()
        val uri = URI(trimmed)
        val host = uri.host?.lowercase(Locale.ROOT).orEmpty()
        val site = allSites.firstOrNull { it.host == host } ?: return@runCatching null
        val title = titleFromQuery(uri.rawQuery) ?: titleFromPath(uri.rawPath.orEmpty())
        if (title.isNullOrBlank()) null else MediaWikiPageRef(site, title, trimmed)
    }.getOrNull()

    private fun titleFromQuery(rawQuery: String?): String? {
        if (rawQuery.isNullOrBlank()) return null
        return rawQuery.split('&').firstNotNullOfOrNull { param ->
            val key = param.substringBefore('=')
            if (key != "title") {
                null
            } else {
                URLDecoder.decode(param.substringAfter('=', ""), UTF8)
                    .replace('_', ' ')
                    .trim()
                    .takeIf(String::isNotBlank)
            }
        }
    }

    private fun titleFromPath(rawPath: String): String? {
        var title = URLDecoder.decode(rawPath, UTF8).trim('/')
        if (title.isBlank() || title == "api.php" || title == "index.php") return null
        val prefixes = listOf(
            "wiki/",
            "zh-hans/",
            "zh-hant/",
            "zh-cn/",
            "zh-tw/",
            "zh-hk/",
            "zh-mo/",
            "en/",
            "ja/"
        )
        prefixes.firstOrNull { title.startsWith(it, ignoreCase = true) }?.let { prefix ->
            title = title.removePrefix(prefix)
        }
        return title
            .replace('_', ' ')
            .trim()
            .takeIf(String::isNotBlank)
    }

    private fun sitesForQuery(query: String, candidates: List<MediaWikiSite>): List<MediaWikiSite> {
        val order = languageOrderFor(query)
        return candidates.sortedWith(
            compareBy<MediaWikiSite> { site ->
                order.indexOf(site.language).takeIf { it >= 0 } ?: Int.MAX_VALUE
            }.thenByDescending { it.priority }
        )
    }

    private fun languageOrderFor(query: String): List<String> {
        val hasKana = query.any { it in '\u3040'..'\u30ff' }
        val hasCjk = query.any { it in '\u4e00'..'\u9fff' }
        return when {
            hasKana -> listOf("ja", "zh", "en")
            hasCjk -> listOf("zh", "en", "ja")
            else -> listOf("en", "zh", "ja")
        }
    }

    private fun normalizeTitle(title: String): String =
        title.replace('_', ' ').trim().lowercase(Locale.ROOT)

    private fun encode(value: String): String =
        URLEncoder.encode(value, UTF8)

    private fun encodePath(value: String): String =
        value.split('/').joinToString("/") { encode(it).replace("+", "%20") }

    private fun JsonObject.string(key: String): String =
        get(key)?.jsonPrimitive?.contentOrNull.orEmpty().trim()

    private data class MediaWikiSite(
        val id: String,
        val language: String,
        val host: String,
        val label: String,
        val apiPath: String,
        val articlePathPrefix: String,
        val priority: Double
    )

    private data class MediaWikiPageRef(
        val site: MediaWikiSite,
        val title: String,
        val url: String
    )

    private companion object {
        const val UTF8 = "UTF-8"
        const val USER_AGENT = "ChatBar/1.0 (Android; character-card research)"
        const val MAX_RESULTS_PER_QUERY = 1
        const val MIN_DEEP_EXTRACT_CHARS = 1600
        const val MAX_EXTRACT_CHARS = 12_000
    }
}
