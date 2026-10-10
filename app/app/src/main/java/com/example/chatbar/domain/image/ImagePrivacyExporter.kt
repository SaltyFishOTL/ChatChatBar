package com.example.chatbar.domain.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import java.io.File
import java.util.UUID

internal object ImagePrivacyExporter {
    fun stripToCopy(source: File, outputDirectory: File): File {
        return com.example.chatbar.domain.backup.LocalDataMaintenance.access {
            require(source.isFile && source.length() in 1..100L * 1024 * 1024) { "图片文件不存在、为空或超过 100 MB" }
            require(!ApngDisguiseCodec.containsAnimationControl(source)) { "暂不支持动画去除元数据" }
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(source.path, bounds)
            require(bounds.outMimeType in setOf("image/png", "image/jpeg", "image/webp")) { "暂不支持此图片格式去除元数据" }
            // Animated WebP must not silently become a static privacy copy.
            if (bounds.outMimeType == "image/webp") require(!isAnimatedWebP(source)) { "暂不支持动画去除元数据" }
            require(bounds.outWidth > 0 && bounds.outHeight > 0 &&
                bounds.outWidth.toLong() * bounds.outHeight <= PrivacyPngEncoder.MAX_PIXELS) { "图片尺寸过大或无效" }
            val orientation = ImageMetadataStripper.readOrientation(source)
            val bitmap = BitmapFactory.decodeFile(source.path, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
                inPremultiplied = false
            }) ?: error("无法解码图片，未生成去元数据副本")
            return try {
                writeCopy(bitmap, outputDirectory, orientation)
            } finally {
                bitmap.recycle()
            }
        }
    }

    fun writeCopy(bitmap: Bitmap, outputDirectory: File, orientation: Int = 1): File {
        return com.example.chatbar.domain.backup.LocalDataMaintenance.access {
            check(outputDirectory.isDirectory || outputDirectory.mkdirs()) { "无法创建图片处理目录" }
            val target = File(outputDirectory, "metadata_stripped_${UUID.randomUUID()}.png")
            val temporary = File(outputDirectory, "${target.name}.tmp")
            try {
                temporary.outputStream().buffered().use { output ->
                    PrivacyPngEncoder.write(output, bitmap.width, bitmap.height, orientation) { y, row ->
                        bitmap.getPixels(row, 0, bitmap.width, 0, y, bitmap.width, 1)
                    }
                }
                check(temporary.length() > 0 && temporary.renameTo(target)) { "无法保存去元数据副本" }
                return target
            } catch (error: Throwable) {
                temporary.delete()
                target.delete()
                throw error
            }
        }
    }

    private fun isAnimatedWebP(source: File): Boolean = source.inputStream().use { input ->
        val header = ByteArray(21)
        val count = input.read(header)
        count >= 21 && header.copyOfRange(12, 16).toString(Charsets.US_ASCII) == "VP8X" &&
            header[20].toInt() and 0x02 != 0
    }
}
