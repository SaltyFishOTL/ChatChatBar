package com.example.chatbar.domain.card

import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject

enum class SharedImportKind {
    CHARACTER,
    FORMAT,
    WORLD_BOOK,
    MODEL_TEMPLATE,
    IMAGE,
    UNKNOWN
}

data class SharedImportImageInfo(
    val mimeType: String,
    val width: Int,
    val height: Int,
    val animatedGif: Boolean
)

sealed interface SharedImportInspection {
    val kind: SharedImportKind

    data class Character(val request: CharacterCardImportRequest) : SharedImportInspection {
        override val kind = SharedImportKind.CHARACTER
    }

    data class Format(val packageData: FormatCardPackage) : SharedImportInspection {
        override val kind = SharedImportKind.FORMAT
    }

    data class WorldBook(val packageData: WorldBookPackage) : SharedImportInspection {
        override val kind = SharedImportKind.WORLD_BOOK
    }

    data class ModelTemplate(val packageData: ModelTemplatePackage) : SharedImportInspection {
        override val kind = SharedImportKind.MODEL_TEMPLATE
    }

    data class Image(val info: SharedImportImageInfo) : SharedImportInspection {
        override val kind = SharedImportKind.IMAGE
    }

    data class Unknown(val textLike: Boolean) : SharedImportInspection {
        override val kind = SharedImportKind.UNKNOWN
    }
}

object SharedImportClassifier {
    private val json = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun inspect(
        bytes: ByteArray,
        displayName: String = "共享文件",
        imageInfo: SharedImportImageInfo? = null
    ): SharedImportInspection {
        if (PngTextChunks.isPng(bytes)) {
            val chatBarPayload = PngTextChunks.extractTextChunk(bytes, PngTextChunks.CHATBAR_CHARACTER_KEYWORD)
            if (chatBarPayload != null) {
                return runCatching { decodeChatBarPng(chatBarPayload) }
                    .getOrElse { SharedImportInspection.Unknown(textLike = false) }
            }
            val sillyTavernPayload = SillyTavernCardParser.extractCharaChunk(bytes)
            if (sillyTavernPayload != null) {
                return runCatching { decodeSillyTavernCharacter(sillyTavernPayload, bytes) }
                    .getOrElse { SharedImportInspection.Unknown(textLike = false) }
            }
        }

        val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF").trim()
        val root = runCatching { json.parseToJsonElement(text).jsonObject }.getOrNull()
        if (root != null) return inspectJson(text, root, displayName)
        if (imageInfo != null) return SharedImportInspection.Image(imageInfo)
        return SharedImportInspection.Unknown(textLike = looksLikeText(bytes))
    }

    fun decodeAs(
        bytes: ByteArray,
        kind: SharedImportKind,
        displayName: String = "共享文件"
    ): SharedImportInspection {
        val text = bytes.toString(Charsets.UTF_8).removePrefix("\uFEFF").trim()
        return when (kind) {
            SharedImportKind.CHARACTER -> {
                if (PngTextChunks.isPng(bytes)) {
                    PngTextChunks.extractTextChunk(bytes, PngTextChunks.CHATBAR_CHARACTER_KEYWORD)?.let {
                        return decodeChatBarPng(it)
                    }
                    SillyTavernCardParser.extractCharaChunk(bytes)?.let {
                        return decodeSillyTavernCharacter(it, bytes)
                    }
                    error("PNG 中未找到 ChatBar 或 SillyTavern 角色卡数据")
                }
                decodeCharacterJson(text)
            }
            SharedImportKind.FORMAT -> SharedImportInspection.Format(
                json.decodeFromString(FormatCardPackage.serializer(), text).also {
                    it.validateForImport()
                }
            )
            SharedImportKind.WORLD_BOOK -> SharedImportInspection.WorldBook(
                WorldBookTransferService(json).decode(text, fallbackName(displayName, "导入世界书"))
            )
            SharedImportKind.MODEL_TEMPLATE -> SharedImportInspection.ModelTemplate(
                json.decodeFromString(ModelTemplatePackage.serializer(), text).also {
                    it.validateForImport()
                }
            )
            SharedImportKind.IMAGE,
            SharedImportKind.UNKNOWN -> error("该目标不能手动解析")
        }
    }

