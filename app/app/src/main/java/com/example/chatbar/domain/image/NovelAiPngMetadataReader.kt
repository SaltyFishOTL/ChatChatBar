package com.example.chatbar.domain.image

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.Log

import com.example.chatbar.data.local.entity.GeneratedImageCharacterPrompt
import com.example.chatbar.data.local.entity.GeneratedImageMetadata
import java.io.ByteArrayInputStream
import java.io.File
import java.util.zip.InflaterInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.floatOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.longOrNull

object NovelAiPngMetadataReader {
    private val pngSignature = byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10)
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun read(imagePath: String): GeneratedImageMetadata? {
        return candidates(imagePath).firstNotNullOfOrNull { chunks ->
            chunks["Comment"]?.let { parseComment(it, imagePath) }
        }
    }

    internal fun parseComment(comment: String, imagePath: String): GeneratedImageMetadata? =
        runCatching { json.parseToJsonElement(comment).jsonObject.toMetadata(imagePath) }.getOrNull()

    fun readStudio(imagePath: String): NovelAiStudioPngMetadata? {
        return candidates(imagePath).firstNotNullOfOrNull { chunks ->
            chunks["Comment"]?.let { parseStudioComment(it, imagePath, chunks["Source"]) }
        }
    }

    fun readEnhance(imagePath: String): NovelAiEnhanceSource {
        var failure: Exception? = null
        for (chunks in candidates(imagePath)) {
            val comment = chunks["Comment"] ?: continue
            try { return parseEnhanceComment(comment, imagePath, chunks["Source"]) }
            catch (error: Exception) { failure = error }
        }
        throw failure ?: IllegalArgumentException("缺少 NovelAI 生成元数据，请使用 Upscale")
    }

    // Each candidate is a complete source: never mix conflicting file/alpha parameters.
    private fun candidates(imagePath: String): Sequence<Map<String, String>> = sequence {
        val file = File(imagePath)
        if (!file.isFile) return@sequence
        require(file.length() in 1..100L * 1024 * 1024) { "图片为空或超过 100 MB" }
        yield(pngTextChunks(file.readBytes()))
        try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(imagePath, bounds)
            if (bounds.outMimeType !in setOf("image/png", "image/webp")) return@sequence
            require(bounds.outWidth > 0 && bounds.outHeight > 0 &&
                bounds.outWidth.toLong() * bounds.outHeight <= 12_582_912) { "图片尺寸过大，无法读取透明度元数据" }
            val bitmap = BitmapFactory.decodeFile(imagePath, BitmapFactory.Options().apply {
                inPreferredConfig = Bitmap.Config.ARGB_8888
                inPremultiplied = false
                inScaled = false
            }) ?: return@sequence
            val payload = try {
                val column = IntArray(bitmap.height)
                var columnX = -1
                StealthAlphaMetadata.decode(bitmap.width, bitmap.height) { x, y ->
                    if (columnX != x) {
                        bitmap.getPixels(column, 0, 1, x, 0, 1, bitmap.height)
                        columnX = x
                    }
                    column[y] ushr 24
                }
            } finally { bitmap.recycle() }
            if (payload != null) {
                val root = json.parseToJsonElement(payload).jsonObject
                val fields = root.mapValues { (_, value) ->
                    if (value is kotlinx.serialization.json.JsonPrimitive) value.content else value.toString()
                }
                Log.d("NovelAiMetadata", "Reading alpha-channel metadata")
                yield(fields)
            }
        } catch (error: Exception) {
            Log.w("NovelAiMetadata", "Cannot read alpha-channel metadata", error)
        }
    }

    internal fun parseEnhanceComment(comment: String, imagePath: String, source: String?): NovelAiEnhanceSource {
        val root = resolveEnhanceActualPrompts(json.parseToJsonElement(comment).jsonObject)
        val explicitModel = root["model"]?.jsonPrimitive?.contentOrNull
            ?: root["model_id"]?.jsonPrimitive?.contentOrNull
        val modelText = (explicitModel ?: root["source"]?.jsonPrimitive?.contentOrNull ?: source).orEmpty().trim().lowercase()
        val model = when {
            "curated" in modelText || "inpainting" in modelText -> null
            modelText in ENHANCE_V5_SOURCES -> NovelAiImageModel.V5_FULL
            modelText in ENHANCE_V45_SOURCES -> NovelAiImageModel.V4_5_FULL
            else -> null
        } ?: error("无法准确识别受支持的 Full 模型，请使用 Upscale")
        val metadata = root.toStudioMetadata(imagePath, source) ?: error("生成元数据不完整，请使用 Upscale")
        require(metadata.positivePrompt.isNotBlank()) { "元数据缺少 Prompt，请使用 Upscale" }
        val imported = metadata.settings
        require(!root.containsKey("steps") || imported.steps != null) { "元数据中的 Steps 不受支持，请使用 Upscale" }
        require(!root.containsKey("scale") || imported.guidance != null) { "元数据中的 Guidance 不受支持，请使用 Upscale" }
        require(!root.containsKey("cfg_rescale") || imported.cfgRescale != null) { "元数据中的 CFG Rescale 不受支持，请使用 Upscale" }
        require(!root.containsKey("sampler") || imported.sampler != null) { "元数据中的采样器不受支持，请使用 Upscale" }
        val settings = NovelAiGenerationSettings(
            model = model,
            steps = imported.steps ?: 28,
            guidance = imported.guidance ?: 6f,
            cfgRescale = imported.cfgRescale ?: 0f,
            varietyPlus = imported.varietyPlus ?: false,
            sampler = imported.sampler ?: NovelAiSampler.EULER_ANCESTRAL,
            useCharacterPositions = imported.useCharacterPositions ?: false
        )
        settings.validationError(metadata.characters.size)?.let { error(it) }
        return NovelAiEnhanceSource(
            prompt = NovelAiPromptPlan(
                baseCaption = metadata.positivePrompt,
                negativePrompt = metadata.negativePrompt.orEmpty(),
                characterCaptions = metadata.characters.map { character ->
                    NovelAiCharacterCaption(character.prompt, character.center ?: DesignedCharacterCenter(0.5f, 0.5f), character.negativePrompt)
                }
            ),
            settings = settings,
            // Keep original captions/positions and sampling switches, but never replay embedded references or seeds.
            parameters = JsonObject(root.filterKeys { it in ENHANCE_PARAMETERS })
        )
    }

    private val ENHANCE_PARAMETERS = setOf(
        "v4_prompt", "v4_negative_prompt", "noise_schedule", "skip_cfg_above_sigma", "dynamic_thresholding",
        "deliberate_euler_ancestral_bug", "prefer_brownian", "legacy_v3_extend", "uncond_scale"
    )

    // Exact hashes from the official model metadata resolver; unknown hashes never default to Full.
    private val ENHANCE_V5_SOURCES = setOf(
        "nai-diffusion-5-full", "novelai diffusion v5 full",
        "novelai diffusion v5 657484a5", "novelai diffusion v5 0adf9ab7"
    )
    private val ENHANCE_V45_SOURCES = setOf(
        "nai-diffusion-4-5-full", "novelai diffusion v4.5 full", "diffusionmodelmetaname.naiv4next 4bde2a90",
        "novelai diffusion v4.5 4bde2a90", "novelai diffusion v4.5 1229b44f",
        "novelai diffusion v4.5 b9f340fd", "novelai diffusion v4.5 f3d95188"
    )

    private fun resolveEnhanceActualPrompts(root: JsonObject): JsonObject {
        val actual = root["actual_prompts"] as? JsonObject ?: return root
        val resolved = root.toMutableMap()
        listOf(Triple("prompt", "v4_prompt", "prompt"), Triple("negative_prompt", "v4_negative_prompt", "uc")).forEach { (key, v4Key, textKey) ->
            val caption = actual[key] as? JsonObject ?: return@forEach
            caption["base_caption"]?.let { resolved[textKey] = it }
            val originalV4 = root[v4Key] as? JsonObject ?: return@forEach
            val originalCaption = originalV4["caption"] as? JsonObject ?: return@forEach
            val mergedCaption = originalCaption.toMutableMap()
            caption["base_caption"]?.let { mergedCaption["base_caption"] = it }
            val originalCharacters = originalCaption["char_captions"]?.jsonArrayOrNull()
            val actualCharacters = caption["char_captions"]?.jsonArrayOrNull()
            if (originalCharacters != null && actualCharacters?.size == originalCharacters.size) {
                mergedCaption["char_captions"] = kotlinx.serialization.json.JsonArray(originalCharacters.mapIndexed { index, item ->
                    val character = item as? JsonObject ?: return@mapIndexed item
                    val actualText = (actualCharacters[index] as? JsonObject)?.get("char_caption")
                    if (actualText == null) character else JsonObject(character + ("char_caption" to actualText))
                })
            }
            resolved[v4Key] = JsonObject(originalV4 + ("caption" to JsonObject(mergedCaption)))
        }
        return JsonObject(resolved)
    }

    internal fun parseStudioComment(
        comment: String,
        imagePath: String,
        source: String? = null
    ): NovelAiStudioPngMetadata? = runCatching {
        json.parseToJsonElement(comment).jsonObject.toStudioMetadata(imagePath, source)
    }.getOrNull()

    private fun JsonObject.toMetadata(imagePath: String): GeneratedImageMetadata? {
        val positive = this["v4_prompt"]?.jsonObjectOrNull()
        val positiveCaption = positive?.get("caption")?.jsonObjectOrNull()
        val baseCaption = positiveCaption?.get("base_caption")?.jsonPrimitive?.contentOrNull
            ?: this["prompt"]?.jsonPrimitive?.contentOrNull
            ?: return null
        val characters = positiveCaption?.get("char_captions")?.jsonArrayOrNull().orEmpty().mapNotNull { item ->
            val itemObject = item.jsonObjectOrNull() ?: return@mapNotNull null
            val prompt = itemObject["char_caption"]?.jsonPrimitive?.contentOrNull ?: return@mapNotNull null
            val center = itemObject["centers"]?.jsonArrayOrNull()?.firstOrNull()?.jsonObjectOrNull()
                ?: return@mapNotNull null
            val x = center["x"]?.jsonPrimitive?.floatOrNull ?: return@mapNotNull null
            val y = center["y"]?.jsonPrimitive?.floatOrNull ?: return@mapNotNull null
            GeneratedImageCharacterPrompt(prompt, x, y)
        }
        val negativeCaption = this["v4_negative_prompt"]?.jsonObjectOrNull()
            ?.get("caption")?.jsonObjectOrNull()
        val negativeCharacters = negativeCaption?.get("char_captions")?.jsonArrayOrNull().orEmpty()
        val charactersWithNegative = characters.mapIndexed { index, character ->
            val negative = negativeCharacters.getOrNull(index)?.jsonObjectOrNull()
                ?.get("char_caption")?.jsonPrimitive?.contentOrNull.orEmpty()
            character.copy(negativePrompt = negative)
        }
        val negativePrompt = negativeCaption?.get("base_caption")?.jsonPrimitive?.contentOrNull
            ?: this["uc"]?.jsonPrimitive?.contentOrNull
            ?: ""
        val width = this["width"]?.jsonPrimitive?.intOrNull ?: return null
        val height = this["height"]?.jsonPrimitive?.intOrNull ?: return null
        val preset = when {
            width == height -> NovelAiImageSizePreset.SQUARE
            width > height -> NovelAiImageSizePreset.HORIZONTAL
            else -> NovelAiImageSizePreset.PORTRAIT
        }
        return GeneratedImageMetadata(
            imagePath = imagePath,
            baseCaption = baseCaption,
            characterPrompts = charactersWithNegative,
            negativePrompt = negativePrompt,
            sizePreset = preset.name,
            width = width,
            height = height
        )
    }

    private fun JsonObject.toStudioMetadata(imagePath: String, source: String?): NovelAiStudioPngMetadata? {
        val positiveCaption = this["v4_prompt"]?.jsonObjectOrNull()
            ?.get("caption")?.jsonObjectOrNull()
        val negativeCaption = this["v4_negative_prompt"]?.jsonObjectOrNull()
            ?.get("caption")?.jsonObjectOrNull()
        val hasNovelAiStructure = positiveCaption != null || (
            containsKey("prompt") && containsKey("uc") && containsKey("steps") &&
                containsKey("sampler") && containsKey("seed")
            )
        if (!hasNovelAiStructure) return null
        val positivePrompt = positiveCaption?.get("base_caption")?.jsonPrimitive?.contentOrNull
            ?: this["prompt"]?.jsonPrimitive?.contentOrNull
            ?: return null
        val width = this["width"]?.jsonPrimitive?.intOrNull ?: return null
        val height = this["height"]?.jsonPrimitive?.intOrNull ?: return null
        if (width <= 0 || height <= 0) return null

        val positiveCharacters = positiveCaption?.get("char_captions")?.jsonArrayOrNull()
        val negativeCharacters = negativeCaption?.get("char_captions")?.jsonArrayOrNull()
        val hasCharacters = positiveCharacters != null || negativeCharacters != null
        val characterCount = maxOf(positiveCharacters?.size ?: 0, negativeCharacters?.size ?: 0)
        val characters = List(characterCount) { index ->
            val center = positiveCharacters?.getOrNull(index)?.jsonObjectOrNull()
                ?.get("centers")?.jsonArrayOrNull()?.firstOrNull()?.jsonObjectOrNull()
            val x = center?.get("x")?.jsonPrimitive?.floatOrNull
            val y = center?.get("y")?.jsonPrimitive?.floatOrNull
            NovelAiImportedCharacterPrompt(
                prompt = positiveCharacters?.getOrNull(index)?.jsonObjectOrNull()
                    ?.get("char_caption")?.jsonPrimitive?.contentOrNull.orEmpty(),
                negativePrompt = negativeCharacters?.getOrNull(index)?.jsonObjectOrNull()
                    ?.get("char_caption")?.jsonPrimitive?.contentOrNull.orEmpty(),
                center = if (x != null && y != null && x in 0f..1f && y in 0f..1f) DesignedCharacterCenter(x, y) else null
            )
        }
        val matchedSize = matchStudioSize(width, height)
        val useCustomSize = matchedSize == null && NovelAiStudioSizePolicy.validationError(width, height) == null
        val modelText = listOfNotNull(
            this["model"]?.jsonPrimitive?.contentOrNull,
            this["model_id"]?.jsonPrimitive?.contentOrNull,
            this["source"]?.jsonPrimitive?.contentOrNull,
            source
        ).joinToString(" ")
        val action = when (this["action"]?.jsonPrimitive?.contentOrNull?.lowercase()) {
            NovelAiGenerationAction.IMAGE_TO_IMAGE.apiId -> NovelAiGenerationAction.IMAGE_TO_IMAGE
            NovelAiGenerationAction.INPAINT.apiId -> NovelAiGenerationAction.INPAINT
            else -> NovelAiGenerationAction.TEXT_TO_IMAGE
        }
        val preciseImage = this["director_reference_images"]?.jsonArrayOrNull()
            ?.firstOrNull()?.jsonPrimitive?.contentOrNull
        val preciseCaption = this["director_reference_descriptions"]?.jsonArrayOrNull()
            ?.firstOrNull()?.jsonObjectOrNull()
            ?.get("caption")?.jsonObjectOrNull()
            ?.get("base_caption")?.jsonPrimitive?.contentOrNull
        val preciseType = NovelAiPreciseReferenceType.entries.firstOrNull { it.wireCaption == preciseCaption }
        val preciseStrength = this["director_reference_strength_values"]?.jsonArrayOrNull()
            ?.firstOrNull()?.jsonPrimitive?.floatOrNull
        val preciseFidelity = this["director_reference_secondary_strength_values"]?.jsonArrayOrNull()
            ?.firstOrNull()?.jsonPrimitive?.floatOrNull
            ?.let(NovelAiPreciseReferenceWirePolicy::fidelity)
        val vibeEncodings = this["reference_image_multiple"]?.jsonArrayOrNull().orEmpty()
            .mapNotNull { it.jsonPrimitive.contentOrNull }
        val vibeInformation = this["reference_information_extracted_multiple"]?.jsonArrayOrNull().orEmpty()
        val vibeStrengths = this["reference_strength_multiple"]?.jsonArrayOrNull().orEmpty()
        val importedVibes = vibeEncodings.mapIndexed { index, encoding ->
            NovelAiVibeReferenceDraft(
                encodedVibe = encoding,
                informationExtracted = vibeInformation.getOrNull(index)?.jsonPrimitive?.floatOrNull ?: 1f,
                strength = vibeStrengths.getOrNull(index)?.jsonPrimitive?.floatOrNull ?: 0.6f
            )
        }
        return NovelAiStudioPngMetadata(
            imagePath = imagePath,
            positivePrompt = positivePrompt,
            negativePrompt = negativeCaption?.get("base_caption")?.jsonPrimitive?.contentOrNull
                ?: this["uc"]?.jsonPrimitive?.contentOrNull,
            characters = characters,
            hasCharacterPrompts = hasCharacters,
            settings = NovelAiImportedGenerationSettings(
                useCharacterPositions = this["v4_prompt"]?.jsonObjectOrNull()
                    ?.get("use_coords")?.jsonPrimitive?.booleanOrNull
                    ?: this["use_coords"]?.jsonPrimitive?.booleanOrNull ?: false,
                model = modelText.toNovelAiModelOrNull(),
                sizeTier = matchedSize?.first,
                aspectRatio = matchedSize?.second,
                customWidth = width.takeIf { useCustomSize },
                customHeight = height.takeIf { useCustomSize },
                count = this["n_samples"]?.jsonPrimitive?.intOrNull?.takeIf { it in 1..4 },
                steps = this["steps"]?.jsonPrimitive?.intOrNull?.takeIf { it in 1..50 },
                guidance = this["scale"]?.jsonPrimitive?.floatOrNull?.takeIf { it in 1f..10f },
                cfgRescale = this["cfg_rescale"]?.jsonPrimitive?.floatOrNull?.takeIf { it in 0f..1f },
                varietyPlus = if (containsKey("skip_cfg_above_sigma")) {
                    (this["skip_cfg_above_sigma"]?.jsonPrimitive?.floatOrNull ?: 0f) > 0f
                } else null,
                sampler = this["sampler"]?.jsonPrimitive?.contentOrNull?.let { sampler ->
                    NovelAiSampler.entries.firstOrNull { it.apiId.equals(sampler, ignoreCase = true) }
                }
            ),
            imageGuidance = NovelAiImportedImageGuidance(
                action = action,
                baseImageBase64 = this["image"]?.jsonPrimitive?.contentOrNull,
                maskBase64 = this["mask"]?.jsonPrimitive?.contentOrNull,
                imageToImageStrength = if (action == NovelAiGenerationAction.IMAGE_TO_IMAGE) {
                    this["strength"]?.jsonPrimitive?.floatOrNull
                } else null,
                imageToImageNoise = this["noise"]?.jsonPrimitive?.floatOrNull,
                inpaintStrength = if (action == NovelAiGenerationAction.INPAINT) {
                    this["inpaintImg2ImgStrength"]?.jsonPrimitive?.floatOrNull
                        ?: this["strength"]?.jsonPrimitive?.floatOrNull
                } else null,
                preciseImageBase64 = preciseImage,
                preciseType = preciseType,
                preciseStrength = preciseStrength,
                preciseFidelity = preciseFidelity,
                vibes = importedVibes
            ),
            seed = this["seed"]?.jsonPrimitive?.longOrNull
                ?.takeIf { it in NovelAiGenerationSettings.MIN_SEED..NovelAiGenerationSettings.MAX_SEED },
            width = width,
            height = height
        )
    }

    private fun pngTextChunks(bytes: ByteArray): Map<String, String> {
        if (bytes.size < pngSignature.size || !bytes.copyOfRange(0, 8).contentEquals(pngSignature)) return emptyMap()
        val result = mutableMapOf<String, String>()
        var offset = 8
        while (offset + 12 <= bytes.size) {
            val length = readInt(bytes, offset)
            if (length < 0 || offset + 12L + length > bytes.size) break
            val type = bytes.copyOfRange(offset + 4, offset + 8).toString(Charsets.US_ASCII)
            if (type in setOf("tEXt", "zTXt", "iTXt") && length <= StealthAlphaMetadata.MAX_METADATA_BYTES) {
                val data = bytes.copyOfRange(offset + 8, offset + 8 + length)
                try { parseTextChunk(type, data)?.let { (key, value) -> result[key] = value } }
                catch (error: Exception) { Log.w("NovelAiMetadata", "Invalid PNG text chunk: $type", error) }
            }
            offset += length + 12
            if (type == "IEND") break
        }
        return result
    }

    private fun parseTextChunk(type: String, data: ByteArray): Pair<String, String>? = when (type) {
        "tEXt" -> splitKeyword(data)?.let { (keyword, rest) -> keyword to rest.toString(Charsets.UTF_8) }
        "zTXt" -> splitKeyword(data)?.let { (keyword, rest) ->
            if (rest.isEmpty()) null else keyword to inflate(rest.copyOfRange(1, rest.size)).toString(Charsets.UTF_8)
        }
        "iTXt" -> parseInternationalText(data)
        else -> null
    }

    private fun parseInternationalText(data: ByteArray): Pair<String, String>? {
        val keywordEnd = data.indexOf(0)
        if (keywordEnd < 0 || keywordEnd + 2 >= data.size) return null
        val keyword = data.copyOfRange(0, keywordEnd).toString(Charsets.ISO_8859_1)
        val compressed = data[keywordEnd + 1].toInt() == 1
        var cursor = keywordEnd + 3
        repeat(2) {
            val end = data.indexOf(0, cursor)
            if (end < 0) return null
            cursor = end + 1
        }
        val text = data.copyOfRange(cursor, data.size)
        return keyword to (if (compressed) inflate(text) else text).toString(Charsets.UTF_8)
    }

    private fun splitKeyword(data: ByteArray): Pair<String, ByteArray>? {
        val separator = data.indexOf(0)
        if (separator < 0) return null
        return data.copyOfRange(0, separator).toString(Charsets.ISO_8859_1) to
            data.copyOfRange(separator + 1, data.size)
    }

    private fun inflate(bytes: ByteArray): ByteArray =
        InflaterInputStream(ByteArrayInputStream(bytes)).use { input ->
            input.readBytesBounded(StealthAlphaMetadata.MAX_METADATA_BYTES)
        }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xff) shl 24) or
            ((bytes[offset + 1].toInt() and 0xff) shl 16) or
            ((bytes[offset + 2].toInt() and 0xff) shl 8) or
            (bytes[offset + 3].toInt() and 0xff)
}

