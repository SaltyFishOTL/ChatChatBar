package com.example.chatbar.domain.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Base64
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.floor
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class NovelAiPixelBounds(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int
) {
    init {
        require(left >= 0 && top >= 0 && right > left && bottom > top) { "无效像素区域" }
    }

    val width: Int get() = right - left
    val height: Int get() = bottom - top

    fun contains(other: NovelAiPixelBounds): Boolean =
        left <= other.left && top <= other.top && right >= other.right && bottom >= other.bottom
}

data class NovelAiFocusedInpaintPlan(
    val sourceWidth: Int,
    val sourceHeight: Int,
    val crop: NovelAiPixelBounds,
    val minimumContextPixels: Int,
    val requestSize: NovelAiImageSize
) {
    val innerMaskBounds: NovelAiPixelBounds
        get() = NovelAiPixelBounds(
            minimumContextPixels,
            minimumContextPixels,
            crop.width - minimumContextPixels,
            crop.height - minimumContextPixels
        )
}

data class NovelAiFocusedInpaintRequest(
    val plan: NovelAiFocusedInpaintPlan,
    val imageBase64: String,
    val maskBase64: String,
    val blendMaskAlpha: ByteArray
)

object NovelAiFocusedInpaintPlanner {
    fun plan(
        sourceWidth: Int,
        sourceHeight: Int,
        region: NovelAiFocusedInpaintRegion,
        minimumContextPixels: Int
    ): NovelAiFocusedInpaintPlan {
        require(region.isValid) { "Focused Inpainting 聚焦区域无效" }
        val left = (region.x * sourceWidth).roundToInt().coerceIn(0, sourceWidth - 1)
        val top = (region.y * sourceHeight).roundToInt().coerceIn(0, sourceHeight - 1)
        val right = ((region.x + region.width) * sourceWidth).roundToInt().coerceIn(left + 1, sourceWidth)
        val bottom = ((region.y + region.height) * sourceHeight).roundToInt().coerceIn(top + 1, sourceHeight)
        return plan(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            focusBounds = NovelAiPixelBounds(left, top, right, bottom),
            minimumContextPixels = minimumContextPixels
        )
    }

    fun plan(
        sourceWidth: Int,
        sourceHeight: Int,
        focusBounds: NovelAiPixelBounds,
        minimumContextPixels: Int = DEFAULT_MINIMUM_CONTEXT_PIXELS
    ): NovelAiFocusedInpaintPlan {
        require(sourceWidth > 0 && sourceHeight > 0) { "Focused Inpainting 基图尺寸无效" }
        require(focusBounds.right <= sourceWidth && focusBounds.bottom <= sourceHeight) {
            "Focused Inpainting 聚焦区域超出基图"
        }
        require(minimumContextPixels in MINIMUM_CONTEXT_PIXELS..MAXIMUM_CONTEXT_PIXELS) {
            "Minimum Context 必须在 $MINIMUM_CONTEXT_PIXELS–$MAXIMUM_CONTEXT_PIXELS 像素之间"
        }
        require(focusBounds.width > minimumContextPixels * 2 && focusBounds.height > minimumContextPixels * 2) {
            "Focused Inpainting 聚焦区域必须大于 Minimum Context 边界"
        }
        require(focusBounds.width.toLong() * focusBounds.height <= MAX_FOCUSED_SOURCE_PIXELS) {
            "Focused Inpainting 聚焦区域超过官方面积上限"
        }
        return NovelAiFocusedInpaintPlan(
            sourceWidth = sourceWidth,
            sourceHeight = sourceHeight,
            crop = focusBounds,
            minimumContextPixels = minimumContextPixels,
            requestSize = requestSize(focusBounds.width, focusBounds.height)
        )
    }

    private fun requestSize(cropWidth: Int, cropHeight: Int): NovelAiImageSize {
        val pixels = cropWidth.toLong() * cropHeight
        val scale = sqrt(MAX_REQUEST_PIXELS.toDouble() / pixels)
        val width = (floor(floor(cropWidth * scale) / DIMENSION_STEP).toInt() * DIMENSION_STEP)
            .coerceAtLeast(DIMENSION_STEP)
        val height = (floor(floor(cropHeight * scale) / DIMENSION_STEP).toInt() * DIMENSION_STEP)
            .coerceAtLeast(DIMENSION_STEP)
        require(width.toLong() * height <= MAX_REQUEST_PIXELS) {
            "Focused Inpainting 请求尺寸超过官方上限"
        }
        return NovelAiImageSize(width, height, "Focused Inpainting")
    }

    const val MAX_REQUEST_PIXELS = 1_048_576L
    const val MAX_FOCUSED_SOURCE_PIXELS = 589_824L
    const val DEFAULT_MINIMUM_CONTEXT_PIXELS = 96
    const val MINIMUM_CONTEXT_PIXELS = 32
    const val MAXIMUM_CONTEXT_PIXELS = 96
    private const val DIMENSION_STEP = 64
}

object NovelAiFocusedInpaintProcessor {
    fun prepare(
        baseImage: NovelAiStudioAssetRef,
        originalMask: NovelAiStudioAssetRef?,
        region: NovelAiFocusedInpaintRegion,
        minimumContextPixels: Int
    ): NovelAiFocusedInpaintRequest {
        val base = BitmapFactory.decodeFile(File(baseImage.path).absolutePath)
            ?: error("Focused Inpainting 基图无法解码")
        val mask = originalMask?.takeIf(NovelAiStudioAssetRef::isUsable)?.let { asset ->
            BitmapFactory.decodeFile(File(asset.path).absolutePath)
                ?: error("Focused Inpainting 蒙版无法解码")
        }
        try {
            if (mask != null) {
                require(base.width == mask.width && base.height == mask.height) {
                    "Focused Inpainting 蒙版与基图尺寸不一致"
                }
            }
            val plan = NovelAiFocusedInpaintPlanner.plan(
                sourceWidth = base.width,
                sourceHeight = base.height,
                region = region,
                minimumContextPixels = minimumContextPixels
            )
            val croppedBase = Bitmap.createBitmap(
                base,
                plan.crop.left,
                plan.crop.top,
                plan.crop.width,
                plan.crop.height
            )
            val scaledBase = NovelAiLanczos3Resampler.resize(
                croppedBase,
                plan.requestSize.width,
                plan.requestSize.height
            )
            return try {
                val preparedMask = NovelAiInpaintMaskEncoder.prepareFocusedMask(mask, plan)
                NovelAiFocusedInpaintRequest(
                    plan = plan,
                    imageBase64 = encodePngBase64(scaledBase),
                    maskBase64 = preparedMask.requestMaskBase64,
                    blendMaskAlpha = preparedMask.blendMaskAlpha
                )
            } finally {
                if (scaledBase !== croppedBase) scaledBase.recycle()
                if (croppedBase !== base) croppedBase.recycle()
            }
        } finally {
            base.recycle()
            mask?.recycle()
        }
    }

    private fun encodePngBase64(bitmap: Bitmap): String {
        val output = ByteArrayOutputStream()
        check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) {
            "Focused Inpainting 基图编码失败"
        }
        return Base64.encodeToString(output.toByteArray(), Base64.NO_WRAP)
    }
}
