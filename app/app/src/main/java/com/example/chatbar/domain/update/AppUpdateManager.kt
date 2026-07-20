package com.example.chatbar.domain.update

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import com.example.chatbar.domain.ProxyAwareClient
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request

sealed interface AppUpdateDownloadState {
    data object Idle : AppUpdateDownloadState

    data class Downloading(
        val downloadUrl: String,
        val bytesDownloaded: Long,
        val totalBytes: Long?
    ) : AppUpdateDownloadState {
        val progress: Float?
            get() = totalBytes
                ?.takeIf { it > 0L }
                ?.let { (bytesDownloaded.toDouble() / it.toDouble()).toFloat().coerceIn(0f, 1f) }
    }

    data class Ready(
        val downloadUrl: String,
        val filePath: String
    ) : AppUpdateDownloadState

    data class Failed(
        val downloadUrl: String,
        val message: String
    ) : AppUpdateDownloadState
}

enum class AppUpdateInstallResult {
    PermissionRequired,
    InstallerLaunched
}

class AppUpdateManager(
    private val app: Application,
    private val scope: CoroutineScope,
    private val client: OkHttpClient = ProxyAwareClient.builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(45, TimeUnit.SECONDS)
        .writeTimeout(15, TimeUnit.SECONDS)
        .build()
) {
    private val _downloadState = MutableStateFlow<AppUpdateDownloadState>(AppUpdateDownloadState.Idle)
    val downloadState: StateFlow<AppUpdateDownloadState> = _downloadState.asStateFlow()

    private var requestId = 0L
    private var downloadJob: Job? = null
    private var activeCall: Call? = null
    private var partialFile: File? = null

    @Synchronized
    fun startDownload(updateInfo: AppUpdateInfo) {
        val asset = updateInfo.apkAsset ?: run {
            _downloadState.value = AppUpdateDownloadState.Failed(
                downloadUrl = "",
                message = "此版本未提供可安装的 APK"
            )
            return
        }
        val currentState = stateFor(updateInfo)
        if (currentState is AppUpdateDownloadState.Downloading ||
            currentState is AppUpdateDownloadState.Ready
        ) {
            return
        }

        cancelActiveLocked()
        val id = ++requestId
        _downloadState.value = AppUpdateDownloadState.Downloading(
            downloadUrl = asset.downloadUrl,
            bytesDownloaded = 0L,
            totalBytes = asset.sizeBytes
        )
        downloadJob = scope.launch {
            download(id, updateInfo, asset)
        }
    }

    @Synchronized
    fun cancelDownload() {
        ++requestId
        cancelActiveLocked()
        _downloadState.value = AppUpdateDownloadState.Idle
    }

    fun stateFor(updateInfo: AppUpdateInfo): AppUpdateDownloadState {
        val assetUrl = updateInfo.apkAsset?.downloadUrl ?: return AppUpdateDownloadState.Idle
        return when (val state = _downloadState.value) {
            is AppUpdateDownloadState.Downloading -> state.takeIf { it.downloadUrl == assetUrl }
            is AppUpdateDownloadState.Ready -> state.takeIf { it.downloadUrl == assetUrl }
            is AppUpdateDownloadState.Failed -> state.takeIf { it.downloadUrl == assetUrl }
            AppUpdateDownloadState.Idle -> state
        } ?: AppUpdateDownloadState.Idle
    }

    fun requestInstall(
        context: Context,
        updateInfo: AppUpdateInfo
    ): Result<AppUpdateInstallResult> = runCatching {
        val ready = stateFor(updateInfo) as? AppUpdateDownloadState.Ready
            ?: throw IllegalStateException("更新包尚未下载完成")
        val apkFile = File(ready.filePath)
        validateApk(apkFile, updateInfo)

        if (!context.packageManager.canRequestPackageInstalls()) {
            val permissionIntent = Intent(
                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                Uri.parse("package:${context.packageName}")
            ).addNewTaskFlagIfNeeded(context)
            context.startActivity(permissionIntent)
            return@runCatching AppUpdateInstallResult.PermissionRequired
        }

        val apkUri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            apkFile
        )
        val installIntent = Intent(Intent.ACTION_INSTALL_PACKAGE)
            .setData(apkUri)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            .addNewTaskFlagIfNeeded(context)
        context.startActivity(installIntent)
        AppUpdateInstallResult.InstallerLaunched
    }

    private suspend fun download(
        id: Long,
        updateInfo: AppUpdateInfo,
        asset: AppUpdateAsset
    ) {
        val updatesDir = File(app.filesDir, UPDATES_DIRECTORY)
        val safeVersion = updateInfo.latestVersion
            .map { character -> if (character.isLetterOrDigit() || character == '.' || character == '-') character else '_' }
            .joinToString("")
            .ifBlank { "latest" }
        val finalFile = File(updatesDir, "ChatBar-$safeVersion.apk")
        val downloadFile = File(updatesDir, "ChatBar-$safeVersion.apk.part")

        try {
            if (!updatesDir.exists() && !updatesDir.mkdirs()) {
                throw IOException("无法创建更新目录")
            }
            updatesDir.listFiles()
                ?.filterNot { it == finalFile || it == downloadFile }
                ?.forEach { it.delete() }
            downloadFile.delete()
            synchronized(this) {
                if (id != requestId) return
                partialFile = downloadFile
            }

            if (finalFile.exists()) {
                runCatching { validateApk(finalFile, updateInfo) }
                    .onSuccess {
                        publish(id, AppUpdateDownloadState.Ready(asset.downloadUrl, finalFile.absolutePath))
                        return
                    }
                finalFile.delete()
            }

            val request = Request.Builder()
                .url(asset.downloadUrl)
                .header("Accept", "application/vnd.android.package-archive,application/octet-stream")
                .header("Cache-Control", "no-cache")
                .header("User-Agent", "ChatBar/${updateInfo.currentVersion}")
                .get()
                .build()
            val call = client.newCall(request)
            synchronized(this) {
                if (id != requestId) return
                activeCall = call
            }

            call.execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("更新包下载失败：HTTP ${response.code}")
                }
                val body = response.body ?: throw IOException("更新包响应为空")
                val responseLength = body.contentLength().takeIf { it > 0L }
                val expectedLength = asset.sizeBytes ?: responseLength
                var downloaded = 0L
                var lastPublished = 0L

                body.byteStream().use { input ->
                    FileOutputStream(downloadFile).buffered().use { output ->
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
                                    AppUpdateDownloadState.Downloading(
                                        downloadUrl = asset.downloadUrl,
                                        bytesDownloaded = downloaded,
                                        totalBytes = expectedLength
                                    )
                                )
                                lastPublished = downloaded
                            }
                        }
                    }
                }

                if (expectedLength != null && downloaded != expectedLength) {
                    throw IOException("更新包大小不完整：已下载 $downloaded 字节，应为 $expectedLength 字节")
                }
            }

            finalFile.delete()
            if (!downloadFile.renameTo(finalFile)) {
                throw IOException("无法保存更新包")
            }
            validateApk(finalFile, updateInfo)
            publish(id, AppUpdateDownloadState.Ready(asset.downloadUrl, finalFile.absolutePath))
        } catch (error: CancellationException) {
            downloadFile.delete()
            throw error
        } catch (error: Throwable) {
            downloadFile.delete()
            finalFile.takeIf { it.exists() }
                ?.takeIf { runCatching { validateApk(it, updateInfo) }.isFailure }
                ?.delete()
            publish(
                id,
                AppUpdateDownloadState.Failed(
                    downloadUrl = asset.downloadUrl,
                    message = error.message?.takeIf { it.isNotBlank() } ?: "下载更新包失败"
                )
            )
        } finally {
            synchronized(this) {
                if (id == requestId) {
                    activeCall = null
                    partialFile = null
                    downloadJob = null
                }
            }
        }
    }

    private fun validateApk(file: File, updateInfo: AppUpdateInfo) {
        if (!file.isFile || file.length() <= 0L) throw IOException("更新包文件无效")
        val packageManager = app.packageManager
        val archiveInfo = packageManager.archivePackageInfo(file)
            ?: throw IOException("下载内容不是有效 APK")
        if (archiveInfo.packageName != app.packageName) {
            throw IOException("更新包应用标识不匹配")
        }

        val archiveVersion = archiveInfo.versionName.orEmpty()
        if (compareReleaseVersions(archiveVersion, updateInfo.latestVersion) != 0) {
            throw IOException("更新包版本不匹配：$archiveVersion")
        }

        val installedInfo = packageManager.installedPackageInfo(app.packageName)
        if (archiveInfo.compatLongVersionCode() <= installedInfo.compatLongVersionCode()) {
            throw IOException("更新包 versionCode 未高于当前版本")
        }
    }

    @Synchronized
    private fun publish(id: Long, state: AppUpdateDownloadState) {
        if (id == requestId) _downloadState.value = state
    }

    private fun cancelActiveLocked() {
        activeCall?.cancel()
        activeCall = null
        downloadJob?.cancel()
        downloadJob = null
        partialFile?.delete()
        partialFile = null
    }

    companion object {
        private const val UPDATES_DIRECTORY = "updates"
        private const val DOWNLOAD_BUFFER_SIZE = 64 * 1024
        private const val PROGRESS_STEP_BYTES = 128 * 1024L
    }
}

@Suppress("DEPRECATION")
private fun PackageManager.archivePackageInfo(file: File): PackageInfo? =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getPackageArchiveInfo(file.absolutePath, PackageManager.PackageInfoFlags.of(0L))
    } else {
        getPackageArchiveInfo(file.absolutePath, 0)
    }

@Suppress("DEPRECATION")
private fun PackageManager.installedPackageInfo(packageName: String): PackageInfo =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
        getPackageInfo(packageName, PackageManager.PackageInfoFlags.of(0L))
    } else {
        getPackageInfo(packageName, 0)
    }

@Suppress("DEPRECATION")
private fun PackageInfo.compatLongVersionCode(): Long =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()

private fun Intent.addNewTaskFlagIfNeeded(context: Context): Intent = apply {
    if (context !is Activity) addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
