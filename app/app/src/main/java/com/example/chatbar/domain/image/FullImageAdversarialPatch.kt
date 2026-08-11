package com.example.chatbar.domain.image

import android.graphics.Bitmap

enum class FullImagePatchOperation {
    Apply,
    Restore
}

fun transformFullImageAdversarialPatch(
    bitmap: Bitmap,
    operation: FullImagePatchOperation,
    frameIndex: Int = 0
) {
    val rowsPerTile = (MAX_TILE_PIXELS / bitmap.width.coerceAtLeast(1)).coerceAtLeast(1)
    var top = 0
    while (top < bitmap.height) {
        val rowCount = rowsPerTile.coerceAtMost(bitmap.height - top)
        val pixels = IntArray(bitmap.width * rowCount)
        bitmap.getPixels(pixels, 0, bitmap.width, 0, top, bitmap.width, rowCount)
        transformFullImageAdversarialPatchPixels(
            pixels = pixels,
            width = bitmap.width,
            height = rowCount,
            operation = operation,
            yOffset = top,
            frameIndex = frameIndex
        )
        bitmap.setPixels(pixels, 0, bitmap.width, 0, top, bitmap.width, rowCount)
        top += rowCount
    }
}

internal fun transformFullImageAdversarialPatchPixels(
    pixels: IntArray,
    width: Int,
    height: Int,
    operation: FullImagePatchOperation,
    yOffset: Int = 0,
    frameIndex: Int = 0
) {
    require(width >= 0 && height >= 0 && pixels.size >= width * height)
    val direction = if (operation == FullImagePatchOperation.Apply) 1 else -1
    for (y in 0 until height) {
        val absoluteY = y + yOffset
        for (x in 0 until width) {
            val index = y * width + x
            val color = pixels[index]
            if (color ushr 24 == 0) continue
            var deltaRed = 0
            var deltaGreen = 0
            var deltaBlue = 0

            // 第一层：3×3 块色度偏移，破坏像素级局部结构
            val chroma = CHROMA_DELTAS[
                Math.floorMod(
                    (x / PATCH_BLOCK_SIZE) * 73856093 xor (absoluteY / PATCH_BLOCK_SIZE) * 19349663,
                    CHROMA_DELTAS.size
                )
            ]
            deltaRed += chroma[0]
            deltaGreen += chroma[1]
            deltaBlue += chroma[2]

            // 第二层：8×8 块 DC 色偏，对齐 JPEG/DCT 块，抗重压缩
            val dc = DC_DELTAS[
                Math.floorMod(
                    (x / DC_BLOCK_SIZE) * 40503 xor (absoluteY / DC_BLOCK_SIZE) * 99147,
                    DC_DELTAS.size
                )
            ]
            deltaRed += dc[0]
            deltaGreen += dc[1]
            deltaBlue += dc[2]

            // 第三层：低频平滑色波，抗缩放，且随帧号移动相位
            val waveA = SINE_A[(x * 3 + absoluteY * 2 + frameIndex * 5) and (SINE_A.size - 1)]
            val waveB = SINE_B[(x * 2 - absoluteY * 3 + frameIndex * 9) and (SINE_B.size - 1)]
            val waveAmp = DC_DELTAS[
                Math.floorMod(
                    (x / 16) * 12289 xor ((absoluteY + frameIndex * 31) / 16) * 27061,
                    DC_DELTAS.size
                )
            ]
            val waveBmp = DC_DELTAS[
                Math.floorMod(
                    (x / 32) * 40503 xor ((absoluteY - frameIndex * 17) / 32) * 99147,
                    DC_DELTAS.size
                )
            ]
            deltaRed += (waveAmp[0] * waveA + waveBmp[0] * waveB) / SINE_SCALE
            deltaGreen += (waveAmp[1] * waveA + waveBmp[1] * waveB) / SINE_SCALE
            deltaBlue += (waveAmp[2] * waveA + waveBmp[2] * waveB) / SINE_SCALE

            // 第四层：Bayer 有序抖动，打散 GIF 调色板色带
            val jitter = (BAYER_4[(absoluteY and 3) * 4 + (x and 3)] - 7) / 2
            deltaRed += jitter
            deltaGreen += jitter
            deltaBlue += jitter

            val red = ((color shr 16) and 0xff) + deltaRed * direction
            val green = ((color shr 8) and 0xff) + deltaGreen * direction
            val blue = (color and 0xff) + deltaBlue * direction
            pixels[index] = (color and -0x1000000) or
                (red.coerceIn(0, 255) shl 16) or
                (green.coerceIn(0, 255) shl 8) or
                blue.coerceIn(0, 255)
        }
    }
}

private const val PATCH_BLOCK_SIZE = 3
private const val DC_BLOCK_SIZE = 8
private const val SINE_SCALE = 10
private const val MAX_TILE_PIXELS = 512 * 1024

private val CHROMA_DELTAS = arrayOf(
    intArrayOf(24, -12, -12),
    intArrayOf(-24, 12, 12),
    intArrayOf(-12, 24, -12),
    intArrayOf(12, -24, 12),
    intArrayOf(-12, -12, 24),
    intArrayOf(12, 12, -24)
)

private val DC_DELTAS = arrayOf(
    intArrayOf(8, -4, -4),
    intArrayOf(-8, 4, 4),
    intArrayOf(-4, 8, -4),
    intArrayOf(4, -8, 4),
    intArrayOf(-4, -4, 8),
    intArrayOf(4, 4, -8)
)

private val SINE_A = IntArray(64) { index ->
    Math.round(Math.sin(2 * Math.PI * index / 64) * 8).toInt()
}

private val SINE_B = IntArray(128) { index ->
    Math.round(Math.sin(2 * Math.PI * index / 128) * 6).toInt()
}

private val BAYER_4 = intArrayOf(
    0, 8, 2, 10,
    12, 4, 14, 6,
    3, 11, 1, 9,
    15, 7, 13, 5
)
