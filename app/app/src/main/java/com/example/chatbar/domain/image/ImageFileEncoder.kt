package com.example.chatbar.domain.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File

object ImageFileEncoder {
    suspend fun encodeToJpegBase64(path: String): String = withContext(Dispatchers.IO) {
        val file = File(path)
        if (!file.exists()) throw IllegalArgumentException("图片文件不存在: $path")
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            ?: throw IllegalArgumentException("图片文件无法读取: $path")
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
        Base64.encodeToString(outputStream.toByteArray(), Base64.NO_WRAP)
    }
}
