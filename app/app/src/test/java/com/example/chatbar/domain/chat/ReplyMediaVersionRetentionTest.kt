package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.moment.MomentImageVersions
import com.example.chatbar.domain.moment.MomentMediaRetention
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ReplyMediaVersionRetentionTest {
    private val json = Json { encodeDefaults = true; ignoreUnknownKeys = true }
    private fun message() = ChatMessage("m", "s", MessageRole.ASSISTANT, "first", createdAt = 1, updatedAt = 1)

    @Test fun `more than twenty versions survive serialization with stable identities and modes`() {
        var message = message()
        repeat(24) { index -> message = MessageAlternativeVersionPolicy.append(message, "body$index", "v$index") }
        val restored = json.decodeFromString(ChatMessage.serializer(), json.encodeToString(ChatMessage.serializer(), message))
        assertEquals(25, MessageAlternativeVersionPolicy.versions(restored).size)
        assertEquals("m", MessageAlternativeVersionPolicy.versions(restored).first().id)
        assertEquals("first", MessageAlternativeVersionPolicy.versions(restored).first().content)
        assertEquals(message, restored)
    }

    @Test fun `selected and inactive deletion preserve all other versions and shared media`() {
        var source = message().copy(images = listOf("shared.png"), sourceTurnId = "turn")
        repeat(3) { source = MessageAlternativeVersionPolicy.append(source, "same", "v$it") }
        val expected = MessageAlternativeVersionPolicy.versions(source).filterNot { it.id == "v1" }
        val removed = requireNotNull(MessageAlternativeVersionPolicy.remove(source, "v1"))
        assertEquals(expected, MessageAlternativeVersionPolicy.versions(removed))
        assertEquals("v2", MessageAlternativeVersionPolicy.activeVersionId(removed))
        val removedActive = requireNotNull(MessageAlternativeVersionPolicy.remove(removed, "v2"))
        assertEquals("v0", MessageAlternativeVersionPolicy.activeVersionId(removedActive))
        assertEquals(source.images, removedActive.images)
        assertEquals(source.sourceTurnId, removedActive.sourceTurnId)
        assertEquals(source.orderKey, removedActive.orderKey)
    }

    @Test fun `deleting last text segment selects a survivor instead of deleting all alternatives`() {
        val source = MessageAlternativeVersionPolicy.append(message(), "current", "next")
        val outcome = editRoleplayMessageSegment(source, 0, 7, "")
        val survivor = requireNotNull(outcome.message)
        assertEquals("first", survivor.displayContent)
        assertEquals("m", MessageAlternativeVersionPolicy.activeVersionId(survivor))
        assertFalse(outcome.deleteMemoryForMessage)
        assertNull(editRoleplayMessageSegment(message(), 0, 5, "").message)
    }

    @Test fun `undo and redo survive persistence and cannot apply to edited or other version`() {
        val original = MessageAlternativeVersionPolicy.append(message().copy(content = "before"), "before", "version")
        val repaired = MessageFormatRepairPolicy.replaceCurrentDisplayContent(original, "after",
            MessageFormatRepairPolicy.completedRepairNotice("before", "after"))
        val undone = requireNotNull(MessageFormatRepairPolicy.restoreOriginal(repaired))
        val reopened = json.decodeFromString(ChatMessage.serializer(), json.encodeToString(ChatMessage.serializer(), undone))
        assertEquals("before", reopened.displayContent)
        assertEquals("after", MessageFormatRepairPolicy.redoRepair(reopened)?.displayContent)
        assertEquals(original.alternatives.first(), reopened.alternatives.first())
        assertNull(MessageFormatRepairPolicy.redoRepair(MessageAlternativeVersionPolicy.select(reopened, 0)))
        assertNull(MessageFormatRepairPolicy.redoRepair(MessageAlternativeVersionPolicy.editCurrentContent(reopened, "manual edit")))
    }

    @Test fun `removing a different alternative preserves local undo and redo`() {
        val original = MessageAlternativeVersionPolicy.append(message().copy(content = "before"), "other", "other")
        val first = MessageAlternativeVersionPolicy.select(original, 0)
        val repaired = MessageFormatRepairPolicy.replaceCurrentDisplayContent(first, "after",
            MessageFormatRepairPolicy.completedRepairNotice("before", "after"))
        val undone = requireNotNull(MessageFormatRepairPolicy.restoreOriginal(repaired))

        fun removeOther(current: ChatMessage): ChatMessage =
            requireNotNull(MessageAlternativeVersionPolicy.remove(MessageAlternativeVersionPolicy.select(current, 1), "other"))

        assertEquals("before", MessageFormatRepairPolicy.restoreOriginal(removeOther(repaired))?.displayContent)
        assertEquals("after", MessageFormatRepairPolicy.redoRepair(removeOther(undone))?.displayContent)
        assertNull(MessageAlternativeVersionPolicy.remove(repaired, first.id)?.formatRepairNotice)
    }

    @Test fun `moments retain original images metadata and prompts across selection and serialization`() {
        val old = MomentPost("p", "card", "s", senderName = "name", text = "post", imagePath = "old.png",
            imagePrompt = "old prompt", scheduledAt = 1, generatedAt = 2)
        val newest = MomentImageVersions.append(old, "new.png", "new prompt", null)
        val reopened = json.decodeFromString(MomentPost.serializer(), json.encodeToString(MomentPost.serializer(), newest))
        assertEquals(2, reopened.imageVersions.size)
        val selected = MomentImageVersions.select(reopened, reopened.imageVersions.first().id)
        assertEquals("old.png", selected.imagePath)
        assertEquals("old prompt", selected.imagePrompt)
        assertEquals(old.text, selected.text)
        assertTrue(MomentMediaRetention.unreferencedPaths(listOf("old.png", "new.png"), listOf(reopened)).isEmpty())
    }
}
