package com.example.chatbar.domain.update

import com.example.chatbar.BuildConfig
import com.example.chatbar.domain.ProxyAwareClient
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request

data class AppUpdateInfo(
    val currentVersion: String,
    val latestVersion: String,
    val releaseUrl: String,
    val releaseName: String
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
        val request = Request.Builder()
            .url(apiLatestReleaseUrl())
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "ChatBar/$currentVersion")
            .get()
            .build()

        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (response.code == 404) return@withContext null
            if (!response.isSuccessful) {
                throw AppUpdateCheckException("GitHub release check failed: HTTP ${response.code}")
            }

            val release = json.decodeFromString(GitHubRelease.serializer(), body)
            if (release.draft || release.prerelease) return@withContext null

            val latestVersion = release.tagName.ifBlank { release.name }.trim()
            if (latestVersion.isBlank()) return@withContext null

            if (isReleaseVersionNewer(latestVersion, currentVersion)) {
                AppUpdateInfo(
                    currentVersion = currentVersion,
                    latestVersion = latestVersion,
                    releaseUrl = release.htmlUrl.ifBlank { releasesUrl(owner, repo) },
                    releaseName = release.name.ifBlank { latestVersion }
                )
            } else {
                null
            }
        }
    }

    private fun apiLatestReleaseUrl(): String {
        return "https://api.github.com/repos/$owner/$repo/releases/latest"
    }

    companion object {
        const val DEFAULT_RELEASES_URL = "https://github.com/SaltyFishOTL/ChatChatBar/releases"

        fun releasesUrl(owner: String = "SaltyFishOTL", repo: String = "ChatChatBar"): String {
            return "https://github.com/$owner/$repo/releases"
        }
    }
}

class AppUpdateCheckException(message: String) : Exception(message)

@Serializable
private data class GitHubRelease(
    @SerialName("tag_name")
    val tagName: String = "",
    @SerialName("html_url")
    val htmlUrl: String = "",
    val name: String = "",
    val draft: Boolean = false,
    val prerelease: Boolean = false
)

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
