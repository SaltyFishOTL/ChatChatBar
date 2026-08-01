package com.example.chatbar.domain.memory

import com.example.chatbar.data.local.entity.MemoryBackfillState
import com.example.chatbar.data.local.entity.MemoryBackfillStatus
import com.example.chatbar.data.local.entity.MemoryDecisionTier
import com.example.chatbar.data.local.entity.MemorySessionState
import com.example.chatbar.data.local.entity.PendingMemoryDecision
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryCompressionDecisionPolicyTest {
    @Test
    fun compressionChoiceClearsDecisionImmediatelyAndPreservesFullRegeneration() {
        val current = MemorySessionState(
            sessionId = "session",
            pendingDecision = PendingMemoryDecision(MemoryDecisionTier.EPISODE),
            fullRegenerationPending = true,
            revision = 7,
            updatedAt = 10
        )

        val resolution = MemoryCompressionDecisionPolicy.resolve(
            current = current,
            expand = false,
            canExpand = true,
            now = 20
        )!!

        assertNull(resolution.state.pendingDecision)
        assertTrue(resolution.state.episodeCompressionPromptDeclined)
        assertTrue(resolution.state.fullRegenerationPending)
        assertEquals(8L, resolution.state.revision)
        assertEquals(20L, resolution.state.updatedAt)
        assertEquals(MemoryCompressionDecisionContinuation.ARCHIVE, resolution.continuation)
    }

    @Test
    fun expansionChoiceClearsDecisionWithoutDecliningCompression() {
        val resolution = MemoryCompressionDecisionPolicy.resolve(
            current = MemorySessionState(
                sessionId = "session",
                pendingDecision = PendingMemoryDecision(MemoryDecisionTier.ARC)
            ),
            expand = true,
            canExpand = true
        )!!

        assertNull(resolution.state.pendingDecision)
        assertFalse(resolution.state.arcCompressionPromptDeclined)
        assertEquals(MemoryCompressionDecisionContinuation.ARCHIVE, resolution.continuation)
    }

    @Test
    fun pausedBackfillResumesThroughBackfillCoordinator() {
        val resolution = MemoryCompressionDecisionPolicy.resolve(
            current = MemorySessionState(
                sessionId = "session",
                pendingDecision = PendingMemoryDecision(MemoryDecisionTier.ERA),
                backfill = MemoryBackfillState(
                    status = MemoryBackfillStatus.PAUSED,
                    pendingSourceTurnIds = listOf("s1")
                )
            ),
            expand = false,
            canExpand = true
        )!!

        assertEquals(MemoryCompressionDecisionContinuation.BACKFILL, resolution.continuation)
        assertTrue(resolution.state.eraCompressionPromptDeclined)
        assertEquals(0, resolution.state.eraCompressionsSincePrompt)
    }

    @Test
    fun expansionAtMaximumKeepsDecisionByFailingExplicitly() {
        val current = MemorySessionState(
            sessionId = "session",
            pendingDecision = PendingMemoryDecision(MemoryDecisionTier.EPISODE)
        )

        val error = runCatching {
            MemoryCompressionDecisionPolicy.resolve(
                current = current,
                expand = true,
                canExpand = false
            )
        }.exceptionOrNull()

        assertEquals("长期记忆已达到最高上限，请选择保持上限并压缩", error?.message)
        assertEquals(MemoryDecisionTier.EPISODE, current.pendingDecision?.tier)
    }
}
