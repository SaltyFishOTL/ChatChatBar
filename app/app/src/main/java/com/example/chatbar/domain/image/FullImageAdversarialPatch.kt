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

    // 第零层：全局色偏，逐帧变化，破坏整图色彩统计且抗一切重编码
    val globalCast = CAST_DELTAS[Math.floorMod(frameIndex * 31, CAST_DELTAS.size)]

    for (y in 0 until height) {
        val absoluteY = y + yOffset
        for (x in 0 until width) {
            val index = y * width + x
            val color = pixels[index]
            if (color ushr 24 == 0) continue
            var deltaRed = globalCast[0]
            var deltaGreen = globalCast[1]
            var deltaBlue = globalCast[2]

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

            // 第三层：三个低频平滑色波（64/96/128 像素周期），抗缩放，且随帧号移动相位
            val waveA = SINE_A[(x * 3 + absoluteY * 2 + frameIndex * 13) and (SINE_A.size - 1)]
            val waveB = SINE_B[(x * 2 - absoluteY * 3 + frameIndex * 27) and (SINE_B.size - 1)]
            val waveC = SINE_C[(x * 5 + absoluteY * 7 + frameIndex * 41) and (SINE_C.size - 1)]
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
            val waveCmp = DC_DELTAS[
                Math.floorMod(
                    (x / 32) * 12289 xor ((absoluteY - frameIndex * 43) / 32) * 40503,
                    DC_DELTAS.size
                )
            ]
            deltaRed += (waveAmp[0] * waveA + waveBmp[0] * waveB + waveCmp[0] * waveC) / SINE_SCALE
            deltaGreen += (waveAmp[1] * waveA + waveBmp[1] * waveB + waveCmp[1] * waveC) / SINE_SCALE
            deltaBlue += (waveAmp[2] * waveA + waveBmp[2] * waveB + waveCmp[2] * waveC) / SINE_SCALE

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

private val CAST_DELTAS = arrayOf(
    intArrayOf(6, -3, -3),
    intArrayOf(-6, 3, 3),
    intArrayOf(-3, 6, -3),
    intArrayOf(3, -6, 3),
    intArrayOf(-3, -3, 6),
    intArrayOf(3, 3, -6)
)

private val CHROMA_DELTAS = arrayOf(
    intArrayOf(28, -14, -14),
    intArrayOf(-28, 14, 14),
    intArrayOf(-14, 28, -14),
    intArrayOf(14, -28, 14),
    intArrayOf(-14, -14, 28),
    intArrayOf(14, 14, -28)
)

private val DC_DELTAS = arrayOf(
    intArrayOf(14, -7, -7),
    intArrayOf(-14, 7, 7),
    intArrayOf(-7, 14, -7),
    intArrayOf(7, -14, 7),
    intArrayOf(-7, -7, 14),
    intArrayOf(7, 7, -14)
)

private val SINE_A = IntArray(64) { index ->
    Math.round(Math.sin(2 * Math.PI * index / 64) * 8).toInt()
}

private val SINE_B = IntArray(128) { index ->
    Math.round(Math.sin(2 * Math.PI * index / 128) * 6).toInt()
}

private val SINE_C = IntArray(96) { index ->
    Math.round(Math.sin(2 * Math.PI * index / 96) * 6).toInt()
}

private val BAYER_4 = intArrayOf(
    0, 8, 2, 10,
    12, 4, 14, 6,
    3, 11, 1, 9,
    15, 7, 13, 5
)
