package com.example.chatbar.domain.memory

import com.example.chatbar.data.local.entity.MemoryBackfillStatus
import com.example.chatbar.data.local.entity.MemoryDecisionTier
import com.example.chatbar.data.local.entity.MemorySessionState

enum class MemoryCompressionDecisionContinuation { ARCHIVE, BACKFILL }

data class MemoryCompressionDecisionResolution(
    val state: MemorySessionState,
    val continuation: MemoryCompressionDecisionContinuation
)

object MemoryCompressionDecisionPolicy {
    fun resolve(
        current: MemorySessionState,
        expand: Boolean,
        canExpand: Boolean,
        now: Long = System.currentTimeMillis()
    ): MemoryCompressionDecisionResolution? {
        val decision = current.pendingDecision ?: return null
        check(!expand || canExpand) { "长期记忆已达到最高上限，请选择保持上限并压缩" }
        val chosen = if (expand) {
            current
        } else {
            when (decision.tier) {
                MemoryDecisionTier.EPISODE -> current.copy(
                    episodeCompressionPromptDeclined = true
                )
                MemoryDecisionTier.ARC -> current.copy(
                    arcCompressionPromptDeclined = true
                )
                MemoryDecisionTier.ERA -> current.copy(
                    eraCompressionPromptDeclined = true,
                    eraCompressionsSincePrompt = 0
                )
            }
        }
        val continuation = if (
            current.backfill.status == MemoryBackfillStatus.PAUSED &&
            current.backfill.pendingSourceTurnIds.isNotEmpty()
        ) {
            MemoryCompressionDecisionContinuation.BACKFILL
        } else {
            MemoryCompressionDecisionContinuation.ARCHIVE
        }
        return MemoryCompressionDecisionResolution(
            state = chosen.copy(
                pendingDecision = null,
                revision = current.revision + 1,
                updatedAt = now
            ),
            continuation = continuation
        )
    }
}
