package com.example.chatbar.domain.update

import android.util.Log
import com.example.chatbar.BuildConfig
import com.example.chatbar.domain.ProxyAwareClient
import java.io.StringReader
import java.net.URI
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import javax.xml.parsers.DocumentBuilderFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import org.w3c.dom.Element
import org.xml.sax.InputSource

data class AppUpdateInfo(
    val currentVersion: String,
    val latestVersion: String,
    val releaseUrl: String,
    val releaseName: String,
    val releaseNotes: List<AppReleaseNote> = emptyList(),
    val apkAsset: AppUpdateAsset? = null
)

data class AppUpdateAsset(
    val name: String,
    val downloadUrl: String,
    val sizeBytes: Long? = null
)

data class AppReleaseNote(
    val version: String,
    val name: String,
    val body: String,
    val releaseUrl: String
)

class AppUpdateChecker(
    private val currentVersion: String = BuildConfig.VERSION_NAME,
    private val owner: String = "SaltyFishOTL",
    private val repo: String = "ChatChatBar",
    private val client: OkHttpClient = ProxyAwareClient.builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build()
) {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    suspend fun checkLatestRelease(): AppUpdateInfo? = withContext(Dispatchers.IO) {
        val releasesApiAttempt = runCatching { fetchReleasesFromApi() }
        val apiReleases = releasesApiAttempt.getOrNull().orEmpty().filterStable()
        val atomAttempt = if (apiReleases.isEmpty()) {
            runCatching { fetchReleasesFromAtom() }
        } else {
            null
        }
        releasesApiAttempt.exceptionOrNull()?.let { error ->
            Log.w(TAG, "GitHub Releases API failed; trying Atom feed", error)
        }
        atomAttempt?.exceptionOrNull()?.let { error ->
            Log.w(TAG, "GitHub Releases Atom feed failed; trying single-release fallback", error)
        }
        val releases = apiReleases.ifEmpty {
            atomAttempt?.getOrNull().orEmpty().filterStable()
        }
        val release = if (releases.isNotEmpty()) {
            releases.first()
        } else {
            val apiAttempt = runCatching { fetchLatestReleaseFromApi() }
            apiAttempt.getOrNull() ?: run {
                val webAttempt = runCatching { fetchLatestReleaseFromWebRedirect() }
                webAttempt.getOrNull()
                    ?: handleLookupFailures(
                        webError = webAttempt.exceptionOrNull(),
                        apiError = apiAttempt.exceptionOrNull(),
                        releasesApiError = releasesApiAttempt.exceptionOrNull(),
                        atomError = atomAttempt?.exceptionOrNull()
                    )
            } ?: return@withContext null
        }

        if (release.draft || release.prerelease) return@withContext null

        val latestVersion = release.tagName.ifBlank { release.name }.trim()
        if (latestVersion.isBlank()) return@withContext null

        if (isReleaseVersionNewer(latestVersion, currentVersion)) {
            AppUpdateInfo(
                currentVersion = currentVersion,
                latestVersion = latestVersion,
                releaseUrl = release.htmlUrl.ifBlank { releasesUrl(owner, repo) },
                releaseName = release.name.ifBlank { latestVersion },
                releaseNotes = releaseNotesBetween(
                    releases = releases.ifEmpty { listOf(release) },
                    currentVersion = currentVersion,
                    latestVersion = latestVersion
                ),
                apkAsset = release.resolveApkAsset(owner, repo)
            )
        } else {
            null
        }
    }

    private fun fetchLatestReleaseFromWebRedirect(): GitHubRelease? {
        val request = Request.Builder()
            .url(webLatestReleaseUrl())
            .header("Accept", "text/html,application/xhtml+xml")
            .header("Cache-Control", "no-cache")
            .header("User-Agent", userAgent())
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) throw httpException("GitHub Release 页面", response.code)

            val releaseUrl = response.request.url.toString()
            val tagName = releaseTagFromUrl(releaseUrl) ?: return null
            return GitHubRelease(
                tagName = tagName,
                htmlUrl = releaseUrl,
                name = tagName
            )
        }
    }

    private fun fetchLatestReleaseFromApi(): GitHubRelease? {
        val request = Request.Builder()
            .url(apiLatestReleaseUrl())
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("Cache-Control", "no-cache")
            .header("User-Agent", userAgent())
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code == 404) return null
            if (!response.isSuccessful) {
                throw httpException("GitHub API", response.code)
            }

            return json.decodeFromString(GitHubRelease.serializer(), body)
        }
    }

    private fun fetchReleasesFromApi(): List<GitHubRelease> {
        val request = Request.Builder()
            .url(apiReleasesUrl())
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("Cache-Control", "no-cache")
            .header("User-Agent", userAgent())
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code == 404) return emptyList()
            if (!response.isSuccessful) {
                throw httpException("GitHub Releases API", response.code)
            }

            return json.decodeFromString(ListSerializer(GitHubRelease.serializer()), body)
        }
    }

    private fun fetchReleasesFromAtom(): List<GitHubRelease> {
        val request = Request.Builder()
            .url(atomReleasesUrl())
            .header("Accept", "application/atom+xml,application/xml;q=0.9")
            .header("Cache-Control", "no-cache")
            .header("User-Agent", userAgent())
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code == 404) return emptyList()
            if (!response.isSuccessful) {
                throw httpException("GitHub Releases Atom", response.code)
            }

            return parseGitHubReleasesAtom(body)
        }
    }

    private fun apiLatestReleaseUrl(): String {
        return "https://api.github.com/repos/$owner/$repo/releases/latest"
    }

    private fun apiReleasesUrl(): String {
        return "https://api.github.com/repos/$owner/$repo/releases?per_page=100"
    }

    private fun atomReleasesUrl(): String {
        return "https://github.com/$owner/$repo/releases.atom"
    }

    private fun webLatestReleaseUrl(): String {
        return "https://github.com/$owner/$repo/releases/latest"
    }

    private fun userAgent(): String {
        return "ChatBar/$currentVersion"
    }

    private fun httpException(source: String, code: Int): AppUpdateCheckException {
        val hint = if (code == 403) "，可能是 GitHub 限流或网络拦截" else ""
        return AppUpdateCheckException("$source HTTP $code$hint")
    }

    private fun handleLookupFailures(
        webError: Throwable?,
        apiError: Throwable?,
        releasesApiError: Throwable?,
        atomError: Throwable?
    ): GitHubRelease? {
        if (webError == null && apiError == null && releasesApiError == null && atomError == null) return null
        val webMessage = webError?.message ?: "无结果"
        val apiMessage = apiError?.message ?: "无结果"
        val releasesApiMessage = releasesApiError?.message ?: "无结果"
        val atomMessage = atomError?.message ?: "无结果"
        throw AppUpdateCheckException(
            "GitHub 更新检查失败：列表 API $releasesApiMessage；Atom $atomMessage；" +
                "单版本 API $apiMessage；网页通道 $webMessage",
            apiError ?: releasesApiError ?: atomError ?: webError
        )
    }

    companion object {
        private const val TAG = "AppUpdateChecker"
        const val DEFAULT_RELEASES_URL = "https://github.com/SaltyFishOTL/ChatChatBar/releases"

        fun releasesUrl(owner: String = "SaltyFishOTL", repo: String = "ChatChatBar"): String {
            return "https://github.com/$owner/$repo/releases"
        }
    }
}

