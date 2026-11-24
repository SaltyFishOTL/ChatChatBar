package com.example.chatbar.ui.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChunkSourceType
import com.example.chatbar.data.local.entity.VectorChunk
import com.example.chatbar.data.local.entity.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class RagRetrievalCredentialTest {
    @Test
    fun archivedMemoryUsesOnlyMatchingEmbeddingSpaceForVectorRank() {
        fun chunk(content: String, key: String?, vector: List<Float> = listOf(1f, 0f)) =
            VectorChunk.create(
                ChunkSourceType.CHAT_MEMORY, "session-1", content, vector,
                metadata = key?.let { mapOf("embeddingKey" to it) }.orEmpty()
            )

        val sameSpace = chunk("Vector match without keyword", "current")
        val otherSpace = chunk("Unrelated restored memory", "previous")
        val lexicalOtherSpace = chunk("Archived lantern", "previous")
        val lexicalLegacy = chunk("Legacy lantern", null)
        val wrongDimension = chunk("Wrong dimension lantern", "current", listOf(1f))
        val ranks = rankChunksByMultiRoute(
            listOf(sameSpace, otherSpace, lexicalOtherSpace, lexicalLegacy, wrongDimension),
            listOf(1f, 0f), "lantern", "current", 5
        ).associateBy { it.chunk.id }

        assertEquals(1, ranks.getValue(sameSpace.id).vectorRank)
        assertEquals(1f, ranks.getValue(sameSpace.id).vectorScore!!, 0f)
        assertFalse(otherSpace.id in ranks)
        for (lexical in listOf(lexicalOtherSpace, lexicalLegacy, wrongDimension)) {
            val hit = ranks.getValue(lexical.id)
            assertNull(hit.vectorRank)
            assertNull(hit.vectorScore)
            assertTrue(hit.lexicalScore > 0f)
        }
    }

    @Test
    fun retrievalCredentials_keepOnlyLastAssistantReply() {
        val messages = listOf(
            message("u1", MessageRole.USER, "old user question"),
            message("a1", MessageRole.ASSISTANT, "old assistant reply"),
            message("u2", MessageRole.USER, "middle user question"),
            message("a2", MessageRole.ASSISTANT, "latest assistant reply"),
            message("u3", MessageRole.USER, "current user message")
        )

        val credentials = buildRagRetrievalCredentialMessages(
            contextMsgs = messages,
            currentUserMessageId = "u3",
            currentUserContent = "current user message"
        )

        assertEquals(listOf("a2"), credentials.map { it.id })
    }

    @Test
    fun retrievalCredentials_keepLastAssistantOptions() {
        val dash = "\u2014".repeat(12)
        val content = "有效正文\n$dash\n[选项一]()\n$dash"

        val credentials = buildRagRetrievalCredentialMessages(
            contextMsgs = listOf(message("a1", MessageRole.ASSISTANT, content))
        )

        assertEquals(content, credentials.single().displayContent)
    }

    @Test
    fun fallbackRagQuery_usesOnlyCurrentUserAndLastAssistant() {
        val credentials = listOf(
            message("a2", MessageRole.ASSISTANT, "latest assistant reply")
        )

        val query = buildRagQuery(
            currentUserContent = "current user message",
            contextMsgs = credentials
        )

        assertTrue(query.contains("current user message"))
        assertTrue(query.contains("latest assistant reply"))
        assertFalse(query.contains("old user question"))
        assertFalse(query.contains("middle user question"))
    }
}

private fun message(id: String, role: MessageRole, content: String): ChatMessage {
    return ChatMessage(
        id = id,
        sessionId = "session-1",
        role = role,
        content = content,
        createdAt = 1L,
        updatedAt = 1L
    )
}
