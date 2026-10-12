package com.example.chatbar.domain.backup

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.MomentPost
import com.example.chatbar.data.local.entity.GeneratedImageMetadata
import java.io.File
import java.util.UUID
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.encodeToString
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class BackupDataSelectionTest {
    @Test fun omittedMomentImagesSkipMissingBodyAndMetadataButRetainAvatarAndValidateRestoredData() = runBlocking {
        withFixture { context ->
            val avatar = write(context, "images/avatar.png", "avatar bytes")
            val missing = File(context.filesDir, "images/missing-body.png")
            val post = MomentPost(id = "post", characterCardId = "old-card", sessionId = "old-session",
                senderName = "历史角色", senderAvatar = avatar.path, text = "原文", imagePath = missing.path,
                generatedImageMetadata = GeneratedImageMetadata(missing.path, "prompt", negativePrompt = "negative", sizePreset = "fixture", width = 10, height = 20),
                scheduledAt = 1, generatedAt = 2)
            val source = write(context, "entities/moment_posts/post.json", backupJson.encodeToString(post))
            val before = source.readText()
            val catalog = BackupExportCatalog.read(context.filesDir) {}
            val selection = AppBackupSelection(moments = true, momentsImages = false)
            BackupEntityValidator.validateTree(context, context.filesDir, exportSelectionOnly = true, selection = catalog.plan(selection)) {}
            val error = runCatching {
                BackupEntityValidator.validateTree(context, context.filesDir, exportSelectionOnly = true,
                    selection = catalog.plan(selection.copy(momentsImages = true))) {}
            }.exceptionOrNull()
            assertNotNull(error)
            assertTrue(error!!.message.orEmpty().contains("missing-body.png"))
            val archive = File(context.noBackupFilesDir, "selected.cbbackup")
            fun engine() = BackupArchive({}, { _, _, _ -> }, { File(it).inputStream() })
            archive.outputStream().use { engine().create(context.filesDir, File(context.noBackupFilesDir, "encode"), it,
                null, 1, "fixture", BackupPreferences(), catalog.plan(selection)) }
            val work = File(context.noBackupFilesDir, "decode")
            archive.inputStream().use { engine().extract(it, null, work, context.filesDir, 1) }
            BackupEntityValidator.validateTree(context, File(work, "files")) {}
            assertEquals(post.copy(imagePath = null, generatedImageMetadata = null),
                backupJson.decodeFromString<MomentPost>(File(work, "files/entities/moment_posts/post.json").readText()))
            assertTrue(File(work, "files/images/avatar.png").isFile)
            assertTrue(!File(work, "files/images/missing-body.png").exists())
            assertEquals(before, source.readText())
        }
    }

    @Test fun selectiveValidationIgnoresUnselectedBrokenResourcesAndRemovedChatPictures() = runBlocking {
        withFixture { context ->
            for (id in listOf("chosen", "excluded")) {
                write(context, "entities/character_cards/$id.json", backupJson.encodeToString(
                    CharacterCard(id = id, name = id, createdAt = 1, updatedAt = 2)))
                write(context, "entities/chat_sessions/$id.json", backupJson.encodeToString(
                    ChatSession(id = id, characterCardId = id, title = id, createdAt = 1, updatedAt = 2)))
                write(context, "entities/chat_messages/$id.json", backupJson.encodeToString(
                    ChatMessage(id = id, sessionId = id, role = MessageRole.USER, content = "fixture",
                        images = listOf(File(context.filesDir, "images/$id-missing.png").path), createdAt = 1, updatedAt = 2)))
            }
            val catalog = BackupExportCatalog.read(context.filesDir) {}
            val selection = AppBackupSelection(characterIds = setOf("chosen"), sessions = mapOf("chosen" to BackupSessionChoice(images = false)))
            BackupEntityValidator.validateTree(context, context.filesDir, exportSelectionOnly = true, selection = catalog.plan(selection)) {}
            val error = runCatching {
                BackupEntityValidator.validateTree(context, context.filesDir, exportSelectionOnly = true,
                    selection = catalog.plan(selection.copy(sessions = mapOf("chosen" to BackupSessionChoice(images = true))))) {}
            }.exceptionOrNull()
            assertNotNull(error)
            assertTrue(error!!.message.orEmpty().contains("chosen-missing.png"))
            val archive = File(context.noBackupFilesDir, "selected.cbbackup")
            fun engine() = BackupArchive({}, { _, _, _ -> }, { File(it).inputStream() })
            archive.outputStream().use { engine().create(context.filesDir, File(context.noBackupFilesDir, "encode"), it,
                null, 1, "fixture", BackupPreferences(), catalog.plan(selection)) }
            val work = File(context.noBackupFilesDir, "decode")
            archive.inputStream().use { engine().extract(it, null, work, context.filesDir, 1) }
            BackupEntityValidator.validateTree(context, File(work, "files")) {}
            assertTrue(!File(work, "files/entities/chat_messages/excluded.json").exists())
            assertTrue(!File(work, "files/images").exists())
        }
    }

    @Test fun exportValidatesSelectedRecordsWhileImportRejectsUnsupportedTypes() = runBlocking {
        withFixture { context ->
            val record = write(context, "entities/chat_messages/m.json", backupJson.encodeToString(
                ChatMessage(id = "m", sessionId = "s", role = MessageRole.ASSISTANT, content = "fixture",
                    createdAt = 10, updatedAt = 11)
            ))
            write(context, "mozilla/profiles/runtime.json", "{invalid runtime JSON")
            write(context, "entities/vendor_runtime/record.json", "{invalid runtime JSON")
            BackupEntityValidator.validateTree(context, context.filesDir, exportSelectionOnly = true) {}
            val unsupported = runCatching {
                BackupEntityValidator.validateTree(context, context.filesDir) {}
            }.exceptionOrNull()
            assertNotNull(unsupported)
            assertTrue(unsupported!!.message.orEmpty().contains("vendor_runtime"))
            record.writeText("{\"id\":\"m\",\"sessionId\":\"s\",\"role\":\"ASSISTANT\",\"content\":\"fixture\",\"createdAt\":\"broken\",\"updatedAt\":11}")
            val corrupt = runCatching {
                BackupEntityValidator.validateTree(context, context.filesDir, exportSelectionOnly = true) {}
            }.exceptionOrNull()
            assertTrue(corrupt is IllegalStateException)
            assertTrue(corrupt!!.message.orEmpty().contains("entities/chat_messages/m.json"))
        }
    }

    @Test fun referencedFileInUnselectedDirectoryMustExistAndIsValidatedAfterRemapping() = runBlocking {
        withFixture { context ->
            val image = write(context, "vendor-runtime/selected.png", "synthetic original bytes")
            write(context, "entities/chat_messages/m.json", backupJson.encodeToString(
                ChatMessage(id = "m", sessionId = "s", role = MessageRole.ASSISTANT,
                    content = "fixture", images = listOf(image.path), createdAt = 10, updatedAt = 11)
            ))
            BackupEntityValidator.validateTree(context, context.filesDir, exportSelectionOnly = true) {}
            val workspace = File(context.noBackupFilesDir, "archive")
            val archive = File(context.noBackupFilesDir, "fixture.cbbackup")
            fun engine() = BackupArchive({}, { _, _, _ -> }, { File(it).inputStream() })
            archive.outputStream().use {
                engine().create(context.filesDir, workspace, it, null, 1, "fixture", BackupPreferences())
            }
            val staged = File(context.noBackupFilesDir, "restore")
            archive.inputStream().use { engine().extract(it, null, staged, context.filesDir, 1) }
            BackupEntityValidator.validateTree(context, File(staged, "files")) {}
            image.delete()
            val missing = runCatching {
                BackupEntityValidator.validateTree(context, context.filesDir, exportSelectionOnly = true) {}
            }.exceptionOrNull()
            assertTrue(missing is IllegalStateException)
        }
    }

    @Test fun disabledMomentsKeepHistoricalMissingAvatarAndWarnBeforeAndAfterMigration() = runBlocking {
        withFixture { context ->
            write(context, "entities/app_settings.json", backupJson.encodeToString(AppSettings(momentsEnabled = false)))
            val image = write(context, "images/moment.png", "original bytes")
            val missing = File(context.filesDir, "images/old-avatar.png")
            val post = MomentPost(id = "post", characterCardId = "old-card", sessionId = "old-session",
                senderName = "历史人物", senderAvatar = missing.path, text = "原文", imagePath = image.path,
                userLiked = true, scheduledAt = 1, generatedAt = 2)
            write(context, "entities/moment_posts/post.json", backupJson.encodeToString(post))
            val validation = BackupEntityValidator.validateTree(context, context.filesDir, exportSelectionOnly = true) {}
            assertEquals(1, validation.missingHistoricalAvatars)
            assertTrue(validation.warnings.isNotEmpty())
            val archive = File(context.noBackupFilesDir, "fixture.cbbackup")
            fun engine() = BackupArchive({}, { _, _, _ -> }, { File(it).inputStream() })
            archive.outputStream().use {
                engine().create(context.filesDir, File(context.noBackupFilesDir, "encode"), it,
                    null, 1, "fixture", BackupPreferences())
            }
            val target = object : ContextWrapper(context) {
                override fun getFilesDir() = File(context.noBackupFilesDir, "target").also(File::mkdirs)
            }
            val work = File(context.noBackupFilesDir, "decode")
            archive.inputStream().use { engine().extract(it, null, work, target.filesDir, 1) }
            val staged = File(work, "files")
            val restored = BackupEntityValidator.validateTree(target, staged) {}
            assertEquals(1, restored.missingHistoricalAvatars)
            assertTrue(restored.warnings.isNotEmpty())
            val settings = backupJson.decodeFromString<AppSettings>(File(staged, "entities/app_settings.json").readText())
            assertEquals(false, settings.momentsEnabled)
            val savedPost = backupJson.decodeFromString<MomentPost>(File(staged, "entities/moment_posts/post.json").readText())
            assertEquals(post.copy(senderAvatar = File(target.filesDir, "images/old-avatar.png").path,
                imagePath = File(target.filesDir, "images/moment.png").path), savedPost)
        }
    }

    @Test fun currentCardAvatarAndMomentOriginalRemainRequired() = runBlocking {
        withFixture { context ->
            val missing = File(context.filesDir, "images/missing.png")
            val card = CharacterCard(id = "card", name = "角色", avatar = missing.path,
                createdAt = 1, updatedAt = 2)
            val record = write(context, "entities/character_cards/card.json", backupJson.encodeToString(card))
            val avatarError = runCatching {
                BackupEntityValidator.validateTree(context, context.filesDir, exportSelectionOnly = true) {}
            }.exceptionOrNull()
            assertTrue(avatarError is IllegalStateException)
            assertTrue(avatarError!!.message.orEmpty().contains("字段：avatar"))
            record.delete()
            val post = MomentPost(id = "post", characterCardId = "card", sessionId = "session",
                senderName = "角色", text = "原文", imagePath = missing.path, scheduledAt = 1, generatedAt = 2)
            write(context, "entities/moment_posts/post.json", backupJson.encodeToString(post))
            val imageError = runCatching {
                BackupEntityValidator.validateTree(context, context.filesDir, exportSelectionOnly = true) {}
            }.exceptionOrNull()
            assertTrue(imageError is IllegalStateException)
            assertTrue(imageError!!.message.orEmpty().contains("字段：imagePath"))
        }
    }

    private suspend fun withFixture(action: suspend (Context) -> Unit) {
        val base = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(base.cacheDir, "backup-selection-" + UUID.randomUUID()).also(File::mkdirs)
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = File(directory, "files").also(File::mkdirs)
            override fun getNoBackupFilesDir() = File(directory, "private").also(File::mkdirs)
        }
        try { action(context) } finally { directory.deleteRecursively() }
    }

    private fun write(context: Context, relative: String, value: String): File =
        File(context.filesDir, relative).also { it.parentFile!!.mkdirs(); it.writeText(value) }
}
