package com.example.chatbar.domain.rag

import com.example.chatbar.data.local.entity.ChunkSourceType
import com.example.chatbar.data.local.entity.VectorChunk
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Test

class ChatMemoryIdentityTest {
    @Test
    fun stableChunkId_usesSessionAndMessageIdentity() {
        assertEquals(
            chatMemoryChunkId("session-1", "message-1"),
            chatMemoryChunkId("session-1", "message-1")
        )
        assertFalse(
            chatMemoryChunkId("session-1", "message-1") ==
                chatMemoryChunkId("session-1", "message-2")
        )
    }

    @Test
    fun prune_removesOrphansAndKeepsNewestDuplicate() {
        val older = chunk("old", "message-1", 10)
        val newer = chunk("new", "message-1", 20)
        val orphan = chunk("orphan", "deleted-message", 30)
        val unique = chunk("unique", "message-2", 40)

        val pruned = chatMemoryChunkIdsToPrune(
            chunks = listOf(older, newer, orphan, unique),
            liveMessageIds = setOf("message-1", "message-2")
        )

        assertEquals(setOf("old", "orphan"), pruned)
        assertTrue("new" !in pruned)
        assertTrue("unique" !in pruned)
    }

    @Test
    fun manualChunk_survivesMessageOrphanPruning() {
        val manual = VectorChunk(
            id = "manual",
            sourceType = ChunkSourceType.CHAT_MEMORY,
            sourceId = "session-1",
            content = "用户手动维护的记忆",
            embedding = listOf(1f),
            metadata = mapOf("indexMode" to "manual"),
            createdAt = 10
        )

        assertTrue(chatMemoryChunkIdsToPrune(listOf(manual), emptySet()).isEmpty())
        assertTrue(manual.isChatMemoryForSession("session-1"))
        assertFalse(manual.isChatMemoryForSession("session-2"))
    }

    @Test
    fun pairChunk_isPrunedWhenEitherMessageIsMissing() {
        val pair = VectorChunk(
            id = "pair",
            sourceType = ChunkSourceType.CHAT_MEMORY,
            sourceId = "session-1",
            messageId = "assistant",
            content = "user 与 assistant",
            embedding = listOf(1f),
            metadata = mapOf(
                "messageIds" to "user,assistant",
                "indexMode" to "message_pair"
            ),
            createdAt = 10
        )

        assertEquals(
            setOf("pair"),
            chatMemoryChunkIdsToPrune(listOf(pair), setOf("assistant"))
        )
    }

    private fun chunk(id: String, messageId: String, createdAt: Long) = VectorChunk(
        id = id,
        sourceType = ChunkSourceType.CHAT_MEMORY,
        sourceId = "session-1",
        messageId = messageId,
        content = id,
        embedding = listOf(1f),
        metadata = mapOf(
            "messageIds" to messageId,
            "indexMode" to "single_message_contextual"
        ),
        createdAt = createdAt
    )
}
