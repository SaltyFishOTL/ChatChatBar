package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.PromptCacheCheckpoint
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptCacheCheckpointPolicyTest {
    @Test
    fun createsCheckpointAndKeepsOnlyMessagesAfterCoveredAssistantHot() {
        val messages = conversation(3)

        val plan = PromptCacheCheckpointPolicy.plan(
            allMessages = messages,
            current = null,
            stablePromptFingerprint = "stable",
            longTermMemoryEnabled = true,
            currentMemory = "冻结记忆",
            memoryUpdatedThroughMessageId = "assistant-1"
        )

        assertTrue(plan.checkpointChanged)
        assertEquals("冻结记忆", plan.checkpoint?.memorySnapshot)
        assertEquals("assistant-1", plan.checkpoint?.coveredThroughMessageId)
        assertEquals(listOf("user-2", "assistant-2"), plan.hotMessages.map { it.id })
    }

    @Test
    fun keepsCheckpointUntilCoveredHistoryAdvancesByBatch() {
        val messages = conversation(5)
        val current = PromptCacheCheckpoint(
            stablePromptFingerprint = "stable",
            memorySnapshot = "旧快照",
            coveredThroughMessageId = "assistant-1"
        )

        val plan = PromptCacheCheckpointPolicy.plan(
            allMessages = messages,
            current = current,
            stablePromptFingerprint = "stable",
            longTermMemoryEnabled = true,
            currentMemory = "新记忆",
            memoryUpdatedThroughMessageId = "assistant-3"
        )

        assertFalse(plan.checkpointChanged)
        assertEquals(current, plan.checkpoint)
        assertEquals(listOf("user-2", "assistant-2", "user-3", "assistant-3", "user-4", "assistant-4"), plan.hotMessages.map { it.id })
    }

    @Test
    fun advancesCheckpointAfterThreeConversationTurns() {
        val messages = conversation(6)
        val current = PromptCacheCheckpoint(
            stablePromptFingerprint = "stable",
            memorySnapshot = "旧快照",
            coveredThroughMessageId = "assistant-1"
        )

        val plan = PromptCacheCheckpointPolicy.plan(
            allMessages = messages,
            current = current,
            stablePromptFingerprint = "stable",
            longTermMemoryEnabled = true,
            currentMemory = "新记忆",
            memoryUpdatedThroughMessageId = "assistant-4"
        )

        assertTrue(plan.checkpointChanged)
        assertEquals("新记忆", plan.checkpoint?.memorySnapshot)
        assertEquals("assistant-4", plan.checkpoint?.coveredThroughMessageId)
        assertEquals(listOf("user-5", "assistant-5"), plan.hotMessages.map { it.id })
    }

    @Test
    fun fingerprintChangeRebuildsCheckpoint() {
        val messages = conversation(2)
        val current = PromptCacheCheckpoint(
            stablePromptFingerprint = "old",
            memorySnapshot = "旧快照",
            coveredThroughMessageId = "assistant-0"
        )

        val plan = PromptCacheCheckpointPolicy.plan(
            allMessages = messages,
            current = current,
            stablePromptFingerprint = "new",
            longTermMemoryEnabled = true,
            currentMemory = "新快照",
            memoryUpdatedThroughMessageId = "assistant-0"
        )

        assertTrue(plan.checkpointChanged)
        assertEquals("new", plan.checkpoint?.stablePromptFingerprint)
    }

    @Test
    fun legacySessionJsonUsesCheckpointDefault() {
        val session = Json { ignoreUnknownKeys = true }.decodeFromString(
            ChatSession.serializer(),
            """{"id":"s","characterCardId":"c","title":"旧会话","createdAt":1,"updatedAt":1}"""
        )

        assertNull(session.promptCacheCheckpoint)
    }

    private fun conversation(turnCount: Int): List<ChatMessage> =
        (0 until turnCount).flatMap { turn ->
            listOf(
                message("user-$turn", MessageRole.USER, turn * 2L),
                message("assistant-$turn", MessageRole.ASSISTANT, turn * 2L + 1)
            )
        }

    private fun message(id: String, role: MessageRole, time: Long): ChatMessage = ChatMessage(
        id = id,
        sessionId = "session",
        role = role,
        content = id,
        createdAt = time,
        updatedAt = time
    )
}
