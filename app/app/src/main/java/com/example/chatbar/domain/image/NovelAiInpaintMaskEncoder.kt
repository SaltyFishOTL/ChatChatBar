package com.example.chatbar.domain.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File

object NovelAiInpaintMaskEncoder {
    fun encodeBinaryPngBase64(asset: NovelAiStudioAssetRef): String {
        val source = File(asset.path)
        require(source.isFile) { "Inpaint 蒙版文件不存在" }
        val bitmap = BitmapFactory.decodeFile(source.absolutePath)
            ?: error("Inpaint 蒙版无法解码")
        try {
            return encode(bitmap, threshold = 128)
        } finally {
            bitmap.recycle()
        }
    }

    fun encodeApiMaskBase64(asset: NovelAiStudioAssetRef): String {
        val source = File(asset.path)
        require(source.isFile) { "Inpaint 蒙版文件不存在" }
        val bitmap = BitmapFactory.decodeFile(source.absolutePath)
            ?: error("Inpaint 蒙版无法解码")
        val resized = Bitmap.createScaledBitmap(
            bitmap,
            (bitmap.width / LATENT_SCALE).coerceAtLeast(1),
            (bitmap.height / LATENT_SCALE).coerceAtLeast(1),
            false
        )
        return try {
            encode(resized, threshold = API_THRESHOLD)
        } finally {
            if (resized !== bitmap) resized.recycle()
            bitmap.recycle()
        }
    }

    private fun encode(bitmap: Bitmap, threshold: Int): String {
        val normalized = normalize(bitmap, threshold)
        return try {
            encodePng(normalized)
        } finally {
            normalized.recycle()
        }
    }

    private fun normalize(bitmap: Bitmap, threshold: Int): Bitmap {
        val sourcePixels = IntArray(bitmap.width * bitmap.height)
        bitmap.getPixels(sourcePixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        val outputPixels = IntArray(sourcePixels.size) { index ->
            val color = sourcePixels[index]
            val intensity = (Color.red(color) + Color.green(color) + Color.blue(color)) / 3
            if (Color.alpha(color) >= 128 && intensity >= threshold) Color.WHITE else Color.BLACK
        }
        val normalized = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ARGB_8888)
        normalized.setPixels(outputPixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
        return normalized
    }

    private fun encodePng(bitmap: Bitmap): String {
        val output = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "Inpaint 蒙版 PNG 编码失败" }
        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }

    private const val LATENT_SCALE = 8
    private const val API_THRESHOLD = 155
}
