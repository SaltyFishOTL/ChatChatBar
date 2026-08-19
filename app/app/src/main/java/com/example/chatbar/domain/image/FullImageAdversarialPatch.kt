package com.example.chatbar.domain.image

import android.graphics.Bitmap

enum class FullImagePatchOperation {
    Apply,
    Restore
}

fun transformFullImageAdversarialPatch(
    bitmap: Bitmap,
    operation: FullImagePatchOperation
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
            yOffset = top
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
    yOffset: Int = 0
) {
    require(width >= 0 && height >= 0 && pixels.size >= width * height)
    val direction = if (operation == FullImagePatchOperation.Apply) 1 else -1
    for (y in 0 until height) {
        for (x in 0 until width) {
            val index = y * width + x
            val color = pixels[index]
            if (color ushr 24 == 0) continue
            val absoluteY = y + yOffset
            val hash = (x / PATCH_BLOCK_SIZE) * 73856093 xor (absoluteY / PATCH_BLOCK_SIZE) * 19349663
            val delta = CHROMA_DELTAS[Math.floorMod(hash, CHROMA_DELTAS.size)]
            val red = ((color shr 16) and 0xff) + delta[0] * direction
            val green = ((color shr 8) and 0xff) + delta[1] * direction
            val blue = (color and 0xff) + delta[2] * direction
            pixels[index] = (color and -0x1000000) or
                (red.coerceIn(0, 255) shl 16) or
                (green.coerceIn(0, 255) shl 8) or
                blue.coerceIn(0, 255)
        }
    }
}

private const val PATCH_BLOCK_SIZE = 3
private const val MAX_TILE_PIXELS = 512 * 1024

private val CHROMA_DELTAS = arrayOf(
    intArrayOf(24, -12, -12),
    intArrayOf(-24, 12, 12),
    intArrayOf(-12, 24, -12),
    intArrayOf(12, -24, 12),
    intArrayOf(-12, -12, 24),
    intArrayOf(12, 12, -24)
)
