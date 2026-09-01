package com.example.chatbar.ui.components

import android.content.ClipData
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.widget.Toast
import androidx.core.content.FileProvider
import com.example.chatbar.domain.image.ApngDisguiseCodec
import java.io.File

fun shareImage(context: Context, path: String, chooserTitle: String = "分享图片") {
    try {
        val sourceFile = File(path)
        if (!sourceFile.exists()) {
            Toast.makeText(context, "图片文件不存在", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            sourceFile
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = imageMimeType(sourceFile)
            putExtra(Intent.EXTRA_STREAM, uri)
            clipData = ClipData.newUri(context.contentResolver, "ChatBar image", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, chooserTitle))
    } catch (error: Exception) {
        Toast.makeText(context, "分享失败: ${error.message}", Toast.LENGTH_SHORT).show()
    }
}

fun shareFromImageLongPress(context: Context, path: String) {
    val sourceFile = File(path)
    if (!sourceFile.exists()) {
        Toast.makeText(context, "图片文件不存在", Toast.LENGTH_SHORT).show()
        return
    }
    if (!shouldShareAsFile(sourceFile)) {
        shareImage(context, path)
        return
    }
    try {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            sourceFile
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "application/octet-stream"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TITLE, sourceFile.name)
            clipData = ClipData.newUri(context.contentResolver, "ChatBar file", uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        context.startActivity(Intent.createChooser(shareIntent, "分享文件"))
    } catch (error: Exception) {
        Toast.makeText(context, "文件分享失败: ${error.message}", Toast.LENGTH_SHORT).show()
    }
}

fun saveImageToGallery(
    context: Context,
    path: String,
    displayName: String? = null
) {
    try {
        val sourceFile = File(path)
        if (!sourceFile.exists()) {
            Toast.makeText(context, "图片文件不存在", Toast.LENGTH_SHORT).show()
            return
        }
        val fileName = displayName?.takeIf { it.isNotBlank() } ?: sourceFile.name
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Images.Media.DISPLAY_NAME, fileName)
                put(MediaStore.Images.Media.MIME_TYPE, imageMimeType(sourceFile))
                put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/ChatBar")
                put(MediaStore.Images.Media.IS_PENDING, 1)
            }
            val uri = context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
            if (uri != null) {
                context.contentResolver.openOutputStream(uri)?.use { output ->
                    sourceFile.inputStream().use { input -> input.copyTo(output) }
                }
                values.clear()
                values.put(MediaStore.Images.Media.IS_PENDING, 0)
                context.contentResolver.update(uri, values, null, null)
                Toast.makeText(context, "已保存到 Pictures/ChatBar", Toast.LENGTH_SHORT).show()
            } else {
                Toast.makeText(context, "保存失败", Toast.LENGTH_SHORT).show()
            }
        } else {
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "ChatBar")
            dir.mkdirs()
            val target = File(dir, fileName)
            sourceFile.copyTo(target, overwrite = true)
            MediaScannerConnection.scanFile(
                context,
                arrayOf(target.absolutePath),
                arrayOf(imageMimeType(target)),
                null
            )
            Toast.makeText(context, "已保存到 Pictures/ChatBar", Toast.LENGTH_SHORT).show()
        }
    } catch (error: Exception) {
        Toast.makeText(context, "保存失败: ${error.message}", Toast.LENGTH_SHORT).show()
    }
}

private fun imageMimeType(file: File): String = when (file.extension.lowercase()) {
    "jpg", "jpeg" -> "image/jpeg"
    "webp" -> "image/webp"
    "gif" -> "image/gif"
    "apng" -> "image/png"
    else -> "image/png"
}

internal fun shouldShareAsFile(file: File): Boolean {
    val extension = file.extension.lowercase()
    if (extension == "apng" || extension == "gif") return true
    return ApngDisguiseCodec.containsAnimationControl(file) || file.hasGifSignature()
}

private fun File.hasGifSignature(): Boolean = runCatching {
    inputStream().buffered().use { input ->
        val signature = ByteArray(6)
        input.read(signature) == signature.size &&
            (signature.contentEquals("GIF87a".toByteArray()) || signature.contentEquals("GIF89a".toByteArray()))
    }
}.getOrDefault(false)