class AppUpdateCheckException(message: String, cause: Throwable? = null) : Exception(message, cause)

@Serializable
internal data class GitHubRelease(
    @SerialName("tag_name")
    val tagName: String = "",
    @SerialName("html_url")
    val htmlUrl: String = "",
    val name: String = "",
    val body: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false,
    val assets: List<GitHubReleaseAsset> = emptyList()
)

@Serializable
internal data class GitHubReleaseAsset(
    val name: String = "",
    @SerialName("browser_download_url")
    val browserDownloadUrl: String = "",
    @SerialName("content_type")
    val contentType: String = "",
    val size: Long = 0L
)

private fun List<GitHubRelease>.filterStable(): List<GitHubRelease> =
    filter { !it.draft && !it.prerelease && it.versionName().isNotBlank() }

private fun GitHubRelease.versionName(): String =
    tagName.ifBlank { name }.trim()

internal fun parseGitHubReleasesAtom(xml: String): List<GitHubRelease> {
    if (xml.isBlank()) return emptyList()
    require(!xml.contains("<!DOCTYPE", ignoreCase = true)) {
        "GitHub Releases Atom contains unsupported DOCTYPE"
    }

    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        isExpandEntityReferences = false
        runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
        runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        runCatching { setFeature("http://xml.org/sax/features/external-parameter-entities", false) }
    }
    val document = factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
    val entries = document.getElementsByTagNameNS(ATOM_NAMESPACE, "entry")
    return buildList {
        for (index in 0 until entries.length) {
            val entry = entries.item(index) as? Element ?: continue
            val releaseUrl = entry.directChildren("link")
                .firstOrNull { link ->
                    link.getAttribute("rel").ifBlank { "alternate" } == "alternate" &&
                        link.getAttribute("href").isNotBlank()
                }
                ?.getAttribute("href")
                .orEmpty()
            val tagName = releaseTagFromUrl(releaseUrl)
                ?: entry.directChildText("id").substringAfterLast('/').takeIf { it.isNotBlank() }
                ?: continue
            add(
                GitHubRelease(
                    tagName = tagName,
                    htmlUrl = releaseUrl,
                    name = entry.directChildText("title").ifBlank { tagName },
                    body = githubReleaseHtmlToPlainText(entry.directChildText("content")),
                    prerelease = tagName.looksLikePrerelease()
                )
            )
        }
    }.sortedWith { left, right ->
        compareReleaseVersions(right.versionName(), left.versionName())
    }
}

