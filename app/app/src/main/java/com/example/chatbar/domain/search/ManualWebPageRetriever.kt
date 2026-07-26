package com.example.chatbar.domain.search

import com.example.chatbar.domain.ProxyAwareClient
import java.io.ByteArrayInputStream
import java.net.InetAddress
import java.net.Inet4Address
import java.net.UnknownHostException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Dns
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Buffer
import org.jsoup.Jsoup
import org.jsoup.nodes.Document
import org.jsoup.nodes.Element

const val MAX_MANUAL_WEB_PAGE_EXCERPT_CHARS = 8_000

data class ManualWebPageFailure(
    val url: String,
    val reason: String
)

data class ManualWebPageRetrievalResult(
    val hits: List<SearchHit> = emptyList(),
    val failures: List<ManualWebPageFailure> = emptyList()
)

interface ManualWebPageRetriever {
    suspend fun retrieve(
        urls: List<String>,
        onStatus: (String) -> Unit = {}
    ): ManualWebPageRetrievalResult
}

class HttpManualWebPageRetriever(
    private val targetResolver: (String) -> List<InetAddress> = {
        InetAddress.getAllByName(it).sortedBy { address ->
            if (address is Inet4Address) 0 else 1
        }
    },
    private val client: OkHttpClient = buildManualWebPageClient(targetResolver),
    private val pageTimeoutMillis: Long = 25_000L,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }
) : ManualWebPageRetriever {
    override suspend fun retrieve(
        urls: List<String>,
        onStatus: (String) -> Unit
    ): ManualWebPageRetrievalResult = withContext(Dispatchers.IO) {
        coroutineScope {
            val validation = validateManualResearchUrls(urls)
            require(validation.isValid) { validation.errors.joinToString("；") }
            require(validation.urls.isNotEmpty()) { "指定网址模式至少需要一个有效网址" }

            validation.urls.forEachIndexed { index, url ->
                onStatus("准备读取指定网页 ${index + 1}/${validation.urls.size}：${url.statusSnippet()}")
            }
            val semaphore = Semaphore(MAX_CONCURRENT_REQUESTS)
            val outcomes = validation.urls.map { url ->
                async {
                    semaphore.withPermit {
                        runCatching { fetchPage(url) }
                            .fold(
                                onSuccess = { FetchOutcome.Success(it) },
                                onFailure = {
                                    FetchOutcome.Failure(
                                        ManualWebPageFailure(
                                            url = url,
                                            reason = it.message ?: it::class.java.simpleName
                                        )
                                    )
                                }
                            )
                    }
                }
            }.awaitAll()

            val hits = mutableListOf<SearchHit>()
            val failures = mutableListOf<ManualWebPageFailure>()
            outcomes.forEachIndexed { index, outcome ->
                when (outcome) {
                    is FetchOutcome.Success -> {
                        hits += outcome.hit
                        onStatus(
                            "指定网页 ${index + 1}/${validation.urls.size} 读取成功：" +
                                "${outcome.hit.title.statusSnippet(80)}，正文 ${outcome.hit.content.length} 字符"
                        )
                    }
                    is FetchOutcome.Failure -> {
                        failures += outcome.failure
                        onStatus(
                            "指定网页 ${index + 1}/${validation.urls.size} 读取失败：" +
                                outcome.failure.reason.statusSnippet(140)
                        )
                    }
                }
            }
            ManualWebPageRetrievalResult(hits = hits, failures = failures)
        }
    }

    private fun fetchPage(url: String): SearchHit {
        val deadlineNanos = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(pageTimeoutMillis)
        val originalUrl = url.toHttpUrlOrNull() ?: error("网址格式无效")
        if (originalUrl.host.endsWith(".fandom.com", ignoreCase = true)) {
            runCatching { fetchFandomPage(originalUrl, deadlineNanos) }.getOrNull()?.let { return it }
        }
        val response = fetchBody(originalUrl, ACCEPT_DOCUMENT, deadlineNanos)
        return response.toSearchHit()
    }

    private fun fetchFandomPage(originalUrl: HttpUrl, deadlineNanos: Long): SearchHit {
        val segments = originalUrl.pathSegments
        val wikiIndex = segments.indexOfFirst { it.equals("wiki", ignoreCase = true) }
        require(wikiIndex >= 0 && wikiIndex < segments.lastIndex) { "不是可识别的 Fandom 词条地址" }
        val pageTitle = segments.drop(wikiIndex + 1).joinToString("/")
        val apiBuilder = originalUrl.newBuilder()
            .encodedPath("/")
            .query(null)
            .fragment(null)
        segments.take(wikiIndex).forEach { segment -> apiBuilder.addPathSegment(segment) }
        val apiUrl = apiBuilder
            .addPathSegment("api.php")
            .addQueryParameter("action", "parse")
            .addQueryParameter("page", pageTitle)
            .addQueryParameter("prop", "text|displaytitle")
            .addQueryParameter("redirects", "1")
            .addQueryParameter("format", "json")
            .addQueryParameter("formatversion", "2")
            .build()
        val response = fetchBody(apiUrl, ACCEPT_JSON, deadlineNanos)
        val root = json.parseToJsonElement(response.decodeText()).jsonObject
        val parse = root["parse"]?.jsonObject ?: error("Fandom API 没有返回词条正文")
        val htmlElement = parse["text"] ?: error("Fandom API 正文为空")
        val html = runCatching { htmlElement.jsonPrimitive.contentOrNull }
            .getOrNull()
            ?: runCatching { htmlElement.jsonObject["*"]?.jsonPrimitive?.contentOrNull }
                .getOrNull()
            ?: error("Fandom API 正文格式无法识别")
        val displayTitle = parse["displaytitle"]?.jsonPrimitive?.contentOrNull
            ?.let { Jsoup.parse(it).text() }
            .orEmpty()
        val resolvedTitle = parse["title"]?.jsonPrimitive?.contentOrNull.orEmpty()
        val finalPageUrl = if (resolvedTitle.isBlank()) {
            originalUrl
        } else {
            originalUrl.newBuilder()
                .encodedPath("/")
                .query(null)
                .fragment(null)
                .apply {
                    segments.take(wikiIndex + 1).forEach { segment -> addPathSegment(segment) }
                    resolvedTitle.split('/').forEach { segment -> addPathSegment(segment) }
                }
                .build()
        }
        val extracted = WebPageContentExtractor.extractHtml(
            html = html,
            baseUrl = finalPageUrl.toString(),
            preferredTitle = displayTitle,
            site = WebPageSite.FANDOM
        )
        return SearchHit(
            title = extracted.title,
            url = finalPageUrl.toString(),
            content = extracted.text,
            rawContent = extracted.text,
            query = MANUAL_URL_QUERY
        )
    }

    private fun FetchedBody.toSearchHit(): SearchHit {
        val mime = contentType.substringBefore(';').trim().lowercase()
        return when {
            mime in HTML_MIME_TYPES || (mime.isBlank() && looksLikeHtml(bytes)) -> {
                val document = Jsoup.parse(
                    ByteArrayInputStream(bytes),
                    charsetName,
                    finalUrl.toString()
                )
                val extracted = WebPageContentExtractor.extractDocument(
                    document = document,
                    baseUrl = finalUrl.toString(),
                    site = if (finalUrl.host.equals("dic.pixiv.net", ignoreCase = true)) {
                        WebPageSite.PIXIV_DICTIONARY
                    } else {
                        WebPageSite.GENERIC
                    }
                )
                SearchHit(
                    title = extracted.title,
                    url = finalUrl.toString(),
                    content = extracted.text,
                    rawContent = extracted.text,
                    query = MANUAL_URL_QUERY
                )
            }
            mime.startsWith("text/") || mime in TEXT_MIME_TYPES -> {
                val text = ResearchCleaner.sanitizeText(decodeText())
                require(text.isNotBlank()) { "网页正文为空" }
                SearchHit(
                    title = finalUrl.host + finalUrl.encodedPath,
                    url = finalUrl.toString(),
                    content = text,
                    rawContent = text,
                    query = MANUAL_URL_QUERY
                )
            }
            else -> error("不支持的网页内容类型：${contentType.ifBlank { "未知" }}")
        }
    }

    private fun fetchBody(initialUrl: HttpUrl, accept: String, deadlineNanos: Long): FetchedBody {
        var currentUrl = initialUrl
        repeat(MAX_REDIRECTS + 1) { redirectIndex ->
            validatePublicTarget(currentUrl)
            val request = Request.Builder()
                .url(currentUrl)
                .header("Accept", accept)
                .header("Accept-Language", "zh-CN,zh;q=0.9,ja;q=0.8,en;q=0.7")
                .header("User-Agent", USER_AGENT)
                .get()
                .build()
            val remainingNanos = deadlineNanos - System.nanoTime()
            require(remainingNanos > 0L) { "指定网页读取超时" }
            val call = client.newCall(request)
            call.timeout().timeout(remainingNanos, TimeUnit.NANOSECONDS)
            call.execute().use { response ->
                if (response.code in REDIRECT_CODES) {
                    require(redirectIndex < MAX_REDIRECTS) { "网页重定向次数过多" }
                    val location = response.header("Location") ?: error("网页重定向缺少目标地址")
                    currentUrl = currentUrl.resolve(location) ?: error("网页重定向地址无效")
                    return@repeat
                }
                require(response.isSuccessful) { "网页请求失败：HTTP ${response.code}" }
                val body = response.body ?: error("网页响应为空")
                if (body.contentLength() > MAX_RESPONSE_BYTES) {
                    error("网页正文超过 2 MiB 限制")
                }
                val source = body.source()
                val buffer = Buffer()
                var totalBytes = 0L
                while (totalBytes <= MAX_RESPONSE_BYTES) {
                    val remaining = MAX_RESPONSE_BYTES + 1L - totalBytes
                    val read = source.read(buffer, minOf(8_192L, remaining))
                    if (read == -1L) break
                    totalBytes += read
                }
                val bytes = buffer.readByteArray()
                require(bytes.size <= MAX_RESPONSE_BYTES) { "网页正文超过 2 MiB 限制" }
                val mediaType = body.contentType()
                return FetchedBody(
                    finalUrl = currentUrl,
                    contentType = mediaType?.toString().orEmpty(),
                    charsetName = mediaType?.charset()?.name(),
                    bytes = bytes
                )
            }
        }
        error("网页重定向次数过多")
    }

    private fun validatePublicTarget(url: HttpUrl) {
        require(url.scheme == "http" || url.scheme == "https") { "仅支持 HTTP(S) 地址" }
        require(url.username.isBlank() && url.password.isBlank()) { "网址不能包含用户名或密码" }
        val host = url.host.lowercase()
        require(host != "localhost" && !host.endsWith(".localhost")) { "不允许访问本机地址" }
        validatePublicAddresses(host, targetResolver(host))
    }

    private fun String.statusSnippet(maxChars: Int = 100): String =
        replace(Regex("\\s+"), " ").trim().let { text ->
            if (text.length <= maxChars) text else text.take(maxChars - 1) + "…"
        }

    private sealed interface FetchOutcome {
        data class Success(val hit: SearchHit) : FetchOutcome
        data class Failure(val failure: ManualWebPageFailure) : FetchOutcome
    }

    private data class FetchedBody(
        val finalUrl: HttpUrl,
        val contentType: String,
        val charsetName: String?,
        val bytes: ByteArray
    ) {
        fun decodeText(): String =
            bytes.toString(runCatching { charset(charsetName ?: "UTF-8") }.getOrDefault(Charsets.UTF_8))
    }

    private companion object {
        const val MAX_CONCURRENT_REQUESTS = 3
        const val MAX_REDIRECTS = 5
        const val MAX_RESPONSE_BYTES = 2 * 1024 * 1024
        const val USER_AGENT =
            "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 " +
                "(KHTML, like Gecko) Chrome/124.0 Mobile Safari/537.36 ChatBar/1.0"
        const val ACCEPT_DOCUMENT =
            "text/html,application/xhtml+xml,text/plain,application/json;q=0.9,*/*;q=0.1"
        const val ACCEPT_JSON = "application/json,text/json;q=0.9,*/*;q=0.1"
        const val MANUAL_URL_QUERY = "用户指定网址"
        val REDIRECT_CODES = setOf(301, 302, 303, 307, 308)
        val HTML_MIME_TYPES = setOf("text/html", "application/xhtml+xml")
        val TEXT_MIME_TYPES = setOf("application/json", "text/json", "application/ld+json")
    }
}

