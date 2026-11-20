package com.example.chatbar.ui.chat

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.FishAudioVoiceBinding
import com.example.chatbar.data.local.entity.GeneratedVoiceMessage
import com.example.chatbar.data.local.entity.GeneratedImageMetadata
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.SaveSlot
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.data.repository.VoiceMessageRepository
import com.example.chatbar.domain.backup.BackupDataRegistry
import com.example.chatbar.domain.chat.MessageAlternativeVersionPolicy
import com.example.chatbar.domain.deletion.ReplyRemovalCoordinator
import com.example.chatbar.domain.image.NovelAiImageStorage
import com.example.chatbar.domain.voice.FishAudioStorage
import com.example.chatbar.domain.voice.VoiceGenerationPolicy
import java.io.File
import java.util.UUID
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test

class ReplyRemovalDurabilityTest {
    @Test fun failedVoiceRecordDeleteKeepsAudioUntilColdRetry() = verifyFailure("generated_voice_messages", versionOnly = true)
    @Test fun failedAnchorDeleteKeepsAudioUntilColdRetry() = verifyFailure("voice_anchor_states", versionOnly = true)
    @Test fun missingMessageAfterIndexWriteFailureFinishesAllMediaOnColdRetry() = verifyFailure("chat_message_indexes")
    @Test fun missingMessageAfterSessionWriteFailureRepairsPreviewAndTurnOnColdRetry() = verifyFailure("chat_sessions")
    @Test fun recoveredSameIdMessageRetainsRestoredAttachments() = verifyFailure("chat_message_indexes", restoreOwner = true)
    @Test fun restoredDifferentImageRetainsNewFilesAndReleasesOldImage() =
        verifyFailure("chat_message_indexes", restoreOwner = true, replaceImage = true)
    @Test fun legacyArchiveStillOwnsAudioAndImageAfterMessageRemoval() = withFixture { fixture ->
        val session = fixture.createSession()
        val message = fixture.addReply(session, versionOnly = false)
        val voice = fixture.addVoice(message)
        val aliased = message.copy(images = listOf(File(message.images.single()).toURI().toString()))
        val slot = SaveSlot.create(session.id, "Legacy fixture", messages = listOf(aliased)).copy(
            schemaVersion = 4, voiceMessages = listOf(voice.copy(audioPath = File(voice.audioPath).toURI().toString()))
        )
        fixture.storage.saveEntityUncached("save_slots", slot.id, slot, SaveSlot.serializer())
        assertNull(fixture.removals.remove(message, null, null))
        assertNull(fixture.chats.getMessage(message.id, session.id))
        assertNull(fixture.voices.get(voice.id))
        assertTrue(File(voice.audioPath).isFile)
        assertTrue(File(message.images.single()).isFile)
        fixture.audio.cleanupOrphanFiles(emptySet(), fixture.storage)
        assertTrue("The startup sweep must also respect legacy archive ownership", File(voice.audioPath).isFile)
    }

    @Test fun anotherMessageImageMetadataKeepsItsOwnedImage() = withFixture { fixture ->
        val session = fixture.createSession()
        val message = fixture.addReply(session, versionOnly = false)
        val file = File(message.images.single())
        fixture.chats.addMessage(ChatMessage.create(session.id, MessageRole.ASSISTANT, "Metadata owner").copy(
            generatedImageMetadata = listOf(GeneratedImageMetadata(
                file.toURI().toString(), "synthetic", emptyList(), "", "normal", 64, 64
            ))
        ))
        assertNull(fixture.removals.remove(message, null, null))
        assertTrue(file.isFile)
    }

    @Test fun legacyVoiceWithSameMessageIdInAnotherSessionIsNotMigrated() = withFixture { fixture ->
        val session = fixture.createSession()
        val otherSession = fixture.createSession()
        val message = fixture.addReply(session, versionOnly = true)
        val voice = fixture.addVoice(message)
        fixture.voices.save(voice.copy(messageVersionId = null))
        val otherVoice = voice.copy(id = "other-legacy", sessionId = otherSession.id, messageVersionId = null)
        fixture.voices.save(otherVoice)
        val version = MessageAlternativeVersionPolicy.activeVersionId(message)
        val replacement = requireNotNull(MessageAlternativeVersionPolicy.remove(message, version))
        assertNull(fixture.removals.remove(message, replacement, version))
        assertEquals(otherVoice, fixture.voices.get(otherVoice.id))
        assertTrue(File(otherVoice.audioPath).isFile)
    }

