package com.example.chatbar.ui.chat

import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.ChunkSourceType
import com.example.chatbar.data.local.entity.FishAudioVoiceBinding
import com.example.chatbar.data.local.entity.GeneratedVoiceMessage
import com.example.chatbar.data.local.entity.GeneratedImageMetadata
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.SaveSlot
import com.example.chatbar.data.local.entity.VectorChunk
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.data.repository.VoiceMessageRepository
import com.example.chatbar.domain.backup.BackupDataRegistry
import com.example.chatbar.domain.chat.MessageAlternativeVersionPolicy
import com.example.chatbar.domain.deletion.ReplyRemovalCoordinator
import com.example.chatbar.domain.image.NovelAiImageStorage
import com.example.chatbar.domain.rag.ChatMemoryIndexPolicy
import com.example.chatbar.domain.rag.RagRepository
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

    @Test fun pendingAudioCleanupPreservesRebuiltMemoryAcrossReopenAndRetry() = withFixture { fixture ->
        val session = fixture.createSession()
        val reply = fixture.addReply(session, versionOnly = true)
        val voice = fixture.addVoice(reply)
        val target = MessageAlternativeVersionPolicy.activeVersionId(reply)
        val replacement = requireNotNull(MessageAlternativeVersionPolicy.remove(reply, target))
        val rag = RagRepository(fixture.storage)
        val metadata = mapOf(
            "indexMode" to ChatMemoryIndexPolicy.INDEX_MODE,
            "contentVersion" to ChatMemoryIndexPolicy.CONTENT_VERSION,
            "sourceTurnId" to requireNotNull(reply.sourceTurnId)
        )
        val stale = VectorChunk.create(ChunkSourceType.CHAT_MEMORY, session.id, reply.displayContent,
            listOf(1f), reply.id, metadata)
        rag.saveChunks(listOf(stale))
        val audioFile = File(voice.audioPath)
        val pendingDirectory = File(fixture.context.filesDir, "entities/pending_reply_removals")
        val rebuilt = VectorChunk.create(ChunkSourceType.CHAT_MEMORY, session.id, replacement.displayContent,
            listOf(1f), reply.id, metadata)
        assertTrue(audioFile.delete())
        assertTrue(audioFile.mkdir())
        val obstruction = File(audioFile, "synthetic-child").also { it.writeText("blocks directory deletion") }
        try {
            assertNotNull(fixture.removals.remove(reply, replacement, target))
            assertEquals(replacement.displayContent, fixture.chats.getMessage(reply.id, session.id)?.displayContent)
            assertNull("The invalidation must already be complete while cleanup is pending", rag.getChunkById(stale.id))
            assertTrue(obstruction.isFile)
            assertEquals(1, pendingDirectory.listFiles().orEmpty().size)
            rag.saveChunks(listOf(rebuilt))

            val cold = Fixture(fixture.context, fixture.app)
            assertEquals(listOf(rebuilt), cold.removals.getChunksForRetrieval(null, session.id))
            cold.removals.resumePending(session.id)
            assertTrue(obstruction.isFile)
            assertEquals(listOf(rebuilt), cold.removals.getChunksForRetrieval(null, session.id))
        } finally {
            assertTrue(obstruction.delete())
        }
        val recovered = Fixture(fixture.context, fixture.app)
        recovered.removals.resumePending(session.id)
        assertFalse(audioFile.exists())
        assertTrue(pendingDirectory.listFiles().orEmpty().isEmpty())
        assertEquals(listOf(rebuilt), recovered.removals.getChunksForRetrieval(null, session.id))
    }

    @Test fun missingMessageAfterIndexWriteFailureFinishesAllMediaOnColdRetry() = verifyFailure("chat_message_indexes")
    @Test fun missingMessageAfterSessionWriteFailureRepairsPreviewAndTurnOnColdRetry() = verifyFailure("chat_sessions")
    @Test fun recoveredSameIdMessageRetainsRestoredAttachments() = verifyFailure("chat_message_indexes", restoreOwner = true)
    @Test fun restoredDifferentImageRetainsNewFilesAndReleasesOldImage() =
        verifyFailure("chat_message_indexes", restoreOwner = true, replaceImage = true)

    @Test fun ambiguousLegacyVoiceSurvivesDirectAlternativeRemoval() = verifyAmbiguousLegacyVoice()
    @Test fun ambiguousLegacyVoiceSurvivesRemovalAfterDisplayMigration() = verifyAmbiguousLegacyVoice(openFirst = true)
    @Test fun oldPersistedDisplayAssignmentCannotAuthorizeVoiceDeletion() = verifyAmbiguousLegacyVoice(assigned = true)
    @Test fun retiredAssignedVoiceDoesNotPlayOnDifferentTextAfterColdRestore() =
        verifyRetiredVoiceStaysHidden(assigned = true)
    @Test fun unassignedVoiceDoesNotPlayOnDifferentTextAfterColdRestore() =
        verifyRetiredVoiceStaysHidden(assigned = false)
    @Test fun failedAlternativeBodyWriteAllowsOneConfirmedRetry() = verifyUncommittedRetry(versionOnly = true)
    @Test fun failedWholeMessageBodyDeleteAllowsOneConfirmedRetry() = verifyUncommittedRetry(versionOnly = false)
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
        val message = fixture.addReply(session, versionOnly = false, imagePath = "images/migrated_backup/synthetic/chat_img_case.png")
        val file = File(message.images.single())
        val owner = fixture.chats.addMessage(ChatMessage.create(session.id, MessageRole.ASSISTANT, "Metadata owner").copy(
            generatedImageMetadata = listOf(GeneratedImageMetadata(
                file.toURI().toString(), "synthetic", emptyList(), "", "normal", 64, 64
            ))
        ))
        assertNull(fixture.removals.remove(message, null, null))
        assertTrue(file.isFile)
        assertNull(fixture.removals.remove(owner, null, null))
        assertFalse(file.exists())
    }

    @Test fun legacyVoiceWithSameMessageIdInAnotherSessionIsNotMigrated() = withFixture { fixture ->
        val session = fixture.createSession()
        val otherSession = fixture.createSession()
        val message = fixture.addReply(session, versionOnly = true)
        val voice = fixture.addVoice(message)
        fixture.voices.save(voice.copy(messageVersionId = null, versionOwnershipConfirmed = false))
        val otherVoice = voice.copy(id = "other-legacy", sessionId = otherSession.id,
            messageVersionId = null, versionOwnershipConfirmed = false)
        fixture.voices.save(otherVoice)
        val version = MessageAlternativeVersionPolicy.activeVersionId(message)
        val replacement = requireNotNull(MessageAlternativeVersionPolicy.remove(message, version))
        assertNull(fixture.removals.remove(message, replacement, version))
        assertEquals(otherVoice, fixture.voices.get(otherVoice.id))
        assertTrue(File(otherVoice.audioPath).isFile)
    }

    private fun verifyAmbiguousLegacyVoice(openFirst: Boolean = false, assigned: Boolean = false) = withFixture { fixture ->
        val session = fixture.createSession()
        val reply = fixture.addReply(session, versionOnly = true, sameText = true)
        val removedId = MessageAlternativeVersionPolicy.activeVersionId(reply)
        val legacy = fixture.addVoice(reply).copy(
            id = "legacy-voice", messageVersionId = removedId.takeIf { assigned }, versionOwnershipConfirmed = false
        )
        fixture.voices.save(legacy)
        if (openFirst) {
            assertEquals(removedId, fixture.voices.listForCurrentVersion(reply).single { it.id == legacy.id }.messageVersionId)
        }
        val replacement = requireNotNull(MessageAlternativeVersionPolicy.remove(reply, removedId))
        assertNull(fixture.removals.remove(reply, replacement, removedId))

        val cold = Fixture(fixture.context, fixture.app)
        val survivingReply = requireNotNull(cold.chats.getMessage(reply.id, session.id))
        val survivingVersion = MessageAlternativeVersionPolicy.versions(survivingReply).single()
        assertEquals(reply.displayContent, survivingVersion.content)
        assertNotEquals(removedId, survivingVersion.id)
        val survivingVoice = cold.voices.listForCurrentVersion(survivingReply).single()
        assertEquals(legacy.id, survivingVoice.id)
        assertEquals(survivingVersion.id, survivingVoice.messageVersionId)
        assertFalse(survivingVoice.versionOwnershipConfirmed)
        assertEquals("synthetic audio", File(survivingVoice.audioPath).readText())
        assertEquals("synthetic image", File(survivingReply.images.single()).readText())
    }

    private fun verifyRetiredVoiceStaysHidden(assigned: Boolean) = withFixture { fixture ->
        val session = fixture.createSession()
        val reply = fixture.addReply(session, versionOnly = true)
        val removed = MessageAlternativeVersionPolicy.activeVersion(reply)
        val retiredId = removed.id.takeIf { assigned }
        val legacy = fixture.addVoice(reply).copy(
            id = "unconfirmed-before-upgrade", messageVersionId = retiredId, versionOwnershipConfirmed = false
        )
        fixture.voices.save(legacy)
        val survivor = requireNotNull(MessageAlternativeVersionPolicy.remove(reply, removed.id))
        assertNotEquals(legacy.sourceText, survivor.displayContent)
        assertNull(fixture.removals.remove(reply, survivor, removed.id))

        val cold = Fixture(fixture.context, fixture.app)
        val persisted = requireNotNull(cold.chats.getMessage(reply.id, session.id))
        assertTrue(cold.voices.listForCurrentVersion(persisted).isEmpty())
        assertTrue(cold.voices.listForCurrentVersion(reply).isEmpty())
        assertTrue(cold.voices.placementsForMessage(reply).isEmpty())
        val archivedVoice = cold.voices.listForSession(session.id).single()
        assertEquals(retiredId, archivedVoice.messageVersionId)
        assertTrue(archivedVoice.requiresCompatibleVersion)
        assertEquals("synthetic audio", File(archivedVoice.audioPath).readText())

        val archive = SaveSlot.create(session.id, "Retired voice", messages = listOf(persisted))
            .copy(voiceMessages = listOf(archivedVoice))
        val encoded = cold.storage.json.encodeToString(SaveSlot.serializer(), archive)
        val reopened = cold.storage.json.decodeFromString(SaveSlot.serializer(), encoded)
        // These are the two voice restore entry points used by inline and packaged save slots.
        for (legacySlot in listOf(true, false)) {
            if (legacySlot) cold.voices.restoreForSession(session.id, reopened.voiceMessages, reopened.messages)
            else cold.voices.restoreForSession(session.id, reopened.voiceMessages)
            val restored = Fixture(fixture.context, fixture.app)
            assertTrue(restored.voices.listForCurrentVersion(persisted).isEmpty())
            val retained = restored.voices.listForSession(session.id).single()
            assertEquals(retiredId, retained.messageVersionId)
            assertTrue(retained.requiresCompatibleVersion)
            assertEquals("synthetic audio", File(retained.audioPath).readText())
        }
    }

    @Test fun restrictedRecordingStaysDetachedFromEditedSameIdVersionAfterRestore() = withFixture { fixture ->
        val session = fixture.createSession()
        val original = fixture.addReply(session, versionOnly = false)
        val voice = fixture.addVoice(original).copy(versionOwnershipConfirmed = false,
            requiresCompatibleVersion = true, anchorId = "original-anchor")
        fixture.voices.save(voice)
        assertEquals(listOf(voice.id), fixture.voices.listForCurrentVersion(original).map { it.id })
        assertEquals(voice.id, fixture.voices.currentVoiceForPlayback(voice)?.id)
        val changed = MessageAlternativeVersionPolicy.editCurrentContent(original, "Edited incompatible reply")
        assertNotNull(fixture.chats.updateMessageIfUnchanged(original, changed))
        val persisted = requireNotNull(fixture.chats.getMessage(original.id, session.id))
        assertEquals(voice.messageVersionId, MessageAlternativeVersionPolicy.activeVersionId(persisted))

        for (legacySlot in listOf(true, false)) {
            if (legacySlot) fixture.voices.restoreForSession(session.id, listOf(voice), listOf(persisted))
            else fixture.voices.restoreForSession(session.id, listOf(voice))
            val cold = Fixture(fixture.context, fixture.app)
            val archived = cold.voices.listForSession(session.id).single()
            assertNull(archived.anchorId)
            assertEquals(voice.messageVersionId, archived.messageVersionId)
            assertTrue(archived.requiresCompatibleVersion)
            assertTrue(cold.voices.listForCurrentVersion(persisted).isEmpty())
            assertTrue(cold.voices.placementsForMessage(original).isEmpty())
            assertNull(cold.voices.currentVoiceForPlayback(voice))
            assertEquals("synthetic audio", File(archived.audioPath).readText())
        }
    }

    @Test fun delayedPlaybackRechecksCapturedRecordingsInTheirOriginalOrder() = withFixture { fixture ->
        val session = fixture.createSession()
        val original = fixture.addReply(session, versionOnly = false)
        val initial = fixture.addVoice(original)
        val legacy = initial.copy(versionOwnershipConfirmed = false, requiresCompatibleVersion = true)
        fixture.voices.save(legacy)
        val removed = initial.copy(id = "removed-recording")
        fixture.voices.save(removed)

        val edited = MessageAlternativeVersionPolicy.editCurrentContent(original, "Updated reply")
        fun recording(id: String, owner: ChatMessage): GeneratedVoiceMessage {
            val audio = File(File(initial.audioPath).parentFile, "$id.mp3").also {
                it.writeText("synthetic $id")
            }
            return initial.copy(
                id = id, sourceText = owner.displayContent, audioPath = audio.path,
                messageVersionId = MessageAlternativeVersionPolicy.activeVersionId(owner),
            )
        }
        val alternate = MessageAlternativeVersionPolicy.append(edited, "Another reply", "other-version")
        assertNotNull(fixture.chats.updateMessageIfUnchanged(original, alternate))
        val capturedAlternative = recording("other-recording", alternate)
        fixture.voices.save(capturedAlternative)
        val current = requireNotNull(fixture.chats.getMessage(original.id, session.id))
        val selected = MessageAlternativeVersionPolicy.select(current, 0)
        assertEquals(legacy.messageVersionId, MessageAlternativeVersionPolicy.activeVersionId(selected))
        assertNotNull(fixture.chats.updateMessageIfUnchanged(current, selected))
        fixture.voices.delete(removed.id)

        val first = recording("current-first", selected)
        val last = recording("current-last", selected)
        fixture.voices.save(first)
        fixture.voices.save(last)
        fixture.voices.withCurrentAutoPlayQueue(listOf(first, legacy, capturedAlternative, removed, last), fixture.chats) { queue ->
            assertEquals(listOf(first.id, last.id), queue.map { it.id })
            assertTrue(queue.all { File(it.audioPath).isFile })
        }
        assertTrue(File(legacy.audioPath).isFile)
        assertTrue(File(capturedAlternative.audioPath).isFile)
    }

    private fun verifyUncommittedRetry(versionOnly: Boolean) = withFixture { fixture ->
        val session = fixture.createSession()
        val message = fixture.addReply(session, versionOnly)
        val voice = fixture.addVoice(message)
        val target = MessageAlternativeVersionPolicy.activeVersionId(message).takeIf { versionOnly }
        val replacement = target?.let { MessageAlternativeVersionPolicy.remove(message, it) }
        val directory = File(fixture.context.filesDir, "entities/chat_messages")
        assertTrue(directory.isDirectory)
        assertTrue(directory.setWritable(false))
        try {
            assertTrue(runCatching { fixture.removals.remove(message, replacement, target) }.isFailure)
            assertEquals(message, fixture.chats.getMessage(message.id, session.id))
            assertEquals(voice, fixture.voices.get(voice.id))
            assertTrue(File(voice.audioPath).isFile)
            assertEquals(1, File(fixture.context.filesDir, "entities/pending_reply_removals").listFiles().orEmpty().size)
        } finally {
            assertTrue(directory.setWritable(true))
        }
        val cold = Fixture(fixture.context, fixture.app)
        assertFalse(cold.removals.retryMessage(session.id, message.id, target))
        assertNull(cold.removals.remove(message, replacement, target))
        assertEquals(replacement?.displayContent, cold.chats.getMessage(message.id, session.id)?.displayContent)
        assertNull(cold.voices.get(voice.id))
        assertFalse(File(voice.audioPath).exists())
        ReplyRemovalCoordinator.requireSettledForBackup(fixture.context.filesDir)
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
                if (entity == "chat_message_indexes" && !restoreOwner) {
                    val journalDirectory = journal.single().parentFile!!
                    assertTrue(journalDirectory.setReadable(false))
                    try {
                        val captured = runCatching { fixture.removals.withSettledSession(session.id) { true } }
                        assertTrue("An unreadable recovery journal must not permit a new save slot", captured.isFailure)
                    } finally {
                        assertTrue(journalDirectory.setReadable(true))
                    }
                }
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

        suspend fun addReply(
            session: ChatSession,
            versionOnly: Boolean,
            imagePath: String = "images/save_slots/synthetic/image.png",
            sameText: Boolean = false
        ): ChatMessage {
            val image = File(context.filesDir, imagePath).also {
                check(it.parentFile!!.mkdirs())
                it.writeText("synthetic image")
            }
            val first = ChatMessage.create(session.id, MessageRole.ASSISTANT, "First version").copy(
                images = listOf(image.path), sourceTurnId = "synthetic-turn", sourceTurnOrder = 1
            )
            val message = if (versionOnly) {
                MessageAlternativeVersionPolicy.append(first, if (sameText) first.content else "Second version", "second")
            } else first
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
                versionOwnershipConfirmed = true,
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
