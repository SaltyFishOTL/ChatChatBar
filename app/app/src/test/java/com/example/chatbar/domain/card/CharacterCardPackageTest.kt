package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.CharacterEditMode
import com.example.chatbar.data.local.entity.PresetManifest
import com.example.chatbar.data.local.entity.PresetType
import com.example.chatbar.domain.image.NovelAiImageModel
import java.io.File
import java.util.Base64
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterCardPackageTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun packageContainsPortableResourcesOnly() {
        val packageData = CharacterCardPackage(
            card = PackagedCharacterCard(
                name = "测试卡",
                botName = "第一行\n第二行",
                defaultNovelAiImageModel = NovelAiImageModel.V5_FULL,
                avatarResourceId = "avatar",
                characters = listOf(PackagedCharacter(name = "角色"))
            ),
            documents = listOf(PackagedDocument("设定.md", "md", "正文")),
            images = mapOf("avatar" to PackagedImage("avatar.png", "YWJj"))
        )

        packageData.validateForImport()
        val encoded = json.encodeToString(CharacterCardPackage.serializer(), packageData)

        assertFalse(encoded.contains("filePath"))
        assertFalse(encoded.contains("customDocuments"))
        assertFalse(encoded.contains("createdAt"))
        assertFalse(encoded.contains("updatedAt"))
        assertFalse(encoded.contains("pendingSpeakerRenameTasks"))
        assertTrue(encoded.contains("\"schemaVersion\":8"))
        assertEquals(packageData, json.decodeFromString(CharacterCardPackage.serializer(), encoded))
    }

    @Test
    fun legacyPackageSchemasDefaultBotNameToBlank() {
        (3..7).forEach { schemaVersion ->
            val packageData = json.decodeFromString(
                CharacterCardPackage.serializer(),
                """{"schemaVersion":$schemaVersion,"card":{"name":"旧角色卡"}}"""
            )

            packageData.validateForImport()

            assertEquals("", packageData.card.botName)
            assertEquals(null, packageData.card.defaultNovelAiImageModel)
        }
    }

    @Test
    fun validationRejectsMissingImageResource() {
        val packageData = CharacterCardPackage(
            card = PackagedCharacterCard(name = "测试卡", avatarResourceId = "missing")
        )

        val error = runCatching { packageData.validateForImport() }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("missing"))
    }

    @Test
    fun validationRejectsVersionTwo() {
        val packageData = CharacterCardPackage(
            schemaVersion = 2,
            card = PackagedCharacterCard(name = "旧角色卡")
        )

        val error = runCatching { packageData.validateForImport() }.exceptionOrNull()

        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("schemaVersion"))
    }

    @Test
    fun legacyEmptyCharacterPlaceholderIsRemovedBeforeValidation() {
        val packageData = CharacterCardPackage(
            card = PackagedCharacterCard(
                name = "自由模式角色卡",
                editMode = CharacterEditMode.FREEFORM,
                freeformCharacterText = "完整人物设定",
                characters = listOf(
                    PackagedCharacter(name = ""),
                    PackagedCharacter(name = "有效人物")
                )
            )
        )

        val normalized = packageData.withoutEmptyCharacterPlaceholders()
        normalized.validateForImport()

        assertEquals(listOf("有效人物"), normalized.card.characters.map(PackagedCharacter::name))
    }

    @Test
    fun unnamedCharacterWithContentIsPreservedAndRejected() {
        val packageData = CharacterCardPackage(
            card = PackagedCharacterCard(
                name = "测试卡",
                characters = listOf(PackagedCharacter(name = "", profile = "不能丢失的设定"))
            )
        )

        val normalized = packageData.withoutEmptyCharacterPlaceholders()
        val error = runCatching { normalized.validateForImport() }.exceptionOrNull()

        assertEquals(1, normalized.card.characters.size)
        assertTrue(error is IllegalArgumentException)
        assertTrue(error?.message.orEmpty().contains("人物名称不能为空"))
    }

    @Test
    fun chatBarPngTextChunkRoundTripsPackagePayload() {
        val png = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+/p9sAAAAASUVORK5CYII="
        )
        val packageData = CharacterCardPackage(
            card = PackagedCharacterCard(
                name = "PNG 角色",
                characters = listOf(PackagedCharacter(name = "角色"))
            )
        )
        val rawJson = json.encodeToString(CharacterCardPackage.serializer(), packageData)
        val payload = Base64.getEncoder().encodeToString(rawJson.toByteArray(Charsets.UTF_8))

        val exported = PngTextChunks.insertTextChunk(png, PngTextChunks.CHATBAR_CHARACTER_KEYWORD, payload)
        val extracted = requireNotNull(PngTextChunks.extractTextChunk(exported, PngTextChunks.CHATBAR_CHARACTER_KEYWORD))
        val decoded = json.decodeFromString(
            CharacterCardPackage.serializer(),
            String(Base64.getDecoder().decode(extracted), Charsets.UTF_8)
        )

        assertTrue(PngTextChunks.isPng(exported))
        assertEquals(packageData, decoded)
    }

    @Test
    fun bundledCharacterCardsPassStableContractValidation() {
        val assetsDirectory = listOf(File("app/src/main/assets"), File("src/main/assets"))
            .first { it.isDirectory }
        val manifest = json.decodeFromString(
            PresetManifest.serializer(),
            File(assetsDirectory, "presets/manifest.json").readText()
        )
        val files = manifest.entries
            .filter { it.type == PresetType.CHARACTER }
            .map { File(assetsDirectory, it.file) }

        assertTrue(files.isNotEmpty())
        files.forEach { file ->
            assertTrue("预置角色文件不存在：${file.path}", file.isFile)
            val packageData = json.decodeFromString(CharacterCardPackage.serializer(), file.readText())
            packageData.validateForImport()
        }
    }
}