private fun buildManualWebPageClient(
    resolver: (String) -> List<InetAddress>
): OkHttpClient = ProxyAwareClient.modelApiBuilder { true }
    .connectTimeout(15, TimeUnit.SECONDS)
    .readTimeout(25, TimeUnit.SECONDS)
    .writeTimeout(15, TimeUnit.SECONDS)
    .followRedirects(false)
    .followSslRedirects(false)
    .dns(
        object : Dns {
            override fun lookup(hostname: String): List<InetAddress> =
                runCatching {
                    resolver(hostname).also { addresses ->
                        validatePublicAddresses(hostname, addresses)
                    }
                }.getOrElse { error ->
                    throw UnknownHostException(error.message ?: "网址无法解析").apply {
                        initCause(error)
                    }
                }
        }
    )
    .build()

private fun validatePublicAddresses(host: String, addresses: List<InetAddress>) {
    require(host != "localhost" && !host.endsWith(".localhost")) { "不允许访问本机地址" }
    require(addresses.isNotEmpty()) { "网址无法解析" }
    require(addresses.none(::isForbiddenNetworkAddress)) { "不允许访问本机、私网或保留地址" }
}

internal data class ExtractedWebPage(
    val title: String,
    val text: String
)

internal enum class WebPageSite {
    FANDOM,
    PIXIV_DICTIONARY,
    GENERIC
}