internal fun githubReleaseHtmlToPlainText(html: String): String {
    if (html.isBlank()) return ""
    val withBlocks = html
        .replace(Regex("(?i)<br\\s*/?>"), "\n")
        .replace(Regex("(?i)<li(?:\\s[^>]*)?>"), "- ")
        .replace(Regex("(?i)</(?:h[1-6]|p|li|ul|ol|pre|blockquote)>"), "\n")
        .replace(Regex("<[^>]+>"), "")
    return decodeHtmlEntities(withBlocks)
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .joinToString("\n")
}

private fun Element.directChildren(localName: String): List<Element> = buildList {
    val nodes = childNodes
    for (index in 0 until nodes.length) {
        val element = nodes.item(index) as? Element ?: continue
        if (element.localName == localName || element.tagName == localName) add(element)
    }
}

private fun Element.directChildText(localName: String): String =
    directChildren(localName).firstOrNull()?.textContent.orEmpty().trim()

private fun String.looksLikePrerelease(): Boolean {
    val clean = trim().removePrefix("v").removePrefix("V")
    val numericPrefix = clean.takeWhile { it.isDigit() || it == '.' }.trimEnd('.')
    return numericPrefix.isNotBlank() && clean.removePrefix(numericPrefix).isNotBlank()
}

private fun decodeHtmlEntities(value: String): String {
    val namedDecoded = value
        .replace("&nbsp;", " ", ignoreCase = true)
        .replace("&quot;", "\"", ignoreCase = true)
        .replace("&#39;", "'", ignoreCase = true)
        .replace("&apos;", "'", ignoreCase = true)
        .replace("&lt;", "<", ignoreCase = true)
        .replace("&gt;", ">", ignoreCase = true)
        .replace("&amp;", "&", ignoreCase = true)
    return HTML_NUMERIC_ENTITY.replace(namedDecoded) { match ->
        val raw = match.groupValues[1]
        val radix = if (raw.startsWith("x", ignoreCase = true)) 16 else 10
        val digits = if (radix == 16) raw.drop(1) else raw
        val codePoint = digits.toIntOrNull(radix)
            ?.takeIf(Character::isValidCodePoint)
            ?: return@replace match.value
        String(Character.toChars(codePoint))
    }
}

