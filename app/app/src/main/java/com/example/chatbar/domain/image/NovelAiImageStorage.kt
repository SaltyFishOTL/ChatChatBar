package com.example.chatbar.domain.image

import android.content.Context
import java.io.File
import java.util.UUID

class NovelAiStagedSessionDeletion internal constructor(
    internal val original: File,
    internal val staged: File
)

class NovelAiStagedImageDeletion internal constructor(
    internal val original: File,
    internal val staged: File
)

class NovelAiImageStorage(private val context: Context) {
    private val root: File get() = File(context.filesDir, "images/generated")

    fun save(sessionId: String, bytes: ByteArray): String {
        val directory = File(root, safeSegment(sessionId)).also(File::mkdirs)
        val file = File(directory, "${UUID.randomUUID()}.png")
        file.writeBytes(bytes)
        return file.absolutePath
    }

    fun deleteIfOwned(path: String): Boolean {
        val file = File(path)
        val owned = file.canonicalPath.startsWith(root.canonicalPath + File.separator)
        return owned && (!file.exists() || file.delete())
    }

    fun deleteSession(sessionId: String): Boolean {
        val directory = File(root, safeSegment(sessionId))
        val owned = directory.canonicalPath.startsWith(root.canonicalPath + File.separator)
        return owned && (!directory.exists() || directory.deleteRecursively())
    }

    fun stageSessionDelete(sessionId: String): NovelAiStagedSessionDeletion? {
        val directory = File(root, safeSegment(sessionId))
        val owned = directory.canonicalPath.startsWith(root.canonicalPath + File.separator)
        check(owned) { "拒绝暂存非应用所有图片目录" }
        if (!directory.exists()) return null
        val staged = File(root, ".delete-${safeSegment(sessionId)}-${UUID.randomUUID()}")
        check(directory.renameTo(staged)) { "无法暂存图片目录" }
        return NovelAiStagedSessionDeletion(directory, staged)
    }

    fun restoreStagedSession(deletion: NovelAiStagedSessionDeletion): Boolean =
        !deletion.staged.exists() || deletion.staged.renameTo(deletion.original)

    fun commitStagedSessionDelete(deletion: NovelAiStagedSessionDeletion): Boolean =
        !deletion.staged.exists() || deletion.staged.deleteRecursively()

    fun stageImageDelete(path: String): NovelAiStagedImageDeletion? {
        val file = File(path)
        val owned = file.canonicalPath.startsWith(root.canonicalPath + File.separator)
        check(owned) { "拒绝暂存非应用所有图片" }
        if (!file.exists()) return null
        val staged = File(file.parentFile, ".delete-${file.name}-${UUID.randomUUID()}")
        check(file.renameTo(staged)) { "无法暂存图片 ${file.name}" }
        return NovelAiStagedImageDeletion(file, staged)
    }

    fun restoreStagedImage(deletion: NovelAiStagedImageDeletion): Boolean =
        !deletion.staged.exists() || deletion.staged.renameTo(deletion.original)

    fun commitStagedImageDelete(deletion: NovelAiStagedImageDeletion): Boolean {
        val deleted = !deletion.staged.exists() || deletion.staged.delete()
        if (deleted) deletion.original.parentFile?.takeIf { it.listFiles().isNullOrEmpty() }?.delete()
        return deleted
    }

    private fun safeSegment(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
