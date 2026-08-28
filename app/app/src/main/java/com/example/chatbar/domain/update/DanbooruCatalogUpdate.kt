package com.example.chatbar.domain.update

import com.example.chatbar.BuildConfig
import com.example.chatbar.domain.ProxyAwareClient
import com.example.chatbar.domain.image.DanbooruCatalogMetadata
import com.example.chatbar.domain.image.DanbooruTagCatalog
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

data class DanbooruCatalogUpdateInfo(
    val currentMetadata: DanbooruCatalogMetadata,
    val latestSourceSha: String,
    val latestCommitTime: String,
    val downloadUrl: String,
    val sourcePageUrl: String,
    val sizeBytes: Long
)

data class UpdateCenterCheckResult(
    val appUpdate: AppUpdateInfo? = null,
    val catalogUpdate: DanbooruCatalogUpdateInfo? = null,
    val appError: String? = null,
    val catalogError: String? = null
) {
    val hasVisibleResult: Boolean
        get() = appUpdate != null || catalogUpdate != null || appError != null || catalogError != null
}

class UpdateCenterChecker(
    private val appUpdateChecker: AppUpdateChecker,
    private val catalogUpdateChecker: DanbooruCatalogUpdateChecker
) {
    suspend fun check(): UpdateCenterCheckResult = coroutineScope {
        val appDeferred = async { runCatching { appUpdateChecker.checkLatestRelease() } }
        val catalogDeferred = async { runCatching { catalogUpdateChecker.checkLatestCatalog() } }
        val appResult = appDeferred.await()
        val catalogResult = catalogDeferred.await()
        appResult.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        catalogResult.exceptionOrNull()?.let { if (it is CancellationException) throw it }
        UpdateCenterCheckResult(
            appUpdate = appResult.getOrNull(),
            catalogUpdate = catalogResult.getOrNull(),
            appError = appResult.exceptionOrNull()?.displayMessage("应用更新检查失败"),
            catalogError = catalogResult.exceptionOrNull()?.displayMessage("Danbooru 词条库检查失败")
        )
    }
}

class DanbooruCatalogUpdateChecker(
    private val catalog: DanbooruTagCatalog,
    private val client: OkHttpClient = ProxyAwareClient.builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(20, TimeUnit.SECONDS)
        .writeTimeout(10, TimeUnit.SECONDS)
        .build(),
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }
) {
    suspend fun checkLatestCatalog(): DanbooruCatalogUpdateInfo? = withContext(Dispatchers.IO) {
        val current = catalog.catalogMetadata()
        val commit = runCatching { fetchLatestCommit() }.getOrNull()
        val content = fetchContents(commit?.sha?.takeIf(String::isNotBlank) ?: DanbooruTagCatalog.SOURCE_BRANCH)
        if (content.sha.equals(current.sourceSha, ignoreCase = true)) return@withContext null
        if (content.sha.isBlank() || content.size <= 0L || content.downloadUrl.isBlank()) {
            throw IOException("GitHub 返回的词库文件信息不完整")
        }
        val pinnedDownloadUrl = commit?.sha?.takeIf(String::isNotBlank)?.let { commitSha ->
            "https://raw.githubusercontent.com/${DanbooruTagCatalog.SOURCE_OWNER}/" +
                "${DanbooruTagCatalog.SOURCE_REPOSITORY}/$commitSha/${DanbooruTagCatalog.SOURCE_PATH}"
        } ?: content.downloadUrl
        DanbooruCatalogUpdateInfo(
            currentMetadata = current,
            latestSourceSha = content.sha,
            latestCommitTime = commit?.commit?.committer?.date.orEmpty(),
            downloadUrl = pinnedDownloadUrl,
            sourcePageUrl = content.htmlUrl.ifBlank { DanbooruTagCatalog.SOURCE_PAGE_URL },
            sizeBytes = content.size
        )
    }

    private fun fetchContents(ref: String): GitHubContentFile {
        val url = "https://api.github.com/repos/${DanbooruTagCatalog.SOURCE_OWNER}/" +
            "${DanbooruTagCatalog.SOURCE_REPOSITORY}/contents/${DanbooruTagCatalog.SOURCE_PATH}" +
            "?ref=$ref"
        val request = githubRequest(url)
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("GitHub 词库 API HTTP ${response.code}")
            return json.decodeFromString(GitHubContentFile.serializer(), body)
        }
    }

    private fun fetchLatestCommit(): GitHubCommitItem? {
        val url = "https://api.github.com/repos/${DanbooruTagCatalog.SOURCE_OWNER}/" +
            "${DanbooruTagCatalog.SOURCE_REPOSITORY}/commits" +
            "?path=${DanbooruTagCatalog.SOURCE_PATH}&per_page=1"
        val request = githubRequest(url)
        client.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IOException("GitHub 词库提交 API HTTP ${response.code}")
            return json.decodeFromString(ListSerializer(GitHubCommitItem.serializer()), body).firstOrNull()
        }
    }

    private fun githubRequest(url: String): Request = Request.Builder()
        .url(url)
        .header("Accept", "application/vnd.github+json")
        .header("X-GitHub-Api-Version", "2022-11-28")
        .header("Cache-Control", "no-cache")
        .header("User-Agent", "ChatBar/${BuildConfig.VERSION_NAME}")
        .get()
        .build()
}

