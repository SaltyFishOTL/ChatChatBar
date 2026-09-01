package com.example.chatbar.domain.image

import java.util.UUID
import kotlin.math.abs
import kotlinx.serialization.Serializable

@Serializable
enum class NovelAiGenerationAction(val apiId: String, val displayName: String) {
    TEXT_TO_IMAGE("generate", "文生图"),
    IMAGE_TO_IMAGE("img2img", "图生图"),
    INPAINT("infill", "聚焦重绘")
}

@Serializable
enum class NovelAiReferenceMode { NONE, PRECISE, VIBE }

@Serializable
enum class NovelAiPreciseReferenceType(val wireCaption: String, val displayName: String) {
    CHARACTER("character", "角色"),
    STYLE("style", "画风"),
    CHARACTER_AND_STYLE("character&style", "角色与画风")
}

enum class NovelAiImageUseTarget(val displayName: String) {
    IMAGE_TO_IMAGE("图生图"),
    INPAINT("聚焦重绘"),
    PRECISE_REFERENCE("精确参考"),
    VIBE_REFERENCE("氛围参考")
}

@Serializable
data class NovelAiStudioAssetRef(
    val id: String = UUID.randomUUID().toString(),
    val path: String = "",
    val sha256: String = "",
    val width: Int = 0,
    val height: Int = 0,
    val containsPaint: Boolean = true
) {
    val isUsable: Boolean get() = path.isNotBlank() && width > 0 && height > 0
}

@Serializable
data class NovelAiFocusedInpaintRegion(
    val x: Float,
    val y: Float,
    val width: Float,
    val height: Float
) {
    val isValid: Boolean
        get() = x.isFinite() && y.isFinite() && width.isFinite() && height.isFinite() &&
            x >= 0f && y >= 0f && width > 0f && height > 0f &&
            x + width <= 1.0001f && y + height <= 1.0001f
}

@Serializable
data class NovelAiPreciseReferenceDraft(
    val asset: NovelAiStudioAssetRef? = null,
    val type: NovelAiPreciseReferenceType = NovelAiPreciseReferenceType.CHARACTER,
    val strength: Float = 1f,
    val fidelity: Float = 1f
)

@Serializable
data class NovelAiVibeReferenceDraft(
    val id: String = UUID.randomUUID().toString(),
    val asset: NovelAiStudioAssetRef? = null,
    val encodedVibe: String? = null,
    val informationExtracted: Float = 1f,
    val strength: Float = 0.6f
) {
    val informationEditable: Boolean get() = asset?.isUsable == true
    val isUsable: Boolean get() = asset?.isUsable == true || !encodedVibe.isNullOrBlank()
}

