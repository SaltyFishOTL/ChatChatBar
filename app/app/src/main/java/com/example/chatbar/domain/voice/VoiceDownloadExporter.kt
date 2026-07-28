package com.example.chatbar.domain.voice

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.example.chatbar.data.local.entity.GeneratedVoiceMessage
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

suspend fun saveGeneratedVoiceToDownloads(
    context: Context,
    voice: GeneratedVoiceMessage,
    chatName: String
): String = withContext(Dispatchers.IO) {
    val source = File(voice.audioPath)
    require(source.isFile && source.length() > 0L) { "语音文件不存在或为空" }
    val displayName = buildVoiceDownloadFileName(chatName, voice.characterName)
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
        saveVoiceWithMediaStore(context, source, displayName)
    } else {
        saveVoiceToLegacyDownloads(context, source, displayName)
    }
    displayName
}

private fun saveVoiceWithMediaStore(
    context: Context,
    source: File,
    displayName: String
) {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, displayName)
        put(MediaStore.Downloads.MIME_TYPE, "audio/mpeg")
        put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
        ?: error("无法创建下载文件")
    var completed = false
    try {
        val copiedBytes = source.inputStream().use { input ->
            resolver.openOutputStream(uri, "w")?.use { output ->
                input.copyTo(output)
            } ?: error("无法写入下载文件")
        }
        check(copiedBytes == source.length()) { "下载文件写入不完整" }
        values.clear()
        values.put(MediaStore.Downloads.IS_PENDING, 0)
        resolver.update(uri, values, null, null)
        completed = true
    } finally {
        if (!completed) resolver.delete(uri, null, null)
    }
}

@Suppress("DEPRECATION")
private fun saveVoiceToLegacyDownloads(
    context: Context,
    source: File,
    displayName: String
) {
    check(
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.WRITE_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    ) { "需要存储权限才能写入下载目录" }

    val directory = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
    check(directory.isDirectory || directory.mkdirs()) { "无法访问下载目录" }
    val target = File(directory, displayName)
    check(!target.exists()) { "下载目录已存在同名文件" }
    val temporary = File(directory, ".$displayName.part")
    check(!temporary.exists()) { "下载目录存在未完成的同名文件" }
    try {
        source.copyTo(temporary, overwrite = false)
        check(temporary.length() == source.length()) { "下载文件写入不完整" }
        check(temporary.renameTo(target)) { "无法完成下载文件" }
    } finally {
        if (temporary.exists()) temporary.delete()
    }
    MediaScannerConnection.scanFile(
        context,
        arrayOf(target.absolutePath),
        arrayOf("audio/mpeg"),
        null
    )
}