private fun matchStudioSize(width: Int, height: Int): Pair<NovelAiSizeTier, NovelAiAspectRatio>? {
    NovelAiSizeTier.entries.forEach { tier ->
        NovelAiAspectRatio.entries.forEach { aspect ->
            if (tier == NovelAiSizeTier.WALLPAPER && aspect == NovelAiAspectRatio.SQUARE) return@forEach
            val size = NovelAiGenerationSettings(sizeTier = tier, aspectRatio = aspect).imageSize()
            if (size.width == width && size.height == height) return tier to aspect
        }
    }
    return null
}

private fun String.toNovelAiModelOrNull(): NovelAiImageModel? {
    val normalized = lowercase()
    return when {
        "nai-diffusion-5" in normalized || "diffusion v5" in normalized -> NovelAiImageModel.V5_FULL
        "nai-diffusion-4" in normalized || "diffusion v4" in normalized -> NovelAiImageModel.V4_5_FULL
        else -> null
    }
}

private fun kotlinx.serialization.json.JsonElement.jsonObjectOrNull(): JsonObject? =
    this as? JsonObject

private fun kotlinx.serialization.json.JsonElement.jsonArrayOrNull(): kotlinx.serialization.json.JsonArray? =
    this as? kotlinx.serialization.json.JsonArray

private fun ByteArray.indexOf(value: Byte, startIndex: Int = 0): Int {
    for (index in startIndex until size) if (this[index] == value) return index
    return -1
}
