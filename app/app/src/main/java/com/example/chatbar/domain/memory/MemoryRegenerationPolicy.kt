package com.example.chatbar.domain.memory

import com.example.chatbar.data.local.entity.MemoryBackfillState
import com.example.chatbar.data.local.entity.MemoryGap
import com.example.chatbar.data.local.entity.MemoryGapReason
import com.example.chatbar.data.local.entity.MemoryBackfillStatus
import com.example.chatbar.data.local.entity.MemorySessionState
import com.example.chatbar.data.local.entity.MemoryTimelineEntry

object MemoryRegenerationPolicy {
    fun deferIncompleteBackfillTail(
        current: MemorySessionState
    ): MemorySessionState? {
        val remaining = current.backfill.pendingSourceTurnIds
        if (!current.fullRegenerationPending ||
            current.backfill.status != MemoryBackfillStatus.RUNNING ||
            current.pendingDecision != null ||
            remaining.isEmpty()
        ) {
            return null
        }
        val orderBySourceId = current.timeline.associate { it.sourceTurnId to it.sourceOrder }
        val nextPending = (current.pendingSourceTurnIds + remaining)
            .distinct()
            .sortedBy { orderBySourceId[it] }
        val nextPendingSet = nextPending.toSet()
        return current.copy(
            gaps = current.gaps.mapNotNull { gap ->
                val retained = gap.sourceTurnIds.filterNot { it in nextPendingSet }
                gap.copy(sourceTurnIds = retained).takeIf { retained.isNotEmpty() }
            },
            pendingSourceTurnIds = nextPending,
            backfill = current.backfill.copy(pendingSourceTurnIds = emptyList())
        )
    }

    /** 放弃完整重建：清除重建标志，保留已录入节点与历史；剩余来源继续留在Gap中等待普通补录。 */
    fun abortFullRegeneration(
        current: MemorySessionState,
        now: Long = System.currentTimeMillis()
    ): MemorySessionState = current.copy(
        fullRegenerationPending = false,
        backfill = MemoryBackfillState(
            status = MemoryBackfillStatus.IDLE,
            pendingSourceTurnIds = emptyList(),
            updatedAt = now
        ),
        revision = current.revision + 1,
        updatedAt = now
    )

    fun resetForFullRegeneration(
        current: MemorySessionState,
        timeline: List<MemoryTimelineEntry>,
        backfillSourceTurnIds: List<String>,
        memoryEnabled: Boolean,
        now: Long = System.currentTimeMillis()
    ): MemorySessionState {
        val requestedIds = backfillSourceTurnIds.toSet()
        val entries = timeline.asSequence()
            .filterNot { it.tombstone }
            .filter { it.sourceTurnId in requestedIds }
            .sortedBy { it.sourceOrder }
            .toList()
        val groups = mutableListOf<MutableList<MemoryTimelineEntry>>()
        entries.forEach { entry ->
            val currentGroup = groups.lastOrNull()
            if (currentGroup == null || entry.displayT != currentGroup.last().displayT + 1) {
                groups += mutableListOf(entry)
            } else {
                currentGroup += entry
            }
        }
        val gaps = groups.map { group ->
            MemoryGap(
                id = MemoryGap.newId(),
                sourceTurnIds = group.map { it.sourceTurnId },
                startSourceOrder = group.first().sourceOrder,
                endSourceOrder = group.last().sourceOrder,
                reason = MemoryGapReason.LEGACY_UNKNOWN,
                createdAt = now
            )
        }
        return MemorySessionState(
            sessionId = current.sessionId,
            timeline = timeline,
            gaps = gaps,
            fullRegenerationPending = true,
            memoryWasEnabled = memoryEnabled,
            recordingStartsAfterSourceOrder = null,
            gapRetentionVersion = CURRENT_MEMORY_GAP_RETENTION_VERSION,
            projectedDeletedSourceTurnIds = current.projectedDeletedSourceTurnIds,
            revision = current.revision + 1,
            createdAt = current.createdAt,
            updatedAt = now
        )
    }
}
