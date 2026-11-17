package com.example.chatbar.ui.chat

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.data.repository.VoiceMessageRepository
import com.example.chatbar.domain.backup.*
import com.example.chatbar.domain.chat.MessageAlternativeVersionPolicy
import com.example.chatbar.domain.voice.VoiceGenerationPolicy
import java.io.File
import java.util.UUID
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Actual VM, private JSON/audio ownership, cold reads and export. No speech or image service. */
class ReplyAlternativeVoiceCleanupRuntimeTest {
    @Test fun deletingCurrentVersionCleansVoiceAndAnchorFromColdStorageAndBackup() = verify()
    @Test fun deletingInactiveLegacyVersionResolvesOwnershipBeforeRemoval() = verify(legacy = true)
    @Test fun survivingVoiceKeepsSharedAudioAndMessageImage() = verify(sharedAudio = true)
    @Test fun deletingLastVersionStillDeletesItsVoiceAndMessage() = verify(last = true)
    @Test fun missingVersionDoesNotCleanAnyVoice() = verify(missing = true)
    @Test fun lateSynthesisCannotRestoreDeletedVersionVoice() = verify(lateVoice = true)
    @Test fun lateLegacyRegenerationCannotRestoreDeletedVersionVoice() = verify(legacy = true, lateVoice = true)
    @Test fun lateAnchorResolutionCannotRestoreDeletedVersionAnchor() = verify(lateAnchor = true)

