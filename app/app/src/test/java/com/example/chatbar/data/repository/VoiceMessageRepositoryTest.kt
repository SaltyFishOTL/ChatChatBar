package com.example.chatbar.data.repository

import android.content.ContextWrapper
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.FishAudioVoiceBinding
import com.example.chatbar.data.local.entity.GeneratedVoiceMessage
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.VoiceAnchorState
import com.example.chatbar.domain.chat.MessageAlternativeVersionPolicy
import com.example.chatbar.domain.voice.VoiceAnchorPolicy
import com.example.chatbar.domain.voice.VoiceAnchorStateKey
import java.io.File
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