sealed interface DanbooruCatalogUpdateState {
    data object Idle : DanbooruCatalogUpdateState

    data class Downloading(
        val sourceSha: String,
        val bytesDownloaded: Long,
        val totalBytes: Long
    ) : DanbooruCatalogUpdateState {
        val progress: Float
            get() = if (totalBytes <= 0L) 0f else
                (bytesDownloaded.toDouble() / totalBytes.toDouble()).toFloat().coerceIn(0f, 1f)
    }

    data class Validating(val sourceSha: String) : DanbooruCatalogUpdateState
    data class Applying(val sourceSha: String) : DanbooruCatalogUpdateState
    data class Ready(val metadata: DanbooruCatalogMetadata) : DanbooruCatalogUpdateState
    data class Failed(val sourceSha: String, val message: String) : DanbooruCatalogUpdateState
}

class DanbooruCatalogUpdateManager(
    private val scope: CoroutineScope,
    private val catalog: DanbooruTagCatalog,
    private val client: OkHttpClient = ProxyAwareClient.builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    private val _state = MutableStateFlow<DanbooruCatalogUpdateState>(DanbooruCatalogUpdateState.Idle)
    val state: StateFlow<DanbooruCatalogUpdateState> = _state.asStateFlow()

    private var requestId = 0L
    private var job: Job? = null
    private var activeCall: Call? = null

    init {
        catalog.cleanupInterruptedDownload()
    }

    @Synchronized
    fun startDownload(updateInfo: DanbooruCatalogUpdateInfo) {
        when (val current = stateFor(updateInfo)) {
            is DanbooruCatalogUpdateState.Downloading,
            is DanbooruCatalogUpdateState.Validating,
            is DanbooruCatalogUpdateState.Applying,
            is DanbooruCatalogUpdateState.Ready -> return
            else -> Unit
        }
        cancelActiveLocked()
        val id = ++requestId
        _state.value = DanbooruCatalogUpdateState.Downloading(
            sourceSha = updateInfo.latestSourceSha,
            bytesDownloaded = 0L,
            totalBytes = updateInfo.sizeBytes
        )
        job = scope.launch { download(id, updateInfo) }
    }

    @Synchronized
    fun cancelDownload() {
        if (_state.value !is DanbooruCatalogUpdateState.Downloading) return
        ++requestId
        cancelActiveLocked()
        catalog.cleanupInterruptedDownload()
        _state.value = DanbooruCatalogUpdateState.Idle
    }

    fun stateFor(updateInfo: DanbooruCatalogUpdateInfo): DanbooruCatalogUpdateState = when (val current = _state.value) {
        is DanbooruCatalogUpdateState.Downloading -> current.takeIf { it.sourceSha == updateInfo.latestSourceSha }
        is DanbooruCatalogUpdateState.Validating -> current.takeIf { it.sourceSha == updateInfo.latestSourceSha }
        is DanbooruCatalogUpdateState.Applying -> current.takeIf { it.sourceSha == updateInfo.latestSourceSha }
        is DanbooruCatalogUpdateState.Ready -> current.takeIf {
            it.metadata.sourceSha == updateInfo.latestSourceSha
        }
        is DanbooruCatalogUpdateState.Failed -> current.takeIf { it.sourceSha == updateInfo.latestSourceSha }
        DanbooruCatalogUpdateState.Idle -> current
    } ?: DanbooruCatalogUpdateState.Idle

    private suspend fun download(id: Long, updateInfo: DanbooruCatalogUpdateInfo) {
        val stagedFile = catalog.downloadStagingFile()
        try {
            stagedFile.parentFile?.let { parent ->
                if (!parent.exists() && !parent.mkdirs()) throw IOException("无法创建词库更新目录")
            }
            stagedFile.delete()
            val request = Request.Builder()
                .url(updateInfo.downloadUrl)
                .header("Accept", "application/octet-stream")
                .header("Cache-Control", "no-cache")
                .header("User-Agent", "ChatBar/${BuildConfig.VERSION_NAME}")
                .get()
                .build()
            val call = client.newCall(request)
            synchronized(this) {
                if (id != requestId) return
                activeCall = call
            }
            call.execute().use { response ->
                if (!response.isSuccessful) throw IOException("词库下载失败：HTTP ${response.code}")
                val body = response.body ?: throw IOException("词库下载响应为空")
                var downloaded = 0L
                var lastPublished = 0L
                body.byteStream().use { input ->
                    FileOutputStream(stagedFile).buffered().use { output ->
                        val buffer = ByteArray(DOWNLOAD_BUFFER_SIZE)
                        while (true) {
                            currentCoroutineContext().ensureActive()
                            val count = input.read(buffer)
                            if (count < 0) break
                            output.write(buffer, 0, count)
                            downloaded += count
                            if (downloaded - lastPublished >= PROGRESS_STEP_BYTES) {
                                publish(
                                    id,
                                    DanbooruCatalogUpdateState.Downloading(
                                        updateInfo.latestSourceSha,
                                        downloaded,
                                        updateInfo.sizeBytes
                                    )
                                )
                                lastPublished = downloaded
                            }
                        }
                    }
                }
            }
            publish(id, DanbooruCatalogUpdateState.Validating(updateInfo.latestSourceSha))
            val validation = catalog.validateDownloadedDatabase(
                file = stagedFile,
                expectedSizeBytes = updateInfo.sizeBytes,
                expectedSourceSha = updateInfo.latestSourceSha
            )
            publish(id, DanbooruCatalogUpdateState.Applying(updateInfo.latestSourceSha))
            val installed = catalog.installDownloadedDatabase(
                stagedFile = stagedFile,
                sourceCommitTime = updateInfo.latestCommitTime,
                validation = validation
            )
            publish(id, DanbooruCatalogUpdateState.Ready(installed))
        } catch (error: CancellationException) {
            stagedFile.delete()
            throw error
        } catch (error: Throwable) {
            stagedFile.delete()
            publish(
                id,
                DanbooruCatalogUpdateState.Failed(
                    updateInfo.latestSourceSha,
                    error.displayMessage("词库更新失败")
                )
            )
        } finally {
            synchronized(this) {
                if (id == requestId) {
                    activeCall = null
                    job = null
                }
            }
        }
    }

    @Synchronized
    private fun publish(id: Long, next: DanbooruCatalogUpdateState) {
        if (id == requestId) _state.value = next
    }

    private fun cancelActiveLocked() {
        activeCall?.cancel()
        activeCall = null
        job?.cancel()
        job = null
    }

    private companion object {
        const val DOWNLOAD_BUFFER_SIZE = 64 * 1024
        const val PROGRESS_STEP_BYTES = 128 * 1024L
    }
}

private fun Throwable.displayMessage(fallback: String): String =
    message?.takeIf(String::isNotBlank) ?: fallback

@Serializable
private data class GitHubContentFile(
    val sha: String = "",
    val size: Long = 0L,
    @SerialName("download_url") val downloadUrl: String = "",
    @SerialName("html_url") val htmlUrl: String = ""
)

@Serializable
private data class GitHubCommitItem(
    val sha: String = "",
    val commit: GitHubCommitDetails = GitHubCommitDetails()
)

@Serializable
private data class GitHubCommitDetails(
    val committer: GitHubCommitter = GitHubCommitter()
)

@Serializable
private data class GitHubCommitter(
    val date: String = ""
)