internal fun GitHubRelease.resolveApkAsset(owner: String, repo: String): AppUpdateAsset? {
    val publishedAsset = assets
        .asSequence()
        .filter { asset ->
            asset.browserDownloadUrl.isNotBlank() &&
                (asset.name.endsWith(".apk", ignoreCase = true) ||
                    asset.contentType.equals("application/vnd.android.package-archive", ignoreCase = true))
        }
        .sortedWith(
            compareBy<GitHubReleaseAsset> { it.name.contains("unsigned", ignoreCase = true) }
                .thenBy { it.name.contains("debug", ignoreCase = true) }
                .thenByDescending { it.name.startsWith("ChatBar-", ignoreCase = true) }
        )
        .firstOrNull()

    if (publishedAsset != null) {
        return AppUpdateAsset(
            name = publishedAsset.name.ifBlank { "ChatBar-${versionName()}.apk" },
            downloadUrl = publishedAsset.browserDownloadUrl,
            sizeBytes = publishedAsset.size.takeIf { it > 0L }
        )
    }

    val tag = tagName.trim().takeIf { it.isNotBlank() } ?: return null
    val normalizedVersion = versionName()
        .removePrefix("v")
        .removePrefix("V")
        .takeIf { it.isNotBlank() }
        ?: return null
    val assetName = "ChatBar-$normalizedVersion.apk"
    return AppUpdateAsset(
        name = assetName,
        downloadUrl = "https://github.com/$owner/$repo/releases/download/$tag/$assetName"
    )
}

internal fun releaseNotesBetween(
    releases: List<GitHubRelease>,
    currentVersion: String,
    latestVersion: String
): List<AppReleaseNote> =
    releases
        .filterStable()
        .filter { release ->
            val version = release.versionName()
            compareReleaseVersions(version, currentVersion) > 0 &&
                compareReleaseVersions(latestVersion, version) >= 0
        }
        .map { release ->
            val version = release.versionName()
            AppReleaseNote(
                version = version,
                name = release.name.ifBlank { version },
                body = release.body.trim(),
                releaseUrl = release.htmlUrl
            )
        }

internal fun isReleaseVersionNewer(latestVersion: String, currentVersion: String): Boolean {
    return compareReleaseVersions(latestVersion, currentVersion) > 0
}

internal fun compareReleaseVersions(left: String, right: String): Int {
    val leftParts = normalizedVersionParts(left) ?: return left.compareTo(right, ignoreCase = true)
    val rightParts = normalizedVersionParts(right) ?: return left.compareTo(right, ignoreCase = true)
    val size = maxOf(leftParts.size, rightParts.size, 3)

    for (index in 0 until size) {
        val leftPart = leftParts.getOrElse(index) { 0 }
        val rightPart = rightParts.getOrElse(index) { 0 }
        if (leftPart != rightPart) return leftPart.compareTo(rightPart)
    }

    return 0
}

private fun normalizedVersionParts(version: String): List<Int>? {
    val clean = version.trim()
        .removePrefix("v")
        .removePrefix("V")
    val numericPrefix = clean.takeWhile { it.isDigit() || it == '.' }
        .trim('.')
    if (numericPrefix.isBlank()) return null

    val parts = numericPrefix.split('.').map { part ->
        part.toIntOrNull() ?: return null
    }
    return parts
}

private const val ATOM_NAMESPACE = "http://www.w3.org/2005/Atom"
private val HTML_NUMERIC_ENTITY = Regex("&#(x?[0-9A-Fa-f]+);", RegexOption.IGNORE_CASE)

internal fun releaseTagFromUrl(url: String): String? {
    val rawPath = runCatching { URI(url).rawPath }.getOrNull() ?: return null
    val segments = rawPath.split('/').filter { it.isNotBlank() }
    val releasesIndex = segments.indexOf("releases")
    if (releasesIndex < 0 || segments.getOrNull(releasesIndex + 1) != "tag") return null

    val rawTag = segments.getOrNull(releasesIndex + 2)?.takeIf { it.isNotBlank() } ?: return null
    return URLDecoder.decode(rawTag.replace("+", "%2B"), StandardCharsets.UTF_8.name())
}