internal object WebPageContentExtractor {
    fun extractHtml(
        html: String,
        baseUrl: String,
        preferredTitle: String = "",
        site: WebPageSite = WebPageSite.GENERIC
    ): ExtractedWebPage =
        extractDocument(Jsoup.parse(html, baseUrl), baseUrl, preferredTitle, site)

    fun extractDocument(
        document: Document,
        baseUrl: String,
        preferredTitle: String = "",
        site: WebPageSite = WebPageSite.GENERIC
    ): ExtractedWebPage {
        document.select(
            "script,style,noscript,template,svg,canvas,iframe,form,nav,header,footer,aside," +
                "[hidden],[aria-hidden=true],[style*=display:none],[style*=\"display: none\"]," +
                "[style*=visibility:hidden],[style*=\"visibility: hidden\"],.hidden,.is-hidden," +
                ".mw-editsection,.toc,.navbox,.printfooter," +
                ".catlinks,.references,.mw-references-wrap,.advertisement,.ad-slot," +
                "[class*=advert],[id*=advert],[class*=cookie],[id*=cookie]," +
                "[class*=recommend],[id*=recommend],.global-navigation,.wds-global-footer," +
                ".page__right-rail,.mcf-wrapper"
        ).remove()

        val preferredSelectors = when (site) {
            WebPageSite.FANDOM -> listOf(".mw-parser-output")
            WebPageSite.PIXIV_DICTIONARY -> listOf(
                "#article-body",
                "#article-body .article_section",
                "#article-content",
                ".article-content",
                ".article-body",
                ".article_section",
                "article",
                "main",
                "[role=main]"
            )
            WebPageSite.GENERIC -> emptyList()
        }
        val preferred = preferredSelectors
            .asSequence()
            .mapNotNull(document::selectFirst)
            .firstOrNull { it.text().length >= MIN_CONTENT_CHARS }
        val candidates = document.select(
            "article,main,[role=main],#content,#main-content,.main-content," +
                ".article-content,.entry-content,.post-content"
        )
        val body = document.body()
        val scoredFallback = body
            .select("section,div")
            .filter { it.text().length >= MIN_CONTENT_CHARS }
            .maxByOrNull(::contentScore)
        val content = preferred
            ?: candidates.maxByOrNull(::contentScore)
            ?: scoredFallback
            ?: body
        val text = ResearchCleaner.sanitizeText(content.wholeText())
        require(text.length >= MIN_CONTENT_CHARS) { "网页没有可用静态正文，可能需要登录或 JavaScript 渲染" }
        val title = sequenceOf(
            preferredTitle,
            document.selectFirst("meta[property=og:title]")?.attr("content").orEmpty(),
            document.selectFirst("h1")?.text().orEmpty(),
            document.title(),
            baseUrl
        )
            .map(ResearchCleaner::sanitizeText)
            .firstOrNull(String::isNotBlank)
            .orEmpty()
            .take(160)
        return ExtractedWebPage(title = title, text = text)
    }