    private fun verify(
        legacy: Boolean = false, sharedAudio: Boolean = false, last: Boolean = false,
        missing: Boolean = false, lateVoice: Boolean = false, lateAnchor: Boolean = false
    ) = runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<ChatBarApp>()
        withTimeout(30_000) { app.dependenciesReady.first { it } }
        val store = ViewModelStore()
        val card = CharacterCard.create("Synthetic voice retention")
        val session = ChatSession.create(card.id, "Synthetic voice retention").copy(
            longTermMemoryEnabled = false, automaticImageGenerationEnabled = false)
        val workspace = File(app.cacheDir, "version-voice-" + UUID.randomUUID()).also(File::mkdirs)
        val image = File(app.filesDir, "images/" + UUID.randomUUID() + ".png").also {
            it.parentFile!!.mkdirs(); it.writeBytes(byteArrayOf(1, 2, 3, 4))
        }
        val audioDir = File(app.filesDir, "audio/generated/${session.id}").also(File::mkdirs)
        fun audio(name: String) = File(audioDir, "$name.mp3").also {
            // Opaque synthetic original bytes for persistence/backup, never played or decoded.
            it.writeBytes("synthetic audio $name".toByteArray())
        }
        val deletedAudio = audio("deleted")
        val retainedAudio = if (sharedAudio) deletedAudio else audio("retained")
        val otherAudio = audio("other")
        val first = ChatMessage.create(session.id, MessageRole.ASSISTANT, "synthetic first voice")
            .copy(images = listOf(image.path))
        val reply = if (last) first else MessageAlternativeVersionPolicy.append(first,
            "synthetic second voice", "second-version")
        val versions = MessageAlternativeVersionPolicy.versions(reply)
        val deletedVersion = if (legacy) versions.first() else versions.last()
        val retainedVersion = versions.firstOrNull { it.id != deletedVersion.id }
        val other = ChatMessage.create(session.id, MessageRole.ASSISTANT, "independent voice")
        fun voice(message: ChatMessage, version: String?, text: String, file: File) = GeneratedVoiceMessage(
            id = UUID.randomUUID().toString(), sessionId = session.id, messageId = message.id,
            messageVersionId = version, sourceOrder = 0, sourceSegmentKind = VoiceGenerationPolicy.WHOLE_MESSAGE_SOURCE_KIND,
            sourceSpeakerName = "", sourceText = text, taggedText = text, characterId = "synthetic-character",
            characterName = "Synthetic", voice = FishAudioVoiceBinding("synthetic-reference", "Synthetic"),
            fishModelId = "synthetic-model", audioPath = file.path, durationMs = 1000, byteLength = file.length(),
            createdAt = 1, updatedAt = 1)
        val removedVoice = voice(reply, if (legacy) null else deletedVersion.id, deletedVersion.content, deletedAudio)
        val survivor = retainedVersion?.let { voice(reply, it.id, it.content, retainedAudio) }
        val independent = voice(other, MessageAlternativeVersionPolicy.activeVersionId(other), other.content, otherAudio)
        try {
            app.characterRepository.save(card)
            app.chatRepository.createSession(session)
            app.chatRepository.addMessage(reply)
            app.chatRepository.addMessage(other)
            for (v in versions) app.voiceMessageRepository.ensureAnchorsForVersion(reply, v.id, v.content)
            app.voiceMessageRepository.ensureAnchors(other)
            app.voiceMessageRepository.save(removedVoice)
            if (legacy) app.voiceMessageRepository.restoreCopiedAnchors(listOf(VoiceAnchorState(
                messageId = reply.id, sessionId = session.id, displayContentSnapshot = deletedVersion.content)))
            survivor?.let { app.voiceMessageRepository.save(it) }
            app.voiceMessageRepository.save(independent)
            val vm = withContext(Dispatchers.Main) {
                ViewModelProvider(store, ChatViewModelFactory(session.id))[ChatViewModel::class.java]
            }
            withTimeout(30_000) { vm.messages.first { rows -> rows.any { it.id == reply.id } } }
            withContext(Dispatchers.Main) { vm.deleteMessageAlternative(reply.id,
                if (missing) "does-not-exist" else deletedVersion.id) }
            if (missing) {
                withTimeout(15_000) { vm.messageActionError.first { it != null } }
            } else {
                withTimeout(15_000) { vm.messages.first { rows ->
                    val current = rows.firstOrNull { it.id == reply.id }
                    if (last) current == null else current != null &&
                        MessageAlternativeVersionPolicy.versions(current).none { it.id == deletedVersion.id }
                } }
            }
            val cold = VoiceMessageRepository(JsonFileStorage(app))
            val coldVoices = cold.listForSession(session.id)
            val coldAnchors = cold.snapshotAnchorsForMessages(setOf(reply.id, other.id))
            if (missing) {
                assertEquals(setOfNotNull(removedVoice.id, survivor?.id, independent.id), coldVoices.map { it.id }.toSet())
                assertTrue(deletedAudio.exists())
                assertTrue(coldAnchors.any { it.messageVersionId == deletedVersion.id })
                return@runBlocking
            }
            assertFalse("Deleted alternative must not retain its dedicated voice", coldVoices.any { it.id == removedVoice.id })
            assertFalse("Deleted alternative must not retain its anchor state", coldAnchors.any {
                it.messageId == reply.id && it.messageVersionId == deletedVersion.id })
            assertEquals(sharedAudio, deletedAudio.exists())
            assertTrue(otherAudio.exists())
            assertTrue(coldVoices.any { it == independent })
            assertTrue(coldAnchors.any { it.messageId == other.id })
            if (survivor != null) {
                assertTrue(coldVoices.any { it == survivor })
                assertTrue(coldAnchors.any { it.messageId == reply.id && it.messageVersionId == survivor.messageVersionId })
                assertTrue(retainedAudio.exists())
            }
            val coldMessage = ChatRepository(JsonFileStorage(app)).getMessage(reply.id, session.id)
            if (last) assertNull(coldMessage) else {
                assertNotNull(coldMessage)
                assertEquals(listOf(image.path), coldMessage!!.images)
                assertTrue(image.exists())
            }
            if (lateVoice) {
                var rejected = false
                try { app.voiceMessageRepository.saveForExistingVersion(removedVoice) {
                    app.chatRepository.getMessage(reply.id, session.id)
                } } catch (_: IllegalStateException) { rejected = true }
                assertTrue("Late TTS result must be rejected", rejected)
                assertNull(VoiceMessageRepository(JsonFileStorage(app)).get(removedVoice.id))
            }
            if (lateAnchor) {
                var rejected = false
                try { app.voiceMessageRepository.ensureAnchorsForVersion(reply, deletedVersion.id, deletedVersion.content) {
                    app.chatRepository.getMessage(reply.id, session.id)
                } } catch (_: IllegalStateException) { rejected = true }
                assertTrue("Late anchor resolution must be rejected", rejected)
                assertFalse(VoiceMessageRepository(JsonFileStorage(app)).snapshotAnchorsForMessages(setOf(reply.id))
                    .any { it.messageVersionId == deletedVersion.id })
            }
            val plan = BackupExportCatalog.read(app.filesDir) {}.plan(AppBackupSelection(
                characterIds = setOf(card.id), sessions = mapOf(session.id to BackupSessionChoice(archives = false))))
            fun engine() = BackupArchive({}, { _, _, _ -> }, { File(it).inputStream() })
            val archive = File(workspace, "synthetic.cbbackup")
            archive.outputStream().use { engine().create(app.filesDir, File(workspace, "encode"), it, null,
                1, "synthetic-version-voice", BackupPreferences(), plan) }
            val restore = File(workspace, "restore")
            val target = File(workspace, "target").also(File::mkdirs)
            archive.inputStream().use { engine().extract(it, null, restore, target, 1) }
            val exported = File(restore, "files/entities/generated_voice_messages").listFiles().orEmpty()
                .map { backupJson.decodeFromString<GeneratedVoiceMessage>(it.readText()) }
            val exportedAnchors = File(restore, "files/entities/voice_anchor_states").listFiles().orEmpty()
                .map { backupJson.decodeFromString<VoiceAnchorState>(it.readText()) }
            assertEquals(coldVoices.map { it.id }.toSet(), exported.map { it.id }.toSet())
            assertFalse(exportedAnchors.any { it.messageId == reply.id && it.messageVersionId == deletedVersion.id })
            val exportedAudio = File(restore, "files/audio/generated/${session.id}")
            assertEquals(sharedAudio, File(exportedAudio, "deleted.mp3").exists())
            assertArrayEquals(otherAudio.readBytes(), File(exportedAudio, "other.mp3").readBytes())
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            app.voiceMessageRepository.deleteForSession(session.id)
            app.chatRepository.deleteMessagesForSession(session.id)
            app.chatRepository.deleteSessionRecord(session.id)
            app.characterRepository.delete(card.id)
            image.delete()
            audioDir.listFiles().orEmpty().forEach { it.delete() }; audioDir.delete()
            workspace.deleteRecursively()
        }
    }
}
