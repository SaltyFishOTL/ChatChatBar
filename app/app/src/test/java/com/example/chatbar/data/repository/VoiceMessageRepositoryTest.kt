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
import com.example.chatbar.domain.chat.SaveSlotPackageStorage
import com.example.chatbar.domain.voice.FishAudioStoredAudio
import com.example.chatbar.domain.voice.VoiceAnchorPolicy
import com.example.chatbar.domain.voice.VoiceAnchorStateKey
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import kotlinx.coroutines.runBlocking
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
