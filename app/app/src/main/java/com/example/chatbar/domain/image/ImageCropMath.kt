package com.example.chatbar.domain.image

import kotlin.math.max

data class ImageCropSize(
    val width: Float,
    val height: Float
)

data class ImageCropOffset(
    val x: Float,
    val y: Float
)

data class ImageCropFractionRect(
    val left: Float,
    val top: Float,
    val right: Float,
    val bottom: Float
) {
    val width: Float get() = right - left
    val height: Float get() = bottom - top
}

fun coverDisplaySize(
    sourceWidth: Float,
    sourceHeight: Float,
    frameWidth: Float,
    frameHeight: Float
): ImageCropSize {
    require(sourceWidth > 0f && sourceHeight > 0f) { "source size must be positive" }
    require(frameWidth > 0f && frameHeight > 0f) { "frame size must be positive" }
    val scale = max(frameWidth / sourceWidth, frameHeight / sourceHeight)
    return ImageCropSize(sourceWidth * scale, sourceHeight * scale)
}

fun clampCropOffset(
    offset: ImageCropOffset,
    sourceWidth: Float,
    sourceHeight: Float,
    frameWidth: Float,
    frameHeight: Float,
    userScale: Float
): ImageCropOffset {
    val display = coverDisplaySize(sourceWidth, sourceHeight, frameWidth, frameHeight)
    val scale = userScale.coerceAtLeast(1f)
    val maxX = max(0f, (display.width * scale - frameWidth) / 2f)
    val maxY = max(0f, (display.height * scale - frameHeight) / 2f)
    return ImageCropOffset(
        x = offset.x.coerceIn(-maxX, maxX),
        y = offset.y.coerceIn(-maxY, maxY)
    )
}

fun imageCropFractionRect(
    sourceWidth: Float,
    sourceHeight: Float,
    frameWidth: Float,
    frameHeight: Float,
    userScale: Float,
    offset: ImageCropOffset
): ImageCropFractionRect {
    val display = coverDisplaySize(sourceWidth, sourceHeight, frameWidth, frameHeight)
    val scale = userScale.coerceAtLeast(1f)
    val drawnWidth = display.width * scale
    val drawnHeight = display.height * scale
    val drawnLeft = (frameWidth - drawnWidth) / 2f + offset.x
    val drawnTop = (frameHeight - drawnHeight) / 2f + offset.y
    return ImageCropFractionRect(
        left = ((0f - drawnLeft) / drawnWidth).coerceIn(0f, 1f),
        top = ((0f - drawnTop) / drawnHeight).coerceIn(0f, 1f),
        right = ((frameWidth - drawnLeft) / drawnWidth).coerceIn(0f, 1f),
        bottom = ((frameHeight - drawnTop) / drawnHeight).coerceIn(0f, 1f)
    )
}
