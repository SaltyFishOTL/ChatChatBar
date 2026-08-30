package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.ModelTemplate
import java.util.Base64
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedImportClassifierTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun classifiesEveryChatBarJsonPackage() {
        val character = json.encodeToString(
            CharacterCardPackage.serializer(),
            CharacterCardPackage(card = PackagedCharacterCard(name = "林雾"))
        )
        val format = json.encodeToString(
            FormatCardPackage.serializer(),
            FormatCardPackage(name = "格式", content = "内容")
        )
        val worldBook = """{"schemaVersion":1,"book":{"id":"b1","name":"设定集","entries":[]}}"""
        val model = json.encodeToString(
            ModelTemplatePackage.serializer(),
            ModelTemplatePackage(
                displayName = "DeepSeek",
                baseUrl = "https://example.com",
                modelName = "deepseek-chat",
                isMultimodal = false,
                templateType = ModelTemplate.OPENAI,
                customParams = emptyMap()
            )
        )

        assertEquals(SharedImportKind.CHARACTER, inspectText(character).kind)
        assertEquals(SharedImportKind.FORMAT, inspectText(format).kind)
        assertEquals(SharedImportKind.WORLD_BOOK, inspectText(worldBook).kind)
        assertEquals(SharedImportKind.MODEL_TEMPLATE, inspectText(model).kind)
    }

    @Test
    fun classifiesSillyTavernV1AndV2Characters() {
        val v1 = """{"name":"V1","description":"角色描述","first_mes":"你好"}"""
        val v2 = """{"spec":"chara_card_v2","data":{"name":"V2","description":"角色描述","first_mes":"你好"}}"""

        assertEquals(SharedImportKind.CHARACTER, inspectText(v1).kind)
        assertEquals(SharedImportKind.CHARACTER, inspectText(v2).kind)
    }

    @Test
    fun classifiesSillyTavernWorldInfoObjectAndArrayEntries() {
        val objectEntries = """{"name":"世界","entries":{"0":{"key":["城镇"],"content":"内容"}}}"""
        val arrayEntries = """{"entries":[{"keys":["城镇"],"content":"内容"}]}"""

        assertEquals(SharedImportKind.WORLD_BOOK, inspectText(objectEntries).kind)
        assertEquals(SharedImportKind.WORLD_BOOK, inspectText(arrayEntries, "array-world.json").kind)
    }

    @Test
    fun classifiesChatBarAndSillyTavernPngCharactersBeforePlainImage() {
        val basePng = onePixelPng()
        val characterJson = json.encodeToString(
            CharacterCardPackage.serializer(),
            CharacterCardPackage(card = PackagedCharacterCard(name = "ChatBar"))
        )
        val chatBarPng = PngTextChunks.insertTextChunk(
            basePng,
            PngTextChunks.CHATBAR_CHARACTER_KEYWORD,
            Base64.getEncoder().encodeToString(characterJson.toByteArray())
        )
        val stJson = """{"spec":"chara_card_v2","data":{"name":"ST","description":"描述"}}"""
        val stPng = PngTextChunks.insertTextChunk(
            basePng,
            "Chara",
            Base64.getEncoder().encodeToString(stJson.toByteArray())
        )
        val imageInfo = SharedImportImageInfo("image/png", 1, 1, animatedGif = false)

        assertEquals(SharedImportKind.CHARACTER, SharedImportClassifier.inspect(chatBarPng, imageInfo = imageInfo).kind)
        assertEquals(SharedImportKind.CHARACTER, SharedImportClassifier.inspect(stPng, imageInfo = imageInfo).kind)
        assertEquals(SharedImportKind.IMAGE, SharedImportClassifier.inspect(basePng, imageInfo = imageInfo).kind)
    }

    @Test
    fun novelAiAndOrdinaryImagesAlwaysRemainImageActions() {
        val png = onePixelPng()
        val pngInfo = SharedImportImageInfo("image/png", 832, 1216, animatedGif = false)
        val gifInfo = SharedImportImageInfo("image/gif", 320, 320, animatedGif = true)

        assertEquals(SharedImportKind.IMAGE, SharedImportClassifier.inspect(png, "novelai.png", pngInfo).kind)
        val gif = SharedImportClassifier.inspect("GIF89a-data".toByteArray(), "animated.gif", gifInfo)
        assertTrue(gif is SharedImportInspection.Image && gif.info.animatedGif)
    }

    @Test
    fun bomJsonIsRecognized() {
        val format = "\uFEFF" + json.encodeToString(
            FormatCardPackage.serializer(),
            FormatCardPackage(name = "格式", content = "内容")
        )
        assertEquals(SharedImportKind.FORMAT, inspectText(format).kind)
    }

    @Test
    fun ambiguousInvalidAndForeignTextStayUnknown() {
        val ambiguous = """{"card":{},"book":{},"name":"冲突","content":"内容"}"""
        val invalidFormat = """{"name":"空格式","content":""}"""

        assertEquals(SharedImportKind.UNKNOWN, inspectText(ambiguous).kind)
        assertEquals(SharedImportKind.UNKNOWN, inspectText(invalidFormat).kind)
        assertEquals(SharedImportKind.UNKNOWN, inspectText("普通分享文本").kind)
        assertEquals(SharedImportKind.UNKNOWN, inspectText("{}").kind)
    }

    @Test
    fun manualTargetUsesStrictDecoder() {
        val format = """{"schemaVersion":2,"name":"格式","content":"内容"}""".toByteArray()
        val decoded = SharedImportClassifier.decodeAs(format, SharedImportKind.FORMAT)
        assertEquals(SharedImportKind.FORMAT, decoded.kind)

        val characterFailure = runCatching {
            SharedImportClassifier.decodeAs(format, SharedImportKind.CHARACTER)
        }.exceptionOrNull()
        assertTrue(characterFailure?.message?.contains("不是受支持") == true)
    }

    private fun inspectText(text: String, displayName: String = "shared.json"): SharedImportInspection =
        SharedImportClassifier.inspect(text.toByteArray(Charsets.UTF_8), displayName)

    private fun onePixelPng(): ByteArray = Base64.getDecoder().decode(
        "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mNk+A8AAQUBAScY42YAAAAASUVORK5CYII="
    )
}