@Serializable
data class NovelAiImageGuidanceDraft(
    val action: NovelAiGenerationAction = NovelAiGenerationAction.TEXT_TO_IMAGE,
    val baseImage: NovelAiStudioAssetRef? = null,
    val maskImage: NovelAiStudioAssetRef? = null,
    val imageToImageStrength: Float = 0.5f,
    val imageToImageNoise: Float = 0.1f,
    val inpaintStrength: Float = 1f,
    val focusedInpaintRegion: NovelAiFocusedInpaintRegion? = null,
    val focusedInpaintMinimumContext: Int = 96,
    val referenceMode: NovelAiReferenceMode = NovelAiReferenceMode.NONE,
    val preciseReference: NovelAiPreciseReferenceDraft = NovelAiPreciseReferenceDraft(),
    val vibes: List<NovelAiVibeReferenceDraft> = emptyList(),
    val normalizeVibeStrengths: Boolean = true
) {
    val hasBaseImage: Boolean get() = baseImage?.isUsable == true
    val hasMask: Boolean get() = maskImage?.isUsable == true && maskImage.containsPaint

    fun effectiveReferenceMode(model: NovelAiImageModel): NovelAiReferenceMode =
        if (model == NovelAiImageModel.V4_5_FULL) referenceMode else NovelAiReferenceMode.NONE

    fun summary(model: NovelAiImageModel): String = buildList {
        when (action) {
            NovelAiGenerationAction.IMAGE_TO_IMAGE -> add("图生图")
            NovelAiGenerationAction.INPAINT -> add("聚焦重绘")
            NovelAiGenerationAction.TEXT_TO_IMAGE -> Unit
        }
        when (effectiveReferenceMode(model)) {
            NovelAiReferenceMode.PRECISE -> add("精确参考")
            NovelAiReferenceMode.VIBE -> vibes.count(NovelAiVibeReferenceDraft::isUsable)
                .takeIf { it > 0 }?.let { add("氛围×$it") }
            NovelAiReferenceMode.NONE -> Unit
        }
    }.joinToString(" · ")

    fun validationError(model: NovelAiImageModel): String? = when {
        action != NovelAiGenerationAction.TEXT_TO_IMAGE && !hasBaseImage -> "请先选择图生图基图"
        action == NovelAiGenerationAction.INPAINT && focusedInpaintRegion?.isValid != true ->
            "请使用聚焦工具框选重绘区域"
        action == NovelAiGenerationAction.INPAINT && baseImage != null && maskImage != null &&
            (baseImage.width != maskImage.width || baseImage.height != maskImage.height) ->
                "Focused Inpainting 蒙版尺寸必须与基图一致"
        action == NovelAiGenerationAction.INPAINT && focusedInpaintMinimumContext !in 32..96 ->
            "Minimum Context 必须在 32–96 像素之间"
        action == NovelAiGenerationAction.INPAINT && baseImage != null && focusedInpaintRegion != null &&
            (focusedInpaintRegion.width * baseImage.width <= focusedInpaintMinimumContext * 2 ||
                focusedInpaintRegion.height * baseImage.height <= focusedInpaintMinimumContext * 2) ->
                "聚焦区域必须大于 Minimum Context 边界"
        action == NovelAiGenerationAction.INPAINT && baseImage != null && focusedInpaintRegion != null &&
            focusedInpaintRegion.width * baseImage.width * focusedInpaintRegion.height * baseImage.height >
            NovelAiFocusedInpaintPlanner.MAX_FOCUSED_SOURCE_PIXELS ->
                "聚焦区域超过官方面积上限"
        imageToImageStrength !in 0f..1f -> "图生图 Strength 必须在 0.0–1.0 之间"
        imageToImageNoise !in 0f..1f -> "图生图 Noise 必须在 0.0–1.0 之间"
        inpaintStrength !in 0f..1f -> "Focused Inpainting Strength 必须在 0.0–1.0 之间"
        effectiveReferenceMode(model) == NovelAiReferenceMode.PRECISE && preciseReference.asset?.isUsable != true ->
            "精确参考图片不可用"
        effectiveReferenceMode(model) == NovelAiReferenceMode.VIBE && vibes.none(NovelAiVibeReferenceDraft::isUsable) ->
            "氛围参考图片不可用"
        model == NovelAiImageModel.V4_5_FULL && vibes.size > MAX_VIBES -> "氛围参考最多 $MAX_VIBES 张"
        model == NovelAiImageModel.V4_5_FULL &&
            (preciseReference.strength !in 0f..1f || preciseReference.fidelity !in 0f..1f) ->
            "精确参考参数必须在 0.0–1.0 之间"
        model == NovelAiImageModel.V4_5_FULL &&
            vibes.any { it.informationExtracted !in 0f..1f || it.strength !in 0f..1f } ->
            "氛围参考参数必须在 0.0–1.0 之间"
        else -> null
    }

    fun effectiveVibeStrengths(): List<Float> {
        val values = vibes.filter(NovelAiVibeReferenceDraft::isUsable).map { it.strength.coerceIn(0f, 1f) }
        val sum = values.sum()
        return if (normalizeVibeStrengths && sum > 1f) values.map { it / sum } else values
    }

    companion object {
        const val MAX_VIBES = 16
    }
}

