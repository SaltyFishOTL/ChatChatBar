package com.example.chatbar.data.repository

import android.content.ContextWrapper
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.FishAudioVoiceBinding
import com.example.chatbar.data.local.entity.GeneratedVoiceMessage
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.SaveSlot
import com.example.chatbar.data.local.entity.SaveSlotImagePolicy
import com.example.chatbar.data.local.entity.VoiceAnchorState
import com.example.chatbar.domain.chat.MessageAlternativeVersionPolicy
import com.example.chatbar.domain.chat.SaveSlotJsonTransfer
import com.example.chatbar.domain.chat.SaveSlotPackageStorage
import com.example.chatbar.domain.deletion.ReplyRemoval
import com.example.chatbar.domain.voice.FishAudioStoredAudio
import com.example.chatbar.domain.voice.VoiceAnchorPolicy
import com.example.chatbar.domain.voice.VoiceAnchorStateKey
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class VoiceMessageRepositoryTest {
    @get:Rule val temp = TemporaryFolder()

    @Test
    fun `raw v7 recorded orphan stays at message head and exact old placement is recovered`() = runBlocking {
        val context = TestContext(temp.newFolder())
        val chats = ChatRepository(JsonFileStorage(context))
        val voices = VoiceMessageRepository(JsonFileStorage(context), chats::getMessage)
        val input = temp.newFile("legacy-v7.json")
        input.writeText(
            """{
              "schemaVersion":7,"id":"slot","sessionId":"original","name":"Legacy","createdAt":1,
              "messages":[{"id":"m","sessionId":"original","role":"ASSISTANT",
                "content":"<n=\"甲\"/>[Still here]()","createdAt":1,"updatedAt":1}],
              "voiceMessages":[
                {"id":"orphan","sessionId":"original","messageId":"m","messageVersionId":"m",
                 "anchorId":null,"sourceOrder":0,"sourceSegmentKind":"DIALOGUE","sourceSpeakerName":"甲",
                 "sourceText":"Deleted line","taggedText":"Deleted line","characterId":"speaker","characterName":"Speaker",
                 "voice":{"referenceId":"synthetic","title":"Synthetic"},"fishModelId":"synthetic",
                 "audioPath":"orphan","durationMs":1,"byteLength":5,"createdAt":1,"updatedAt":1},
                {"id":"exact","sessionId":"original","messageId":"m","messageVersionId":"m",
                 "anchorId":"old-anchor","sourceOrder":0,"sourceSegmentKind":"DIALOGUE","sourceSpeakerName":"甲",
                 "sourceText":"Still here","taggedText":"Still here","characterId":"speaker","characterName":"Speaker",
                 "voice":{"referenceId":"synthetic","title":"Synthetic"},"fishModelId":"synthetic",
                 "audioPath":"exact","durationMs":1,"byteLength":5,"createdAt":1,"updatedAt":1}]
            }""".trimIndent()
        )
        val raw = input.readBytes()
        val slot = input.inputStream().use(SaveSlotJsonTransfer::read)
        val message = slot.messages.single().copy(sessionId = "target")
        val recordings = slot.voiceMessages.map { voice ->
            val audio = File(context.filesDir, "${voice.id}.mp3").apply { writeText(voice.id) }
            voice.copy(audioPath = audio.path)
        }
        chats.replaceMessagesForSession(message.sessionId, listOf(message))
        voices.restoreForSession(message.sessionId, recordings, listOf(message))

        val cold = VoiceMessageRepository(JsonFileStorage(context), chats::getMessage)
        val placements = cold.placementsForMessage(requireNotNull(chats.getMessage(message.id, message.sessionId)))
            .associateBy { it.voice.id }
        assertNull(placements.getValue("orphan").voice.anchorId)
        assertNull(placements.getValue("orphan").segmentIndex)
        assertEquals(0, placements.getValue("exact").segmentIndex)
        placements.values.forEach { assertEquals(it.voice.id, File(it.voice.audioPath).readText()) }
        assertArrayEquals(raw, input.readBytes())
    }

    @Test
    fun `raw inline legacy recording is playable only on the reply with its recorded speaker`() = runBlocking {
        for (schema in 5..7) {
            val context = TestContext(temp.newFolder("legacy-v$schema"))
            val input = File(context.filesDir, "archive.json")
            input.writeText(
                """{
                  "schemaVersion":$schema,"id":"slot","sessionId":"source","name":"Legacy","createdAt":1,
                  "messages":[{"id":"m","sessionId":"source","role":"ASSISTANT",
                    "content":"<n=\"Bob\"/>[Hello]()\n<n=\"Casey\"/>[Shared]()",
                    "alternatives":["<n=\"Alice\"/>[Hello]()\n<n=\"Casey\"/>[Shared]()",
                      "<n=\"Bob\"/>[Hello]()\n<n=\"Casey\"/>[Shared]()"],
                    "alternativeVersionIds":["A","B"],"currentAlternativeVersionId":"B",
                    "currentAlternativeIndex":1,"createdAt":1,"updatedAt":1}],
                  "voiceMessages":[
                    {"id":"clip","sessionId":"source","messageId":"m","sourceOrder":0,
                     "sourceSegmentKind":"DIALOGUE","sourceSpeakerName":"Alice","sourceText":"Hello",
                     "taggedText":"Hello","characterId":"speaker","characterName":"Alice",
                     "voice":{"referenceId":"fixture","title":"Synthetic"},"fishModelId":"fixture",
                     "audioPath":"clip","durationMs":1,"byteLength":5,"createdAt":1,"updatedAt":1},
                    {"id":"shared","sessionId":"source","messageId":"m","sourceOrder":1,
                     "sourceSegmentKind":"DIALOGUE","sourceSpeakerName":"Casey","sourceText":"Shared",
                     "taggedText":"Shared","characterId":"shared-speaker","characterName":"Casey",
                     "voice":{"referenceId":"fixture","title":"Synthetic"},"fishModelId":"fixture",
                     "audioPath":"shared","durationMs":1,"byteLength":5,"createdAt":1,"updatedAt":1}],
                  "audioResources":{"clip":{"fileName":"clip.mp3","data":"YXVkaW8="},
                    "shared":{"fileName":"shared.mp3","data":"c2hhcmU="}}
                }""".trimIndent()
            )
            val raw = input.readBytes()
            val slot = input.inputStream().use(SaveSlotJsonTransfer::read)
            val message = slot.messages.single().copy(sessionId = "target")
            val chats = ChatRepository(JsonFileStorage(context))
            val voices = VoiceMessageRepository(JsonFileStorage(context), chats::getMessage)
            val recordings = slot.voiceMessages.map { voice ->
                val audio = File(context.filesDir, voice.id + ".mp3")
                audio.writeBytes(java.util.Base64.getDecoder().decode(slot.audioResources.getValue(voice.audioPath).data))
                voice.copy(audioPath = audio.path)
            }
            chats.replaceMessagesForSession(message.sessionId, listOf(message))
            voices.restoreForSession(message.sessionId, recordings, listOf(message))

            val coldChats = ChatRepository(JsonFileStorage(context))
            val cold = VoiceMessageRepository(JsonFileStorage(context), coldChats::getMessage)
            val onB = requireNotNull(coldChats.getMessage(message.id, message.sessionId))
            assertTrue("v$schema must not display Alice on Bob's reply", cold.placementsForMessage(onB).isEmpty())
            val restored = cold.listForSession(message.sessionId).associateBy { it.id }
            assertEquals("A", restored.getValue("clip").messageVersionId)
            assertNull(restored.getValue("shared").messageVersionId)
            cold.withCurrentPlaybackQueue(restored.values.toList(), coldChats) { assertTrue(it.isEmpty()) }

            val onA = requireNotNull(coldChats.updateMessageIfUnchanged(onB, MessageAlternativeVersionPolicy.select(onB, 0)))
            assertEquals(0, cold.placementsForMessage(onA).single().segmentIndex)
            cold.withCurrentPlaybackQueue(restored.values.toList(), coldChats) { queue ->
                assertEquals(listOf("clip"), queue.map { it.id })
            }
            assertEquals("audio", File(restored.getValue("clip").audioPath).readText())
            assertEquals("share", File(restored.getValue("shared").audioPath).readText())
            assertArrayEquals(raw, input.readBytes())
        }
    }

    @Test
    fun `guarded image removal keeps a concurrent recording and prevents late recreation after a whole delete`() = runBlocking<Unit> {
        withTimeout(5_000) {
            val context = TestContext(temp.newFolder())
            val chats = ChatRepository(JsonFileStorage(context))
            val voices = VoiceMessageRepository(JsonFileStorage(context), chats::getMessage)
            val reply = chats.addMessage(ChatMessage.create("session", MessageRole.ASSISTANT, "",
                images = listOf(File(context.filesDir, "attachment.png").path)))
            val voice = recording(reply, "clip", "Earlier reply", anchor = null)

            suspend fun removeIfUnvoiced() = voices.prepareReplyRemoval(reply, null, {}, onlyIfUnvoiced = true) { collectAudio ->
                chats.commitReplyRemoval(reply, null) { assertTrue(collectAudio().isEmpty()) }
            }

            val committing = CompletableDeferred<Unit>()
            val finishSave = CompletableDeferred<Unit>()
            val write = async {
                voices.saveForExistingVersion(voice) {
                    committing.complete(Unit)
                    finishSave.await()
                    chats.getMessage(reply.id, reply.sessionId)
                }
            }
            committing.await()
            val pendingRemoval = async(start = CoroutineStart.UNDISPATCHED) { removeIfUnvoiced() }
            finishSave.complete(Unit)
            write.await()
            assertFalse(pendingRemoval.await())
            assertEquals(reply, chats.getMessage(reply.id, reply.sessionId))
            assertEquals(voice, voices.get(voice.id))

            voices.delete(voice.id)
            assertTrue(removeIfUnvoiced())
            assertNull(chats.getMessage(reply.id, reply.sessionId))
            val late = runCatching {
                voices.saveForExistingVersion(voice) { chats.getMessage(reply.id, reply.sessionId) }
            }
            assertTrue(late.exceptionOrNull() is IllegalStateException)
            assertNull(voices.get(voice.id))
        }
    }

    @Test
    fun `previously placed legacy line does not follow a different reply after deletion and archive restore`() =
        verifySharedLegacyLine(openFirst = true)

    @Test
    fun `previously placed legacy line can follow a reply with identical whole text`() =
        verifySharedLegacyLine(openFirst = true, sameReply = true)

    @Test
    fun `unassigned legacy line can still recover by its exact source`() = verifySharedLegacyLine(openFirst = false)

    private fun verifySharedLegacyLine(openFirst: Boolean, sameReply: Boolean = false) = runBlocking {
        val context = TestContext(temp.newFolder())
        val chats = ChatRepository(JsonFileStorage(context))
        val voices = VoiceMessageRepository(JsonFileStorage(context), chats::getMessage)
        val original = chats.addMessage(ChatMessage.create(
            "source", MessageRole.ASSISTANT, "<n=\"甲\"/>[Shared line]()\n<n=\"甲\"/>[A only]()"
        ))
        val originalId = MessageAlternativeVersionPolicy.activeVersionId(original)
        val audio = File(context.filesDir, "recorded.mp3").apply { writeText("synthetic audio") }
        val voice = recording(original, "legacy", "Shared line", null).copy(
            messageVersionId = null, versionOwnershipConfirmed = false, audioPath = audio.path, byteLength = audio.length()
        )
        voices.save(voice)
        if (openFirst) assertEquals(0, voices.placementsForMessage(original).single().segmentIndex)
        val alternate = if (sameReply) original.content else "<n=\"甲\"/>[Shared line]()\n<n=\"甲\"/>[B only]()"
        val regenerated = MessageAlternativeVersionPolicy.append(original, alternate, "B")
        val current = requireNotNull(chats.updateMessageIfUnchanged(original, regenerated))
        val survivor = requireNotNull(MessageAlternativeVersionPolicy.remove(current, originalId))
        assertTrue(voices.prepareReplyRemoval(current, originalId, {}) { collectAudio ->
            chats.commitReplyRemoval(current, survivor) { assertTrue(collectAudio().isEmpty()) }
        })
        val removal = ReplyRemoval("remove-legacy", original.sessionId, original.id, originalId, original.role,
            original.sourceTurnId, original.sourceTurnOrder, original.timelineTurn, emptyList(), emptyList())
        assertTrue(voices.finishReplyRemoval(removal, { cleanup -> chats.finishReplyRemoval(removal, cleanup) }, {},
            { error("The unconfirmed recording must be retained") }))

        val coldChats = ChatRepository(JsonFileStorage(context))
        val cold = VoiceMessageRepository(JsonFileStorage(context), coldChats::getMessage)
        val persisted = requireNotNull(coldChats.getMessage(original.id, original.sessionId))
        val expectedVersions = if (!openFirst || sameReply) listOf("B") else emptyList<String>()
        assertEquals(expectedVersions, cold.placementsForMessage(persisted).map { it.voice.messageVersionId })
        val retained = cold.listForSession(original.sessionId).single()
        assertEquals(expectedVersions.singleOrNull() ?: originalId, retained.messageVersionId)
        assertFalse(retained.versionOwnershipConfirmed)
        assertTrue(retained.requiresCompatibleVersion)
        assertNotNull(retained.legacyVersionContentHash)
        assertEquals("synthetic audio", File(retained.audioPath).readText())

        val archive = SaveSlot.create(original.sessionId, "Retired line", messages = listOf(persisted))
            .copy(schemaVersion = 7, voiceMessages = listOf(retained))
        val inline = temp.newFile("re-export-v7.json")
        inline.outputStream().use { SaveSlotJsonTransfer.write(archive, it) }
        val imported = inline.inputStream().use(SaveSlotJsonTransfer::read)
        val rebound = imported.messages.single().copy(sessionId = "inline-target")
        val destinationAudio = File(context.filesDir, "rebound.mp3").apply { writeBytes(audio.readBytes()) }
        coldChats.replaceMessagesForSession(rebound.sessionId, listOf(rebound))
        cold.restoreForSession(rebound.sessionId, imported.voiceMessages.map {
            it.copy(id = "rebound-voice", audioPath = destinationAudio.path)
        }, listOf(rebound))

        val packages = SaveSlotPackageStorage(context)
        val modern = packages.createPackage(SaveSlot.create(original.sessionId, "Portable audio"), SaveSlotImagePolicy.NONE, true,
            messageSource = { emit -> emit(persisted) }, ragSource = {}, voices = listOf(retained))
        val exported = temp.newFile("re-export-v8.cbsave")
        exported.outputStream().use { packages.export(modern, it) }
        val packagedSession = "package-target"
        val importedPackage = exported.inputStream().use { packages.importPackage(it, packagedSession) { name -> name }.slot }
        packages.openReader(importedPackage).use { reader ->
            reader.validate()
            val restoredVoices = reader.restoreVoices(packagedSession) { _, _, input ->
                val path = File(context.filesDir, "packaged-rebound.mp3")
                path.outputStream().use { input.copyTo(it) }
                FishAudioStoredAudio(path.path, 1_000, path.length())
            }
            coldChats.replaceMessagesForSessionStreaming(packagedSession) { emit -> reader.streamMessages(packagedSession, emit) }
            cold.restorePackagedForSession(packagedSession, restoredVoices) { emit -> reader.streamVoiceAnchors(packagedSession, emit) }
        }

        val freshChats = ChatRepository(JsonFileStorage(context))
        val restored = VoiceMessageRepository(JsonFileStorage(context), freshChats::getMessage)
        for (sessionId in listOf(rebound.sessionId, packagedSession)) {
            val message = requireNotNull(freshChats.getMessage(original.id, sessionId))
            assertEquals(expectedVersions, restored.placementsForMessage(message).map { it.voice.messageVersionId })
            val reboundClip = restored.listForSession(sessionId).single()
            assertEquals(retained.legacyVersionContentHash, reboundClip.legacyVersionContentHash)
            assertEquals("synthetic audio", File(reboundClip.audioPath).readText())
        }
    }

    @Test
    fun `restored v8 recordings recover exact placement after version removal and cold read`() = runBlocking {
        val context = TestContext(temp.newFolder())
        val reply = MessageAlternativeVersionPolicy.append(
            ChatMessage.create("session", MessageRole.ASSISTANT, "<n=\"甲\"/>[Old]()"),
            "<n=\"甲\"/>[Keep]()\n< n/>\n<n=\"甲\"/>[Repeat]()\n<n=\"甲\"/>[Repeat]()",
            "selected-version"
        )
        val active = MessageAlternativeVersionPolicy.activeVersion(reply)
        val surviving = requireNotNull(MessageAlternativeVersionPolicy.remove(reply, active.id))
        var current = surviving
        val storage = JsonFileStorage(context)
        val owner: suspend (String, String) -> ChatMessage? = { id, session ->
            current.takeIf { it.id == id && it.sessionId == session }
        }
        val repo = VoiceMessageRepository(storage, owner)
        val unique = recording(reply, "exact", "Keep", "removed-anchor")
        val duplicate = recording(reply, "duplicate", "Repeat", "unknown-duplicate-anchor")
        val orphan = recording(reply, "orphan", "Keep", null)
        val otherSpeaker = recording(reply, "speaker", "Keep", "unknown-speaker-anchor").copy(sourceSpeakerName = "乙")
        val recordings = listOf(unique, duplicate, orphan, otherSpeaker).map {
            val file = File(context.filesDir, "${it.id}.mp3").apply { writeText("synthetic ${it.id}") }
            it.copy(audioPath = file.path)
        }

        assertTrue(repo.placementsForMessage(surviving).isEmpty())
        current = reply
        repo.restorePackagedForSession(reply.sessionId, recordings)

        val cold = VoiceMessageRepository(JsonFileStorage(context), owner)
        val placements = cold.placementsForMessage(reply).associateBy { it.voice.id }
        assertEquals(recordings.map { it.id }.toSet(), placements.keys)
        assertNotNull(placements.getValue(unique.id).segmentIndex)
        assertNull(placements.getValue(duplicate.id).segmentIndex)
        assertNull(placements.getValue(orphan.id).segmentIndex)
        assertNull(placements.getValue(otherSpeaker.id).segmentIndex)
        val currentAnchors = cold.ensureAnchors(reply)
        assertEquals(currentAnchors.single { it.segment.spokenText == "Keep" }.anchor.id,
            placements.getValue(unique.id).voice.anchorId)
        for (voice in placements.values.map { it.voice }) {
            assertEquals("selected-version", voice.messageVersionId)
            assertTrue(voice.versionOwnershipConfirmed)
            assertEquals("synthetic ${voice.id}", File(voice.audioPath).readText())
        }
        assertTrue(cold.placementsForMessage(surviving).isEmpty())
    }

    @Test
    fun `v8 export import restore preserves two identical recorded segments with their own bytes`() = runBlocking {
        val context = TestContext(temp.newFolder())
        val storage = JsonFileStorage(context)
        val chats = ChatRepository(storage)
        val voices = VoiceMessageRepository(storage, chats::getMessage)
        val packages = SaveSlotPackageStorage(context)
        val reply = chats.addMessage(MessageAlternativeVersionPolicy.append(
            ChatMessage.create("source", MessageRole.ASSISTANT, "Earlier reply"),
            "<n=\"甲\"/>[Repeated line]()\n<n=\"甲\"/>[Repeated line]()", "saved-version"
        ))
        val version = MessageAlternativeVersionPolicy.activeVersion(reply)
        val repeated = voices.ensureAnchors(reply).filter { it.segment.spokenText == "Repeated line" }
        assertEquals(2, repeated.size)
        val clips = repeated.mapIndexed { index, segment ->
            val audio = File(context.filesDir, "original-$index.mp3").apply { writeText("clip $index") }
            recording(reply, "clip-$index", segment.segment.spokenText, segment.anchor.id)
                .copy(sourceOrder = segment.anchor.sourceOrder, audioPath = audio.path, byteLength = audio.length())
        }
        clips.forEach { voices.save(it) }
        val anchors = voices.snapshotAnchorsForMessages(reply.sessionId, setOf(reply.id))
        suspend fun archive(name: String, anchorStates: List<VoiceAnchorState>, includeAudio: Boolean = true): SaveSlot =
            packages.createPackage(
                SaveSlot.create(reply.sessionId, name), SaveSlotImagePolicy.NONE, includeAudio,
                messageSource = { emit -> chats.forEachMessage(reply.sessionId, action = emit) },
                ragSource = {}, voices = clips, voiceAnchors = anchorStates
            )

        val modern = archive("layout", anchors)
        val oldV8 = archive("legacy v8", emptyList())
        val withoutAudio = archive("without audio", anchors, includeAudio = false)
        fun hasAnchorEntry(slot: SaveSlot): Boolean = ZipFile(File(context.filesDir,
            "save_slot_packages/${requireNotNull(slot.packageRef).fileName}")).use {
                it.getEntry("voice_anchors.jsonl") != null
            }
        assertTrue(hasAnchorEntry(modern))
        assertFalse(hasAnchorEntry(oldV8))
        assertFalse(hasAnchorEntry(withoutAudio))
        val corrupt = temp.newFile("duplicate-anchors.cbsave")
        val modernFile = File(context.filesDir, "save_slot_packages/${requireNotNull(modern.packageRef).fileName}")
        ZipFile(modernFile).use { original ->
            ZipOutputStream(corrupt.outputStream()).use { output ->
                for (entry in original.entries()) {
                    output.putNextEntry(ZipEntry(entry.name))
                    original.getInputStream(entry).use { it.copyTo(output) }
                    if (entry.name == "voice_anchors.jsonl") original.getInputStream(entry).use { it.copyTo(output) }
                    output.closeEntry()
                }
            }
        }
        val rejected = corrupt.inputStream().use { input ->
            runCatching { packages.importPackage(input, "unselected-target") { it } }.exceptionOrNull()
        }
        assertTrue(rejected is IllegalArgumentException && rejected.message.orEmpty().contains("重复语音锚点"))

        val remaining = requireNotNull(MessageAlternativeVersionPolicy.remove(reply, version.id))
        val removed = voices.deleteForVersion(reply, version.id) {
            chats.updateMessageIfUnchanged(reply, remaining) != null
        }
        assertEquals(clips.toSet(), removed.toSet())
        assertTrue(voices.snapshotAnchorsForMessages(reply.sessionId, setOf(reply.id)).isEmpty())

        suspend fun restore(packageSlot: SaveSlot, target: String): List<VoiceMessagePlacement> {
            val exported = File(context.filesDir, "$target-export.cbsave")
            exported.outputStream().use { packages.export(packageSlot, it) }
            val imported = exported.inputStream().use { packages.importPackage(it, target) { it }.slot }
            packages.openReader(imported).use { reader ->
                reader.validate()
                val restoredVoices = reader.restoreVoices(target) { sessionId, resourceId, input ->
                    val path = File(context.filesDir, "$sessionId-$resourceId.mp3")
                    path.outputStream().use { input.copyTo(it) }
                    FishAudioStoredAudio(path.path, 1_000, path.length())
                }
                chats.replaceMessagesForSessionStreaming(target) { emit -> reader.streamMessages(target, emit) }
                voices.restorePackagedForSession(target, restoredVoices) { emit -> reader.streamVoiceAnchors(target, emit) }
            }
            val coldChats = ChatRepository(JsonFileStorage(context))
            val cold = VoiceMessageRepository(JsonFileStorage(context), coldChats::getMessage)
            val message = requireNotNull(coldChats.getMessage(reply.id, target))
            assertEquals(version.id, MessageAlternativeVersionPolicy.activeVersionId(message))
            return cold.placementsForMessage(message)
        }

        for (target in listOf(reply.sessionId, "copied-session")) {
            val restored = restore(modern, target)
            assertEquals(repeated.map { it.segment.segmentIndex }, restored.map { it.segmentIndex })
            assertEquals(repeated.map { it.anchor.id }, restored.map { it.voice.anchorId })
            assertEquals(listOf("clip 0", "clip 1"), restored.map { File(it.voice.audioPath).readText() })
            assertTrue(restored.all { it.voice.sessionId == target && it.voice.versionOwnershipConfirmed })
            assertTrue(restored.none { it.voice.id in clips.map(GeneratedVoiceMessage::id) })
        }
        val older = restore(oldV8, "old-v8-session")
        assertEquals(listOf("clip 0", "clip 1"), older.map { File(it.voice.audioPath).readText() })
        assertTrue(older.all { it.segmentIndex == null && it.voice.anchorId == null })
    }

    @Test
    fun `same imported message and version IDs keep session anchors independent after edit and cleanup`() = runBlocking {
        val context = TestContext(temp.newFolder())
        val storage = JsonFileStorage(context)
        val source = ChatMessage.create("source", MessageRole.ASSISTANT, "<n=\"甲\"/>[Recorded]()")
        val target = source.copy(sessionId = "target")
        val version = MessageAlternativeVersionPolicy.activeVersion(source)
        val state = VoiceAnchorPolicy.initialState(source.id, version.content, version.id, source.sessionId, includeNarration = true)
        val key = VoiceAnchorStateKey(state)
        storage.saveEntity("voice_anchor_states", key.legacyStorageId, state, VoiceAnchorState.serializer())
        val sharedAnchorId = state.anchors.single().id
        val sourceVoice = recording(source, "source-voice", "Recorded", sharedAnchorId)
        val targetVoice = recording(target, "target-voice", "Recorded", sharedAnchorId)
        val repo = VoiceMessageRepository(storage)
        repo.save(sourceVoice)
        repo.restoreCopiedAnchors(listOf(state.copy(sessionId = target.sessionId)))
        repo.save(targetVoice)

        repo.ensureAnchors(MessageAlternativeVersionPolicy.editCurrentContent(target, ""))
        assertNull(repo.get(targetVoice.id)?.anchorId)
        assertEquals(sourceVoice, repo.get(sourceVoice.id))
        assertEquals(listOf(state), repo.snapshotAnchorsForMessages(source.sessionId, setOf(source.id)))
        assertTrue(File(context.filesDir, "entities/voice_anchor_states/${key.legacyStorageId}.json").exists())

        repo.deleteForSession(target.sessionId)
        val cold = VoiceMessageRepository(JsonFileStorage(context))
        assertEquals(sourceVoice, cold.placementsForMessage(source).single().voice)
        assertEquals(sharedAnchorId, cold.ensureAnchors(source).single().anchor.id)
        assertTrue(cold.snapshotAnchorsForMessages(target.sessionId, setOf(target.id)).isEmpty())
        assertFalse(cold.listForSession(target.sessionId).any())
    }

    @Test
    fun `switch replacement and deletion evict only stale recording IDs and reject stale handoffs`() = runBlocking {
        val storage = JsonFileStorage(TestContext(temp.newFolder()))
        val chats = ChatRepository(storage)
        val voices = VoiceMessageRepository(storage, chats::getMessage)
        val initial = ChatMessage.create("test-session", MessageRole.ASSISTANT, "Same displayed text")
        val alternatives = MessageAlternativeVersionPolicy.append(initial, initial.content, "other-version")
        val shown = chats.addMessage(MessageAlternativeVersionPolicy.select(alternatives, 0))
        val other = MessageAlternativeVersionPolicy.select(shown, 1)
        val first = recording(shown, "first", initial.content, null)
        val second = recording(other, "second", initial.content, null)
        val independent = first.copy(id = "independent", sessionId = "different-session")
        for (voice in listOf(first, second, independent)) voices.save(voice)
        val removals = mutableListOf<Set<String>>()
        val evict: suspend (Set<String>) -> Unit = { removals += it }

        assertNull(voices.switchDisplayedVersion(shown.copy(content = "stale"), other, chats, evict))
        assertTrue(removals.isEmpty())
        val oldRevision = chats.messageTimelineRevision(shown.sessionId)
        chats.replaceMessagesForSession(shown.sessionId, listOf(shown))
        assertEquals(shown, chats.getMessage(shown.id, shown.sessionId))
        assertNull(voices.switchDisplayedVersion(shown, other, chats, evict, oldRevision))
        assertTrue(removals.isEmpty())
        val revision = chats.messageTimelineRevision(shown.sessionId)
        val committed = requireNotNull(voices.switchDisplayedVersion(shown, other, chats, evict, revision))
        assertEquals(listOf(setOf(first.id)), removals)
        voices.withCurrentPlaybackQueue(listOf(first, second), chats) { assertEquals(listOf(second), it) }
        val stale = runCatching {
            voices.ensureAnchorsForVersion(other, requireNotNull(second.messageVersionId), "Changed text", requireSameContent = true)
        }
        assertTrue(stale.exceptionOrNull() is IllegalStateException)

        val replacement = second.copy(audioPath = "replacement.mp3")
        voices.saveForExistingVersion(replacement, replacing = second, removeFromPlayback = evict) { committed }
        assertEquals(listOf(setOf(first.id), setOf(second.id)), removals)
        voices.withCurrentPlaybackQueue(listOf(second), chats) { assertTrue(it.isEmpty()) }
        voices.withCurrentPlaybackQueue(listOf(replacement), chats) { assertEquals(listOf(replacement), it) }

        assertEquals(first, voices.delete(first.id, evict))
        assertEquals(listOf(setOf(first.id), setOf(second.id), setOf(first.id)), removals)
        assertEquals(independent, voices.get(independent.id))
        assertEquals(replacement, VoiceMessageRepository(storage).get(second.id))
    }

    @Test
    fun `replacing or deleting one recording preserves bytes shared by another owner`() = runBlocking {
        val context = TestContext(temp.newFolder())
        val voices = VoiceMessageRepository(JsonFileStorage(context))
        val reply = ChatMessage.create("session", MessageRole.ASSISTANT, "Recorded reply")
        val audio = File(context.filesDir, "audio/generated/session/shared.mp3").apply {
            parentFile!!.mkdirs()
            writeText("shared bytes")
        }
        val first = recording(reply, "first", reply.content, null).copy(audioPath = audio.path)
        val second = recording(reply, "second", reply.content, null).copy(audioPath = audio.path)
        voices.save(first)
        voices.save(second)
        val replacement = first.copy(audioPath = File(context.filesDir, "replacement.mp3").path)

        voices.saveForExistingVersion(replacement, replacing = first) { reply }
        voices.pruneUnreferencedAudio(listOf(first.audioPath)) { File(it).delete() }
        assertEquals("shared bytes", audio.readText())
        assertEquals(second, voices.get(second.id))
        voices.delete(second.id)
        voices.pruneUnreferencedAudio(listOf(second.audioPath)) { File(it).delete() }
        assertFalse(audio.exists())
        assertEquals(replacement, voices.get(first.id))
    }

    private fun recording(message: ChatMessage, id: String, text: String, anchor: String?) = GeneratedVoiceMessage.create(
        sessionId = message.sessionId, messageId = message.id,
        messageVersionId = MessageAlternativeVersionPolicy.activeVersionId(message),
        anchorId = anchor, sourceOrder = 0, sourceSegmentKind = "DIALOGUE", sourceSpeakerName = "甲",
        sourceText = text, taggedText = text, characterId = "character", characterName = "Synthetic",
        voice = FishAudioVoiceBinding("fixture", "Synthetic"), fishModelId = "model",
        audioPath = "$id.mp3", durationMs = 1000, byteLength = 10
    )

    private class TestContext(private val dir: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = dir
    }
}
