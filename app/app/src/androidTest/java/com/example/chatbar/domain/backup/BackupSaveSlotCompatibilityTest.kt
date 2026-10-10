package com.example.chatbar.domain.backup

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.chat.SaveSlotPackageStorage
import com.example.chatbar.domain.chat.OMITTED_SAVE_SLOT_IMAGE_PREFIX
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupSaveSlotCompatibilityTest {
    @Test fun legacyAndV8ArchivesKeepIdentityOmissionsAndOriginalPackageBytes() = runBlocking {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val workspace = File(base.cacheDir, "backup-fixture-" + UUID.randomUUID()).also(File::mkdirs)
        fun isolated(name: String) = object : ContextWrapper(base) {
            override fun getFilesDir() = File(workspace, name + "/files").also(File::mkdirs)
            override fun getNoBackupFilesDir() = File(workspace, name + "/private").also(File::mkdirs)
        }
        val source = isolated("source")
        val target = isolated("target")
        try {
            val media = File(source.filesDir, "images/fixture.png").also {
                it.parentFile!!.mkdirs()
                it.writeBytes(ByteArray(1024) { n -> n.toByte() })
            }
            val message = ChatMessage(id = "m", sessionId = "s", role = MessageRole.ASSISTANT,
                content = "fixture", images = listOf(media.path), sourceTurnId = "turn", sourceTurnOrder = 8,
                createdAt = 10, updatedAt = 11)
            val packages = SaveSlotPackageStorage(source)
            val packaged = packages.createPackage(
                baseSlot = SaveSlot(schemaVersion = 8, id = "slot-v8", sessionId = "s", name = "v8", createdAt = 15),
                imagePolicy = SaveSlotImagePolicy.ORIGINAL, includeAudio = false,
                messageSource = { emit -> emit(message) }, ragSource = {}, voices = emptyList())
            val missing = File(source.filesDir, "images/historically-deleted.png").path
            val legacy = SaveSlot(schemaVersion = 7, id = "slot-old", sessionId = "s", name = "old", createdAt = 12,
                messages = listOf(message.copy(images = listOf(missing, OMITTED_SAVE_SLOT_IMAGE_PREFIX + "old"))),
                imageResources = mapOf(missing to SaveSlotImageResource("image.png", "QUJD")))
            for (slot in listOf(packaged, legacy)) {
                File(source.filesDir, "entities/save_slots/" + slot.id + ".json").also {
                    it.parentFile!!.mkdirs(); it.writeText(backupJson.encodeToString(slot))
                }
            }
            BackupEntityValidator.validateTree(source, source.filesDir) {}
            fun engine() = BackupArchive({}, { _, _, _ -> }, { File(it).inputStream() })
            val archive = File(workspace, "fixture.cbbackup")
            archive.outputStream().use {
                engine().create(source.filesDir, File(workspace, "encode"), it, null, 1, "fixture", BackupPreferences())
            }
            val restore = File(workspace, "restore")
            archive.inputStream().use { engine().extract(it, null, restore, target.filesDir, 1) }
            val staged = File(restore, "files")
            BackupEntityValidator.validateTree(target, staged) {}
            val newLegacy = backupJson.decodeFromString<SaveSlot>(File(staged, "entities/save_slots/slot-old.json").readText())
            assertEquals(legacy.id, newLegacy.id)
            assertEquals("turn", newLegacy.messages.single().sourceTurnId)
            assertEquals("QUJD", newLegacy.imageResources.values.single().data)
            assertEquals(legacy.messages.single().images[1], newLegacy.messages.single().images[1])
            assertTrue(newLegacy.imageResources.keys.single().startsWith(target.filesDir.path))
            val fileName = requireNotNull(packaged.packageRef).fileName
            assertArrayEquals(File(source.filesDir, "save_slot_packages/" + fileName).readBytes(),
                File(staged, "save_slot_packages/" + fileName).readBytes())
        } finally { workspace.deleteRecursively() }
    }
}