    private fun inspectJson(text: String, root: JsonObject, displayName: String): SharedImportInspection {
        val candidates = buildList {
            if (root.containsKey("card")) add(SharedImportKind.CHARACTER)
            if (root.containsKey("book")) add(SharedImportKind.WORLD_BOOK)
            if (root.containsKey("displayName") && root.containsKey("modelName")) {
                add(SharedImportKind.MODEL_TEMPLATE)
            }
            if (root.containsKey("name") && root.containsKey("content")) add(SharedImportKind.FORMAT)
            if (root.containsKey("entries")) add(SharedImportKind.WORLD_BOOK)
            if (looksLikeSillyTavernV2(root) || looksLikeSillyTavernV1(root)) {
                add(SharedImportKind.CHARACTER)
            }
        }.distinct()
        if (candidates.size != 1) return SharedImportInspection.Unknown(textLike = true)
        return runCatching { decodeAs(text.toByteArray(Charsets.UTF_8), candidates.single(), displayName) }
            .getOrElse { SharedImportInspection.Unknown(textLike = true) }
    }

    private fun decodeChatBarPng(payload: String): SharedImportInspection.Character {
        val rawJson = if (payload.trimStart().startsWith("{")) {
            payload
        } else {
            String(Base64.getDecoder().decode(payload), Charsets.UTF_8)
        }
        val packageData = json.decodeFromString(CharacterCardPackage.serializer(), rawJson)
            .withoutEmptyCharacterPlaceholders()
            .also(CharacterCardPackage::validateForImport)
        return SharedImportInspection.Character(CharacterCardImportRequest(packageData))
    }

    private fun decodeCharacterJson(text: String): SharedImportInspection.Character {
        val root = json.parseToJsonElement(text).jsonObject
        val packageData = when {
            root.containsKey("card") -> json.decodeFromString(CharacterCardPackage.serializer(), text)
                .withoutEmptyCharacterPlaceholders()
                .also(CharacterCardPackage::validateForImport)
            looksLikeSillyTavernV2(root) || looksLikeSillyTavernV1(root) ->
                SillyTavernCardMapper.toCharacterCardPackage(SillyTavernCardParser.parseJson(text))
                    .also(CharacterCardPackage::validateForImport)
            else -> error("文件不是受支持的 ChatBar 或 SillyTavern 角色卡")
        }
        return SharedImportInspection.Character(CharacterCardImportRequest(packageData))
    }

    private fun decodeSillyTavernCharacter(rawJson: String, pngBytes: ByteArray): SharedImportInspection.Character {
        val root = json.parseToJsonElement(rawJson).jsonObject
        require(looksLikeSillyTavernV2(root) || looksLikeSillyTavernV1(root)) {
            "PNG 中的 Chara 数据不是受支持的 SillyTavern 角色卡"
        }
        val packageData = SillyTavernCardMapper.toCharacterCardPackage(
            SillyTavernCardParser.parseJson(rawJson, pngBytes)
        ).also(CharacterCardPackage::validateForImport)
        return SharedImportInspection.Character(CharacterCardImportRequest(packageData))
    }

    private fun looksLikeSillyTavernV2(root: JsonObject): Boolean =
        root.containsKey("spec") && root["data"] is JsonObject

    private fun looksLikeSillyTavernV1(root: JsonObject): Boolean =
        root.containsKey("name") && listOf(
            "description",
            "personality",
            "scenario",
            "first_mes",
            "mes_example"
        ).any(root::containsKey)

    private fun fallbackName(displayName: String, default: String): String =
        displayName.substringAfterLast('/').substringBeforeLast('.').trim().ifBlank { default }

    private fun looksLikeText(bytes: ByteArray): Boolean {
        if (bytes.isEmpty()) return true
        val sample = bytes.take(4096)
        return sample.none { byte ->
            val value = byte.toInt() and 0xff
            value == 0 || value in 1..8 || value in 14..31
        }
    }
}
