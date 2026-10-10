package com.example.chatbar.domain.backup

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import java.io.File
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class BackupArchiveTest {
    @get:Rule val temporary = TemporaryFolder()
    private val json = Json { encodeDefaults = true }
    private fun engine() = BackupArchive({}, { _, _, _ -> }, { File(it).inputStream() })

    private fun source(name: String): Pair<File, ByteArray> {
        val root = temporary.newFolder(name)
        val bytes = ByteArray(1024) { (it % 251).toByte() }
        val image = File(root, "images/generated/picture.png").also { it.parentFile!!.mkdirs(); it.writeBytes(bytes) }
        File(root, "entities/chat_messages/m.json").also {
            it.parentFile!!.mkdirs()
            it.writeText(json.encodeToString(ChatMessage(
                id = "m", sessionId = "s", role = MessageRole.ASSISTANT,
                content = "保留原文路径 " + image.absolutePath, images = listOf(image.absolutePath),
                alternatives = listOf("一", "二"), currentAlternativeIndex = 1,
                createdAt = 11, updatedAt = 12, sourceTurnId = "turn", sourceTurnOrder = 5
            )))
        }
        return root to bytes
    }

    @Test fun completeRoundTripPreservesIdentityTextRawMediaAndSecretsAcrossRoots() {
        for ((index, password) in listOf(null, "合成迁移密码🙂abc".toCharArray()).withIndex()) {
            val (source, media) = source("source-" + index)
            val originalJson = File(source, "entities/chat_messages/m.json").readText()
            val archive = temporary.newFile("archive-" + index)
            val preferences = BackupPreferences("synthetic-novel-key", "synthetic-fish-key")
            val summary = archive.outputStream().use {
                engine().create(source, temporary.newFolder("encode-" + index), it, password, 10, "fixture", preferences)
            }
            val target = temporary.newFolder("target-" + index)
            val work = temporary.newFolder("decode-" + index)
            val restored = archive.inputStream().use { engine().extract(it, password, work, target, 10) }
            assertEquals(summary, restored)
            val staged = File(work, "files")
            val message = json.decodeFromString<ChatMessage>(File(staged, "entities/chat_messages/m.json").readText())
            assertEquals("m", message.id)
            assertEquals("turn", message.sourceTurnId)
            assertEquals(5L, message.sourceTurnOrder)
            assertEquals(listOf("一", "二"), message.alternatives)
            assertEquals(1, message.currentAlternativeIndex)
            assertTrue(message.content.contains(source.absolutePath))
            assertEquals(File(target, "images/generated/picture.png").absolutePath, message.images.single())
            assertArrayEquals(media, File(staged, "images/generated/picture.png").readBytes())
            assertEquals(preferences, backupJson.decodeFromString<BackupPreferences>(File(work, "preferences.json").readText()))
            assertEquals(originalJson, File(source, "entities/chat_messages/m.json").readText())
        }
    }

    @Test fun wrongPasswordAndTruncationRejectBeforeReplacingCurrentData() {
        val (source, _) = source("source")
        val archive = temporary.newFile("archive")
        val password = "fixture-password".toCharArray()
        archive.outputStream().use { engine().create(source, temporary.newFolder("encode"), it, password, 5, "fixture", BackupPreferences()) }
        val target = temporary.newFolder("target")
        val original = File(target, "existing.json").also { it.writeText("original") }
        assertThrows(Exception::class.java) {
            archive.inputStream().use { engine().extract(it, "wrong-password".toCharArray(), temporary.newFolder("wrong"), target, 5) }
        }
        java.io.RandomAccessFile(archive, "rw").use { it.setLength(archive.length() - 24) }
        assertThrows(Exception::class.java) {
            archive.inputStream().use { engine().extract(it, password, temporary.newFolder("truncated"), target, 5) }
        }
        assertEquals("original", original.readText())
    }

    @Test fun newerAppArchiveAndEscapingPathsAreRejected() {
        val (source, _) = source("source")
        val archive = temporary.newFile("archive")
        archive.outputStream().use { engine().create(source, temporary.newFolder("encode"), it, null, 20, "fixture", BackupPreferences()) }
        assertThrows(IllegalArgumentException::class.java) {
            archive.inputStream().use { engine().extract(it, null, temporary.newFolder("decode"), temporary.newFolder("target"), 19) }
        }
        for (path in listOf("../escape", "images/../../escape", "/images/x", "images\\x", "entities//x")) {
            assertThrows(IllegalArgumentException::class.java) { BackupDataRegistry.target(source, path) }
        }
    }

    @Test fun missingOwnedMediaStopsExportAndLeavesSourceUntouched() {
        val (source, _) = source("source")
        val original = File(source, "entities/chat_messages/m.json").readText()
        File(source, "images/generated/picture.png").delete()
        assertThrows(IllegalStateException::class.java) {
            temporary.newFile().outputStream().use { engine().create(source, temporary.newFolder("encode"), it, null, 10, "fixture", BackupPreferences()) }
        }
        assertEquals(original, File(source, "entities/chat_messages/m.json").readText())
    }

    @Test fun legacyInlineSaveSlotKeepsEmbeddedBytesAndPortableKeyIdentity() {
        val root = temporary.newFolder("source")
        val old = File(root, "images/deleted.png").absolutePath
        val inline = kotlinx.serialization.json.buildJsonObject {
            put("schemaVersion", kotlinx.serialization.json.JsonPrimitive(7))
            put("images", kotlinx.serialization.json.JsonArray(listOf(kotlinx.serialization.json.JsonPrimitive(old))))
            put("imageResources", kotlinx.serialization.json.buildJsonObject {
                put(old, kotlinx.serialization.json.buildJsonObject {
                    put("fileName", kotlinx.serialization.json.JsonPrimitive("image.png"))
                    put("data", kotlinx.serialization.json.JsonPrimitive("QUJD"))
                })
            })
        }
        File(root, "entities/save_slots/legacy.json").also { it.parentFile!!.mkdirs(); it.writeText(inline.toString()) }
        val archive = temporary.newFile()
        archive.outputStream().use { engine().create(root, temporary.newFolder("encode"), it, null, 10, "fixture", BackupPreferences()) }
        val work = temporary.newFolder("decode")
        val target = temporary.newFolder("target")
        archive.inputStream().use { engine().extract(it, null, work, target, 10) }
        val restored = Json.parseToJsonElement(File(work, "files/entities/save_slots/legacy.json").readText()).jsonObject
        val resources = restored.getValue("imageResources").jsonObject
        assertEquals("QUJD", resources.values.single().jsonObject.getValue("data").jsonPrimitive.content)
        assertTrue(resources.keys.single().startsWith(target.absolutePath))
    }
}
