package com.example.chatbar.domain.image

import android.content.Context
import java.io.File
import java.util.UUID

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

    private fun safeSegment(value: String): String = value.replace(Regex("[^A-Za-z0-9._-]"), "_")
}
