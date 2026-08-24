package com.example.chatbar.domain.image

import com.example.chatbar.data.local.entity.GeneratedImageCharacterPrompt
import com.example.chatbar.data.local.entity.GeneratedImageMetadata
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.InflaterInputStream
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.contentOrNull
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
        val file = File(imagePath)
        if (!file.isFile) return null
        val bytes = file.readBytes()
        val comment = pngTextChunks(bytes)["Comment"] ?: return null
        val root = runCatching { json.parseToJsonElement(comment).jsonObject }.getOrNull() ?: return null
        return root.toMetadata(imagePath)
    }

    internal fun parseComment(comment: String, imagePath: String): GeneratedImageMetadata? =
        runCatching { json.parseToJsonElement(comment).jsonObject.toMetadata(imagePath) }.getOrNull()

    fun readStudio(imagePath: String): NovelAiStudioPngMetadata? {
        val file = File(imagePath)
        if (!file.isFile) return null
        val chunks = pngTextChunks(file.readBytes())
        val comment = chunks["Comment"] ?: return null
        return parseStudioComment(comment, imagePath, chunks["Source"])
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
            NovelAiImportedCharacterPrompt(
                prompt = positiveCharacters?.getOrNull(index)?.jsonObjectOrNull()
                    ?.get("char_caption")?.jsonPrimitive?.contentOrNull.orEmpty(),
                negativePrompt = negativeCharacters?.getOrNull(index)?.jsonObjectOrNull()
                    ?.get("char_caption")?.jsonPrimitive?.contentOrNull.orEmpty()
            )
        }
        val matchedSize = matchStudioSize(width, height)
        val modelText = listOfNotNull(
            this["model"]?.jsonPrimitive?.contentOrNull,
            this["model_id"]?.jsonPrimitive?.contentOrNull,
            this["source"]?.jsonPrimitive?.contentOrNull,
            source
        ).joinToString(" ")
        return NovelAiStudioPngMetadata(
            imagePath = imagePath,
            positivePrompt = positivePrompt,
            negativePrompt = negativeCaption?.get("base_caption")?.jsonPrimitive?.contentOrNull
                ?: this["uc"]?.jsonPrimitive?.contentOrNull,
            characters = characters,
            hasCharacterPrompts = hasCharacters,
            settings = NovelAiImportedGenerationSettings(
                model = modelText.toNovelAiModelOrNull(),
                sizeTier = matchedSize?.first,
                aspectRatio = matchedSize?.second,
                count = this["n_samples"]?.jsonPrimitive?.intOrNull?.takeIf { it in 1..4 },
                steps = this["steps"]?.jsonPrimitive?.intOrNull?.takeIf { it in 1..50 },
                guidance = this["scale"]?.jsonPrimitive?.floatOrNull?.takeIf { it in 1f..10f },
                sampler = this["sampler"]?.jsonPrimitive?.contentOrNull?.let { sampler ->
                    NovelAiSampler.entries.firstOrNull { it.apiId.equals(sampler, ignoreCase = true) }
                }
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
            val data = bytes.copyOfRange(offset + 8, offset + 8 + length)
            parseTextChunk(type, data)?.let { (key, value) -> result[key] = value }
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
            ByteArrayOutputStream().use { output -> input.copyTo(output); output.toByteArray() }
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
