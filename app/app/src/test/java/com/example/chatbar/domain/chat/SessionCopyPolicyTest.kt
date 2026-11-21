package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.ChunkSourceType
import com.example.chatbar.data.local.entity.MemoryUpdateStatus
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.VectorChunk
import com.example.chatbar.domain.memory.MemorySourceFingerprint
import com.example.chatbar.domain.rag.ChatMemoryIndexPolicy
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionCopyPolicyTest {
    private val source = ChatSession(
        id = "source", characterCardId = "card", title = "角色", createdAt = 1, updatedAt = 2
    )

    @Test
    fun `copy remaps image anchors and retains version and source evidence`() {
        val user = message("a", MessageRole.USER)
        val reply = message("b", MessageRole.ASSISTANT).copy(alternatives = listOf("正文", "其他回复"))
        val image = message("c", MessageRole.ASSISTANT).copy(generatedFromMessageId = reply.id)
        val policy = SessionCopyPolicy("target", setOf(user.id, reply.id, image.id))
        val copied = listOf(user, reply, image).map { policy.message(it, "target") }

        assertEquals(copied[1].id, copied[2].generatedFromMessageId)
        assertTrue(copied.none { it.id in setOf("a", "b", "c") })
        assertEquals(MessageAlternativeVersionPolicy.versions(reply), MessageAlternativeVersionPolicy.versions(copied[1]))
        assertEquals(
            MemorySourceFingerprint.semantic("turn", listOf(user, reply, image), source),
            MemorySourceFingerprint.semantic("turn", copied, source.copy(id = "target"))
        )
        assertEquals(copied, copied.sortedWith(ChatMessage.TimelineComparator))
    }

    @Test
    fun `each copy owns distinct messages even when copying a copy`() {
        val original = message("message", MessageRole.USER)
        val first = SessionCopyPolicy("first", setOf(original.id)).message(original, "first")
        val second = SessionCopyPolicy("second", setOf(original.id)).message(original, "second")
        val third = SessionCopyPolicy("third", setOf(first.id)).message(first, "third")
        assertEquals(4, setOf(original.id, first.id, second.id, third.id).size)
        assertTrue(third.id.length <= first.id.length)
    }

    @Test
    fun `rag references point only to copied messages and retain vectors`() {
        val policy = SessionCopyPolicy("target", setOf("a", "b"))
        val original = VectorChunk(
            id = "chunk", sourceType = ChunkSourceType.CHAT_MEMORY, sourceId = "target",
            messageId = "b", content = "索引正文", embedding = listOf(0.1f, 0.2f), createdAt = 1,
            metadata = mapOf(
                "messageIds" to "a,b", "sourceTurnId" to "turn", "embeddingKey" to "model",
                ChatMemoryIndexPolicy.ACTION_CHOICE_SOURCE_IDS to "a,b",
            )
        )
        val copied = policy.chunk(original)
        assertEquals(policy.messageId("b"), copied.messageId)
        assertEquals("${policy.messageId("a")},${policy.messageId("b")}", copied.metadata["messageIds"])
        assertEquals("${policy.messageId("a")},${policy.messageId("b")}",
            copied.metadata[ChatMemoryIndexPolicy.ACTION_CHOICE_SOURCE_IDS])
        assertEquals("turn", copied.metadata["sourceTurnId"])
        assertEquals(original.embedding, copied.embedding)
        assertEquals(original.content, copied.content)
    }

    @Test
    fun `session settings survive while runtime identity resets`() {
        val original = source.copy(
            playerName = "玩家", extraWorldBookIds = listOf("world"), voiceLanguage = "日语",
            novelAiNaturalLanguageMode = true, automaticImageGenerationEnabled = true,
            supplementarySetting = "设定", timedWorldInfo = emptyMap(), isPinned = true,
            memoryStateRevision = 20, memoryUpdateStatus = MemoryUpdateStatus.UPDATING,
            longTermMemoryUpdatedThroughMessageId = "a"
        )
        val policy = SessionCopyPolicy("target", setOf("a"))
        val copied = policy.session(original, "target", "副本", 42)
        assertEquals(original.copy(
            id = "target", displayTitleOverride = "副本", createdAt = 42, updatedAt = 42,
            memoryStateRevision = 0, memoryUpdateStatus = MemoryUpdateStatus.IDLE,
            longTermMemoryUpdatedThroughMessageId = policy.messageId("a")
        ), copied)
        assertNotEquals(original.id, copied.id)
    }

    @Test
    fun `copy names avoid collisions without exceeding display title limit`() {
        val longTitle = source.copy(displayTitleOverride = "名".repeat(SessionDisplayTitlePolicy.MAX_LENGTH))
        val first = SessionCopyPolicy.copyName(longTitle, listOf(longTitle))
        val second = SessionCopyPolicy.copyName(longTitle, listOf(longTitle, source.copy(displayTitleOverride = first)))
        assertNotEquals(first, second)
        assertTrue(first.length <= SessionDisplayTitlePolicy.MAX_LENGTH)
        assertTrue(second.length <= SessionDisplayTitlePolicy.MAX_LENGTH)
    }

    private fun message(id: String, role: MessageRole) = ChatMessage(
        id = id, sessionId = "source", role = role, content = "正文", createdAt = 1,
        updatedAt = 1, orderKey = 1, sourceTurnId = "turn", sourceTurnOrder = 1
    )
}
