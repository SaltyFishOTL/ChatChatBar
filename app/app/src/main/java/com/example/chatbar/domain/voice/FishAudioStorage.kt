package com.example.chatbar.domain.voice

import android.content.Context
import android.media.MediaMetadataRetriever
import java.io.File
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.ResponseBody

class FishAudioStorage(private val context: Context) {
    private val root = File(context.filesDir, "audio/generated")
    private val previewRoot = File(context.cacheDir, "audio/fish_preview")

    suspend fun persistTtsResponse(
        sessionId: String,
        voiceId: String,
        body: ResponseBody,
        onProgress: (FishAudioDownloadProgress.Downloading) -> Unit = {}
    ): FishAudioStoredAudio = withContext(Dispatchers.IO) {
        val directory = File(root, safeSegment(sessionId)).also(File::mkdirs)
        val finalFile = File(directory, "${safeSegment(voiceId)}.mp3")
        val tempFile = File(directory, ".${finalFile.name}.${UUID.randomUUID()}.part")
        try {
            val expected = body.contentLength().takeIf { it >= 0L }
            var received = 0L
            body.byteStream().use { input ->
                tempFile.outputStream().buffered().use { output ->
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    while (true) {
                        currentCoroutineContext().ensureActive()
                        val count = input.read(buffer)
                        if (count < 0) break
                        output.write(buffer, 0, count)
                        received += count
                        onProgress(FishAudioDownloadProgress.Downloading(received, expected))
                    }
                    output.flush()
                }
            }
            require(received > 0L) { "Fish Audio 返回空音频" }
            require(expected == null || received == expected) {
                "Fish Audio 音频下载不完整：$received/$expected 字节"
            }
            val duration = readDuration(tempFile)
            require(duration > 0L) { "Fish Audio 音频无法解析时长" }
            moveReplacing(tempFile, finalFile)
            FishAudioStoredAudio(finalFile.absolutePath, duration, finalFile.length())
        } catch (error: Throwable) {
            tempFile.delete()
            throw error
        }
    }

    suspend fun persistPreview(
        modelId: String,
        body: ResponseBody
    ): File = withContext(Dispatchers.IO) {
        previewRoot.mkdirs()
        val finalFile = File(previewRoot, "${safeSegment(modelId)}.mp3")
        val tempFile = File(previewRoot, ".${finalFile.name}.${UUID.randomUUID()}.part")
        try {
            body.byteStream().use { input ->
                tempFile.outputStream().buffered().use(input::copyTo)
            }
            require(tempFile.length() > 0L) { "音色样本为空" }
            moveReplacing(tempFile, finalFile)
            finalFile
        } catch (error: Throwable) {
            tempFile.delete()
            throw error
        }
    }

    suspend fun restoreArchivedAudio(
        sessionId: String,
        resourceId: String,
        data: ByteArray
    ): FishAudioStoredAudio = withContext(Dispatchers.IO) {
        require(data.isNotEmpty()) { "存档语音资源为空" }
        val directory = File(root, safeSegment(sessionId)).also(File::mkdirs)
        val finalFile = File(
            directory,
            "restored-${safeSegment(resourceId)}-${UUID.randomUUID()}.mp3"
        )
        val tempFile = File(directory, ".${finalFile.name}.${UUID.randomUUID()}.part")
        try {
            tempFile.writeBytes(data)
            val duration = readDuration(tempFile)
            require(duration > 0L) { "存档语音资源无法解析" }
            moveReplacing(tempFile, finalFile)
            FishAudioStoredAudio(finalFile.absolutePath, duration, finalFile.length())
        } catch (error: Throwable) {
            tempFile.delete()
            finalFile.delete()
            throw error
        }
    }

    fun deleteIfOwned(path: String): Boolean = runCatching {
        val file = File(path).canonicalFile
        val ownedRoot = root.canonicalFile
        val owned = file.path.startsWith(ownedRoot.path + File.separator)
        !owned || !file.exists() || file.delete()
    }.getOrDefault(false)

    fun deleteSession(sessionId: String): Boolean = runCatching {
        val directory = File(root, safeSegment(sessionId)).canonicalFile
        val ownedRoot = root.canonicalFile
        if (!directory.path.startsWith(ownedRoot.path + File.separator)) return@runCatching false
        !directory.exists() || directory.deleteRecursively()
    }.getOrDefault(false)

    fun cleanupPartialFiles() {
        root.walkTopDown()
            .filter { it.isFile && it.extension == "part" }
            .forEach(File::delete)
        previewRoot.walkTopDown()
            .filter { it.isFile && it.extension == "part" }
            .forEach(File::delete)
    }

    fun cleanupOrphanFiles(referencedPaths: Set<String>) {
        val referenced = referencedPaths.mapNotNullTo(mutableSetOf()) { path ->
            runCatching { File(path).canonicalPath }.getOrNull()
        }
        root.walkTopDown()
            .filter { it.isFile && it.extension.equals("mp3", ignoreCase = true) }
            .filterNot { runCatching { it.canonicalPath in referenced }.getOrDefault(false) }
            .forEach(File::delete)
    }

    private fun readDuration(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.absolutePath)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } finally {
            retriever.release()
        }
    }

    private fun moveReplacing(source: File, target: File) {
        try {
            Files.move(
                source.toPath(),
                target.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
    }

    private fun safeSegment(value: String): String =
        value.replace(Regex("[^A-Za-z0-9._-]"), "_").ifBlank { "voice" }
}