    private fun contentScore(element: Element): Int {
        val textLength = element.text().length
        val linkLength = element.select("a").sumOf { it.text().length }
        return textLength - linkLength.coerceAtMost(textLength) / 2
    }

    private const val MIN_CONTENT_CHARS = 40
}

internal fun isForbiddenNetworkAddress(address: InetAddress): Boolean {
    if (
        address.isAnyLocalAddress ||
        address.isLoopbackAddress ||
        address.isLinkLocalAddress ||
        address.isSiteLocalAddress ||
        address.isMulticastAddress
    ) {
        return true
    }
    val bytes = address.address.map(Byte::toInt).map { it and 0xff }
    if (bytes.size == 4) {
        val first = bytes[0]
        val second = bytes[1]
        return first == 0 ||
            first >= 224 ||
            (first == 100 && second in 64..127) ||
            (first == 192 && second == 0) ||
            (first == 198 && second in 18..19) ||
            (first == 192 && second == 0 && bytes[2] == 2) ||
            (first == 198 && second == 51 && bytes[2] == 100) ||
            (first == 203 && second == 0 && bytes[2] == 113)
    }
    if (bytes.size == 16) {
        if (bytes[0] and 0xfe == 0xfc) return true
        if (bytes[0] == 0x20 && bytes[1] == 0x01 && bytes[2] == 0x0d && bytes[3] == 0xb8) {
            return true
        }
        val ipv4Mapped = bytes.take(10).all { it == 0 } && bytes[10] == 0xff && bytes[11] == 0xff
        if (ipv4Mapped) {
            return runCatching {
                isForbiddenNetworkAddress(InetAddress.getByAddress(bytes.takeLast(4).map(Int::toByte).toByteArray()))
            }.getOrDefault(true)
        }
    }
    return false
}

private fun looksLikeHtml(bytes: ByteArray): Boolean {
    val prefix = bytes.take(512).toByteArray().toString(Charsets.UTF_8).trimStart()
    return prefix.startsWith("<!doctype", ignoreCase = true) ||
        prefix.startsWith("<html", ignoreCase = true) ||
        prefix.startsWith("<head", ignoreCase = true) ||
        prefix.startsWith("<body", ignoreCase = true)
}