fun NovelAiImageGuidanceDraft.withSharedImageSources(
    baseAsset: NovelAiStudioAssetRef,
    referenceAsset: NovelAiStudioAssetRef
): NovelAiImageGuidanceDraft {
    val matchingVibe = vibes.indexOfFirst { vibe ->
        val existing = vibe.asset ?: return@indexOfFirst false
        referenceAsset.sha256.isNotBlank() && existing.sha256 == referenceAsset.sha256
    }
    require(matchingVibe >= 0 || vibes.size < NovelAiImageGuidanceDraft.MAX_VIBES) {
        "氛围参考已满；请进入图像引导管理"
    }
    val updatedVibes = if (matchingVibe >= 0) {
        vibes.mapIndexed { index, vibe ->
            if (index == matchingVibe) vibe.copy(asset = referenceAsset, encodedVibe = null) else vibe
        }
    } else {
        vibes + NovelAiVibeReferenceDraft(asset = referenceAsset)
    }
    return copy(
        action = NovelAiGenerationAction.IMAGE_TO_IMAGE,
        baseImage = baseAsset,
        maskImage = null,
        focusedInpaintRegion = null,
        preciseReference = preciseReference.copy(asset = referenceAsset),
        vibes = updatedVibes
    )
}

fun NovelAiImageGuidanceDraft.withoutImageSource(target: NovelAiImageUseTarget): NovelAiImageGuidanceDraft =
    when (target) {
        NovelAiImageUseTarget.IMAGE_TO_IMAGE,
        NovelAiImageUseTarget.INPAINT -> copy(
            action = NovelAiGenerationAction.TEXT_TO_IMAGE,
            baseImage = null,
            maskImage = null,
            focusedInpaintRegion = null
        )
        NovelAiImageUseTarget.PRECISE_REFERENCE -> copy(
            referenceMode = if (referenceMode == NovelAiReferenceMode.PRECISE) {
                NovelAiReferenceMode.NONE
            } else {
                referenceMode
            },
            preciseReference = preciseReference.copy(asset = null)
        )
        NovelAiImageUseTarget.VIBE_REFERENCE -> copy(
            referenceMode = if (referenceMode == NovelAiReferenceMode.VIBE) {
                NovelAiReferenceMode.NONE
            } else {
                referenceMode
            },
            vibes = emptyList()
        )
    }

data class NovelAiPreparedVibeReference(
    val encoding: String,
    val informationExtracted: Float,
    val strength: Float
)

data class NovelAiPreparedImageGuidance(
    val action: NovelAiGenerationAction,
    val imageBase64: String? = null,
    val maskBase64: String? = null,
    val imageToImageStrength: Float = 0.5f,
    val imageToImageNoise: Float = 0.1f,
    val inpaintStrength: Float = 1f,
    val preciseReferenceBase64: String? = null,
    val preciseReferenceType: NovelAiPreciseReferenceType = NovelAiPreciseReferenceType.CHARACTER,
    val preciseReferenceStrength: Float = 1f,
    val preciseReferenceFidelity: Float = 1f,
    val vibes: List<NovelAiPreparedVibeReference> = emptyList()
) {
    companion object {
        val NONE = NovelAiPreparedImageGuidance(NovelAiGenerationAction.TEXT_TO_IMAGE)
    }
}

object NovelAiPreciseReferenceWirePolicy {
    fun secondaryStrength(fidelity: Float): Float = 1f - fidelity.coerceIn(0f, 1f)
    fun fidelity(secondaryStrength: Float): Float = 1f - secondaryStrength.coerceIn(0f, 1f)

    fun targetSize(sourceWidth: Int, sourceHeight: Int): NovelAiImageSize {
        require(sourceWidth > 0 && sourceHeight > 0) { "精确参考图片尺寸无效" }
        val sourceRatio = sourceWidth.toDouble() / sourceHeight
        return NovelAiAspectRatio.entries
            .map { aspect ->
                NovelAiGenerationSettings(
                    sizeTier = NovelAiSizeTier.LARGE,
                    aspectRatio = aspect
                ).imageSize()
            }
            .minBy { size -> abs(sourceRatio - size.width.toDouble() / size.height) }
    }
}

fun NovelAiImageGuidanceDraft.ownedAssetPaths(): Set<String> = buildSet {
    baseImage?.path?.takeIf(String::isNotBlank)?.let(::add)
    maskImage?.path?.takeIf(String::isNotBlank)?.let(::add)
    preciseReference.asset?.path?.takeIf(String::isNotBlank)?.let(::add)
    vibes.forEach { vibe -> vibe.asset?.path?.takeIf(String::isNotBlank)?.let(::add) }
}
