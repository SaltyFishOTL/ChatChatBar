package com.example.chatbar.domain.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import java.io.ByteArrayOutputStream
import java.io.File

object NovelAiInpaintResultComposer {
    fun compose(
        generatedPng: ByteArray,
        baseImage: NovelAiStudioAssetRef,
        originalMask: NovelAiStudioAssetRef
    ): ByteArray {
        val generated = BitmapFactory.decodeByteArray(generatedPng, 0, generatedPng.size)
            ?: error("Inpaint 结果无法解码")
        val base = BitmapFactory.decodeFile(File(baseImage.path).absolutePath)
            ?: error("Inpaint 基图无法解码")
        val mask = BitmapFactory.decodeFile(File(originalMask.path).absolutePath)
            ?: error("Inpaint 蒙版无法解码")
        require(generated.width == base.width && generated.height == base.height) {
            "Inpaint 结果与基图尺寸不一致"
        }
        require(mask.width == base.width && mask.height == base.height) {
            "Inpaint 蒙版与基图尺寸不一致"
        }
        val output = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        return try {
            val size = base.width * base.height
            val generatedPixels = IntArray(size)
            val basePixels = IntArray(size)
            generated.getPixels(generatedPixels, 0, base.width, 0, 0, base.width, base.height)
            base.getPixels(basePixels, 0, base.width, 0, 0, base.width, base.height)
            val maskPixels = IntArray(size)
            mask.getPixels(maskPixels, 0, base.width, 0, 0, base.width, base.height)
            val selection = BooleanArray(size) { index ->
                val color = maskPixels[index]
                val intensity = (Color.red(color) + Color.green(color) + Color.blue(color)) / 3
                Color.alpha(color) >= 128 && intensity >= MASK_THRESHOLD
            }
            val weights = inwardFeather(selection, base.width, base.height, FEATHER_RADIUS)
            val composed = IntArray(size) { index ->
                blendOpaque(basePixels[index], generatedPixels[index], weights[index])
            }
            output.setPixels(composed, 0, base.width, 0, 0, base.width, base.height)
            val encoded = ByteArrayOutputStream().use { stream ->
                check(output.compress(Bitmap.CompressFormat.PNG, 100, stream)) {
                    "Inpaint 结果合成失败"
                }
                stream.toByteArray()
            }
            preserveTextChunks(encoded, generatedPng)
        } finally {
            output.recycle()
            generated.recycle()
            base.recycle()
            mask.recycle()
        }
    }

    private fun inwardFeather(
        selection: BooleanArray,
        width: Int,
        height: Int,
        radius: Int
    ): IntArray {
        val infinite = Int.MAX_VALUE / 4
        val distance = IntArray(selection.size) { index -> if (selection[index]) infinite else 0 }
        for (y in 0 until height) {
            for (x in 0 until width) {
                val index = y * width + x
                if (!selection[index]) continue
                var value = distance[index]
                if (x > 0) value = minOf(value, distance[index - 1] + CARDINAL_COST)
                if (y > 0) value = minOf(value, distance[index - width] + CARDINAL_COST)
                if (x > 0 && y > 0) value = minOf(value, distance[index - width - 1] + DIAGONAL_COST)
                if (x + 1 < width && y > 0) value = minOf(value, distance[index - width + 1] + DIAGONAL_COST)
                distance[index] = value
            }
        }
        for (y in height - 1 downTo 0) {
            for (x in width - 1 downTo 0) {
                val index = y * width + x
                if (!selection[index]) continue
                var value = distance[index]
                if (x + 1 < width) value = minOf(value, distance[index + 1] + CARDINAL_COST)
                if (y + 1 < height) value = minOf(value, distance[index + width] + CARDINAL_COST)
                if (x + 1 < width && y + 1 < height) {
                    value = minOf(value, distance[index + width + 1] + DIAGONAL_COST)
                }
                if (x > 0 && y + 1 < height) {
                    value = minOf(value, distance[index + width - 1] + DIAGONAL_COST)
                }
                distance[index] = value
            }
        }
        val edgeGuardDistance = EDGE_GUARD_PIXELS * CARDINAL_COST
        val featherDistance = radius * CARDINAL_COST
        return IntArray(selection.size) { index ->
            if (!selection[index]) {
                0
            } else if (distance[index] >= infinite || distance[index] >= edgeGuardDistance + featherDistance) {
                255
            } else {
                val linear = ((distance[index] - edgeGuardDistance).coerceAtLeast(0)).toFloat() / featherDistance
                val smooth = linear * linear * linear * (linear * (linear * 6f - 15f) + 10f)
                (smooth * 255f).toInt().coerceIn(0, 255)
            }
        }
    }

