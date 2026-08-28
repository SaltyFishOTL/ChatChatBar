package com.example.chatbar.domain.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import java.io.ByteArrayOutputStream
import java.io.File

object NovelAiInpaintResultComposer {
    fun compose(
        generatedPng: ByteArray,
        baseImage: NovelAiStudioAssetRef,
        focusedPlan: NovelAiFocusedInpaintPlan,
        blendMaskAlpha: ByteArray
    ): ByteArray {
        val generated = BitmapFactory.decodeByteArray(generatedPng, 0, generatedPng.size)
            ?: error("Inpaint 结果无法解码")
        val base = BitmapFactory.decodeFile(File(baseImage.path).absolutePath)
            ?: error("Inpaint 基图无法解码")
        require(
            generated.width == focusedPlan.requestSize.width &&
                generated.height == focusedPlan.requestSize.height
        ) {
            "Focused Inpainting 结果与请求尺寸不一致"
        }
        require(blendMaskAlpha.size == focusedPlan.requestSize.width * focusedPlan.requestSize.height) {
            "Focused Inpainting 羽化蒙版尺寸不一致"
        }
        require(
            focusedPlan.sourceWidth == base.width && focusedPlan.sourceHeight == base.height &&
                focusedPlan.crop.right <= base.width && focusedPlan.crop.bottom <= base.height
        ) { "Focused Inpainting 聚焦区域与基图不匹配" }
        val scaledGenerated = NovelAiLanczos3Resampler.resize(
            generated,
            focusedPlan.crop.width,
            focusedPlan.crop.height
        )
        val requestBlendMask = Bitmap.createBitmap(
            focusedPlan.requestSize.width,
            focusedPlan.requestSize.height,
            Bitmap.Config.ARGB_8888
        )
        val requestMaskPixels = IntArray(blendMaskAlpha.size) { index ->
            ((blendMaskAlpha[index].toInt() and 0xff) shl 24) or 0x00ffffff
        }
        requestBlendMask.setPixels(
            requestMaskPixels,
            0,
            focusedPlan.requestSize.width,
            0,
            0,
            focusedPlan.requestSize.width,
            focusedPlan.requestSize.height
        )
        val scaledBlendMask = NovelAiLanczos3Resampler.resize(
            requestBlendMask,
            focusedPlan.crop.width,
            focusedPlan.crop.height
        )
        val output = Bitmap.createBitmap(base.width, base.height, Bitmap.Config.ARGB_8888)
        return try {
            val size = base.width * base.height
            val basePixels = IntArray(size)
            base.getPixels(basePixels, 0, base.width, 0, 0, base.width, base.height)
            val generatedPixels = IntArray(focusedPlan.crop.width * focusedPlan.crop.height)
            scaledGenerated.getPixels(
                generatedPixels,
                0,
                focusedPlan.crop.width,
                0,
                0,
                focusedPlan.crop.width,
                focusedPlan.crop.height
            )
            val blendPixels = IntArray(generatedPixels.size)
            scaledBlendMask.getPixels(
                blendPixels,
                0,
                focusedPlan.crop.width,
                0,
                0,
                focusedPlan.crop.width,
                focusedPlan.crop.height
            )
            val composed = basePixels.copyOf()
            for (y in 0 until focusedPlan.crop.height) {
                for (x in 0 until focusedPlan.crop.width) {
                    val outputIndex = (focusedPlan.crop.top + y) * base.width + focusedPlan.crop.left + x
                    val generatedIndex = y * focusedPlan.crop.width + x
                    composed[outputIndex] = composeOfficialPixel(
                        basePixels[outputIndex],
                        generatedPixels[generatedIndex],
                        blendPixels[generatedIndex] ushr 24 and 0xff
                    )
                }
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
            if (scaledGenerated !== generated) scaledGenerated.recycle()
            requestBlendMask.recycle()
            if (scaledBlendMask !== requestBlendMask) scaledBlendMask.recycle()
            base.recycle()
        }
    }

    /** Mirrors NovelAI web: destination-out(base, mask), then lighter(generated, mask). */
    internal fun composeOfficialPixel(base: Int, generated: Int, maskWeight: Int): Int {
        val baseAlpha = base ushr 24 and 0xff
        val generatedAlpha = generated ushr 24 and 0xff
        val mask = maskWeight.coerceIn(0, 255)
        if (mask <= 0) return base
        val clearedBaseAlpha = (baseAlpha * (255 - mask) + 127) / 255
        val appliedGeneratedAlpha = (generatedAlpha * mask + 127) / 255
        val outputAlpha = (clearedBaseAlpha + appliedGeneratedAlpha).coerceAtMost(255)
        if (outputAlpha <= 0) return 0
        fun channel(pixel: Int, shift: Int): Int = pixel ushr shift and 0xff
        fun composed(shift: Int): Int {
            val premultiplied = (
                channel(base, shift) * clearedBaseAlpha +
                    channel(generated, shift) * appliedGeneratedAlpha +
                    127
                ) / 255
            return ((premultiplied * 255 + outputAlpha / 2) / outputAlpha).coerceIn(0, 255)
        }
        return (outputAlpha shl 24) or
            (composed(16) shl 16) or
            (composed(8) shl 8) or
            composed(0)
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
}