    private fun verifyFailure(
        entity: String,
        versionOnly: Boolean = false,
        restoreOwner: Boolean = false,
        replaceImage: Boolean = false
    ) =
        withFixture { fixture ->
            val session = fixture.createSession()
            val message = fixture.addReply(session, versionOnly)
            val voice = fixture.addVoice(message)
            val target = MessageAlternativeVersionPolicy.activeVersionId(message)
            val replacement = if (versionOnly) MessageAlternativeVersionPolicy.remove(message, target) else null
            val directory = File(fixture.context.filesDir, "entities/$entity")
            assertTrue(directory.isDirectory)
            assertTrue(directory.setWritable(false))
            try {
                val error = fixture.removals.remove(message, replacement, target.takeIf { versionOnly })
                assertNotNull("An incomplete deletion must be reported as pending", error)
                assertTrue(File(voice.audioPath).isFile)
                assertTrue(File(message.images.single()).isFile)
                val persisted = fixture.chats.getMessage(message.id, session.id)
                if (versionOnly) {
                    assertFalse(MessageAlternativeVersionPolicy.versions(requireNotNull(persisted)).any { it.id == target })
                } else {
                    assertNull(persisted)
                }
                if (entity != "voice_anchor_states") assertNotNull(fixture.voices.get(voice.id))
                val journal = File(fixture.context.filesDir, "entities/pending_reply_removals").listFiles().orEmpty()
                assertEquals(1, journal.size)
                assertFalse(BackupDataRegistry.isSelectedFile("entities/pending_reply_removals/${journal.single().name}"))
                assertTrue(runCatching {
                    ReplyRemovalCoordinator.requireSettledForBackup(fixture.context.filesDir)
                }.exceptionOrNull() is IllegalStateException)
                assertNotNull(runCatching {
                    fixture.removals.withSettledSession(session.id) { error("A pending removal must block this capture") }
                }.exceptionOrNull()?.takeUnless { it.message == "A pending removal must block this capture" })
            } finally {
                assertTrue(directory.setWritable(true))
            }

            val cold = Fixture(fixture.context, fixture.app)
            if (restoreOwner) {
                val restoredImages = if (replaceImage) listOf(File(File(message.images.single()).parentFile, "restored.png").also {
                    it.writeText("restored image")
                }.path) else message.images
                cold.chats.replaceMessagesForSession(session.id, listOf(message.copy(content = "restored", images = restoredImages)))
            }
            cold.removals.resumePending()
            assertEquals(0, File(fixture.context.filesDir, "entities/pending_reply_removals").listFiles().orEmpty().size)
            ReplyRemovalCoordinator.requireSettledForBackup(fixture.context.filesDir)
            if (restoreOwner) {
                val restored = requireNotNull(cold.chats.getMessage(message.id, session.id))
                assertEquals("restored", restored.content)
                assertNotNull(cold.voices.get(voice.id))
                assertTrue(File(voice.audioPath).isFile)
                assertTrue(File(restored.images.single()).isFile)
                assertEquals(!replaceImage, File(message.images.single()).isFile)
            } else {
                assertNull(cold.voices.get(voice.id))
                assertFalse(cold.voices.snapshotAnchorsForMessages(setOf(message.id)).any { it.messageVersionId == target })
                assertFalse(File(voice.audioPath).exists())
                assertEquals(versionOnly, File(message.images.single()).isFile)
                if (!versionOnly) {
                    assertTrue(cold.chats.getMessages(session.id).isEmpty())
                    val reopened = requireNotNull(cold.chats.getSession(session.id))
                    assertNull(reopened.lastMessagePreview)
                    assertTrue(reopened.sourceTurnTombstones.any { it.sourceTurnId == message.sourceTurnId })
                }
            }
        }

    private fun withFixture(block: suspend (Fixture) -> Unit) = runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<ChatBarApp>()
        withTimeout(30_000) { app.dependenciesReady.first { it } }
        val root = File(app.cacheDir, "reply-removal-${UUID.randomUUID()}").also { check(it.mkdirs()) }
        val context = object : ContextWrapper(app) { override fun getFilesDir(): File = root }
        try {
            block(Fixture(context, app))
        } finally {
            root.deleteRecursively()
        }
    }

    private class Fixture(val context: Context, val app: ChatBarApp) {
        val storage = JsonFileStorage(context)
        val chats = ChatRepository(storage)
        val voices = VoiceMessageRepository(storage, chats::getMessage)
        val audio = FishAudioStorage(context)
        val removals = ReplyRemovalCoordinator(storage, chats, voices, audio, NovelAiImageStorage(context), app.voicePlaybackController)

        suspend fun createSession(): ChatSession =
            chats.createSession(ChatSession.create("synthetic-card", "Synthetic chat"))

        suspend fun addReply(session: ChatSession, versionOnly: Boolean): ChatMessage {
            val image = File(context.filesDir, "images/save_slots/synthetic/image.png").also {
                check(it.parentFile!!.mkdirs())
                it.writeText("synthetic image")
            }
            val first = ChatMessage.create(session.id, MessageRole.ASSISTANT, "First version").copy(
                images = listOf(image.path), sourceTurnId = "synthetic-turn", sourceTurnOrder = 1
            )
            val message = if (versionOnly) MessageAlternativeVersionPolicy.append(first, "Second version", "second") else first
            return chats.addMessage(message)
        }

        suspend fun addVoice(message: ChatMessage): GeneratedVoiceMessage {
            val version = MessageAlternativeVersionPolicy.activeVersion(message)
            val audioFile = File(context.filesDir, "audio/generated/${message.sessionId}/reply.mp3").also {
                check(it.parentFile!!.mkdirs())
                it.writeText("synthetic audio")
            }
            voices.ensureAnchorsForVersion(message, version.id, version.content)
            val voice = GeneratedVoiceMessage(
                id = "synthetic-voice", sessionId = message.sessionId, messageId = message.id, messageVersionId = version.id,
                sourceOrder = 0, sourceSegmentKind = VoiceGenerationPolicy.WHOLE_MESSAGE_SOURCE_KIND, sourceSpeakerName = "",
                sourceText = version.content, taggedText = version.content, characterId = "synthetic-card",
                characterName = "Synthetic", voice = FishAudioVoiceBinding("synthetic-reference", "Synthetic"),
                fishModelId = "synthetic-model", audioPath = audioFile.path, durationMs = 1, byteLength = audioFile.length(),
                createdAt = 1, updatedAt = 1
            )
            voices.save(voice)
            return voice
        }
    }
}