    private fun blendOpaque(base: Int, generated: Int, weight: Int): Int {
        if (weight <= 0) return Color.rgb(Color.red(base), Color.green(base), Color.blue(base))
        if (weight >= 255) return Color.rgb(Color.red(generated), Color.green(generated), Color.blue(generated))
        val inverse = 255 - weight
        return Color.rgb(
            (Color.red(base) * inverse + Color.red(generated) * weight + 127) / 255,
            (Color.green(base) * inverse + Color.green(generated) * weight + 127) / 255,
            (Color.blue(base) * inverse + Color.blue(generated) * weight + 127) / 255
        )
    }

    private fun preserveTextChunks(encoded: ByteArray, source: ByteArray): ByteArray {
        val chunks = rawTextChunks(source)
        if (chunks.isEmpty()) return encoded
        val iend = findChunkOffset(encoded, "IEND") ?: return encoded
        return ByteArrayOutputStream(encoded.size + chunks.sumOf(ByteArray::size)).use { output ->
            output.write(encoded, 0, iend)
            chunks.forEach(output::write)
            output.write(encoded, iend, encoded.size - iend)
            output.toByteArray()
        }
    }

    private fun rawTextChunks(bytes: ByteArray): List<ByteArray> {
        if (!isPng(bytes)) return emptyList()
        val result = mutableListOf<ByteArray>()
        var offset = PNG_SIGNATURE_SIZE
        while (offset + CHUNK_OVERHEAD <= bytes.size) {
            val length = readInt(bytes, offset)
            if (length < 0 || offset + CHUNK_OVERHEAD.toLong() + length > bytes.size) break
            val end = offset + CHUNK_OVERHEAD + length
            val type = bytes.copyOfRange(offset + 4, offset + 8).toString(Charsets.US_ASCII)
            if (type in TEXT_CHUNK_TYPES) result += bytes.copyOfRange(offset, end)
            offset = end
            if (type == "IEND") break
        }
        return result
    }

    private fun findChunkOffset(bytes: ByteArray, target: String): Int? {
        if (!isPng(bytes)) return null
        var offset = PNG_SIGNATURE_SIZE
        while (offset + CHUNK_OVERHEAD <= bytes.size) {
            val length = readInt(bytes, offset)
            if (length < 0 || offset + CHUNK_OVERHEAD.toLong() + length > bytes.size) return null
            val type = bytes.copyOfRange(offset + 4, offset + 8).toString(Charsets.US_ASCII)
            if (type == target) return offset
            offset += CHUNK_OVERHEAD + length
        }
        return null
    }

    private fun isPng(bytes: ByteArray): Boolean = bytes.size >= PNG_SIGNATURE_SIZE &&
        bytes.copyOfRange(0, PNG_SIGNATURE_SIZE).contentEquals(PNG_SIGNATURE)

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)

    private val PNG_SIGNATURE = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    private val TEXT_CHUNK_TYPES = setOf("tEXt", "zTXt", "iTXt")
    private const val PNG_SIGNATURE_SIZE = 8
    private const val CHUNK_OVERHEAD = 12
    private const val MASK_THRESHOLD = 128
    private const val EDGE_GUARD_PIXELS = 10
    private const val FEATHER_RADIUS = 32
    private const val CARDINAL_COST = 3
    private const val DIAGONAL_COST = 4
}
