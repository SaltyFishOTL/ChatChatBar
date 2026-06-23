package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.PresetManifest
import com.example.chatbar.data.local.entity.PresetType
import java.io.File
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
        assertEquals(packageData, json.decodeFromString(CharacterCardPackage.serializer(), encoded))
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
    fun bundledCharacterCardsUseVersionThreeSchema() {
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
            val raw = file.readText()
            val packageData = json.decodeFromString(CharacterCardPackage.serializer(), raw)
            packageData.validateForImport()
            assertEquals(3, packageData.schemaVersion)
            assertFalse(raw.contains("\"filePath\""))
            assertFalse(raw.contains("\"customDocuments\""))
            assertFalse(raw.contains("\"createdAt\""))
            assertFalse(raw.contains("\"updatedAt\""))
        }
    }
}
