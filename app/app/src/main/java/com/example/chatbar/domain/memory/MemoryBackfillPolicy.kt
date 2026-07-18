package com.example.chatbar.domain.memory

import com.example.chatbar.data.local.entity.MemoryBackfillState
import com.example.chatbar.data.local.entity.MemoryBackfillStatus
import com.example.chatbar.data.local.entity.MemoryGap
import com.example.chatbar.data.local.entity.MemoryGapReason
import com.example.chatbar.data.local.entity.MemoryNode
import com.example.chatbar.data.local.entity.MemoryTimelineEntry

const val CURRENT_MEMORY_GAP_RETENTION_VERSION = 1

data class MemoryBackfillBoundary(
    val gaps: List<MemoryGap>,
    val backfill: MemoryBackfillState,
    val recoveryCompleted: Boolean = true
)

/** Keeps durable missing-memory facts separate from current backfill eligibility. */
object MemoryBackfillPolicy {
    fun pauseOrphanedRun(
        backfill: MemoryBackfillState,
        hasActiveRunner: Boolean
    ): MemoryBackfillState = if (
        backfill.status == MemoryBackfillStatus.RUNNING && !hasActiveRunner
    ) {
        backfill.copy(status = MemoryBackfillStatus.PAUSED)
    } else {
        backfill
    }

    fun reconcileToCurrentArchive(
        gaps: List<MemoryGap>,
        backfill: MemoryBackfillState,
        timeline: List<MemoryTimelineEntry>,
        availableSourceTurnIds: Set<String>,
        archivedSourceTurnIds: Set<String>,
        coveredSourceTurnIds: Set<String> = emptySet(),
        normalPendingSourceTurnIds: Set<String> = emptySet(),
        recordingStartsAfterSourceOrder: Long? = null,
        discoverUntrackedArchived: Boolean = false
    ): MemoryBackfillBoundary {
        val existingGapIds = gaps.flatMapTo(mutableSetOf()) { it.sourceTurnIds }
        val untracked = if (discoverUntrackedArchived) {
            timeline.asSequence()
                .filterNot { it.tombstone }
                .filter { entry ->
                    recordingStartsAfterSourceOrder == null ||
                        entry.sourceOrder > recordingStartsAfterSourceOrder
                }
                .filter { it.sourceTurnId in availableSourceTurnIds }
                .filterNot { it.sourceTurnId in existingGapIds }
                .filterNot { it.sourceTurnId in coveredSourceTurnIds }
                .filterNot { it.sourceTurnId in normalPendingSourceTurnIds }
                .sortedBy { it.sourceOrder }
                .toList()
        } else {
            emptyList()
        }
        val recoveredEntries = untracked.filter { it.sourceTurnId in archivedSourceTurnIds }
        val recoveredGaps = recoveredEntries.toContiguousGaps()
        val nextGaps = gaps + recoveredGaps
        val retainedGapIds = nextGaps.flatMapTo(mutableSetOf()) { it.sourceTurnIds }
        val pending = backfill.pendingSourceTurnIds.filter { sourceTurnId ->
            sourceTurnId in retainedGapIds &&
                sourceTurnId in availableSourceTurnIds &&
                sourceTurnId in archivedSourceTurnIds
        }
        val hasNoPendingWork = pending.isEmpty()
        val nextBackfill = backfill.copy(
            status = if (hasNoPendingWork) MemoryBackfillStatus.IDLE else backfill.status,
            pendingSourceTurnIds = pending,
            error = if (hasNoPendingWork) null else backfill.error
        )
        return MemoryBackfillBoundary(
            gaps = nextGaps,
            backfill = nextBackfill,
            recoveryCompleted = !discoverUntrackedArchived ||
                recoveredEntries.isNotEmpty() ||
                untracked.isEmpty()
        )
    }

    fun eligibleSourceTurnIds(
        activeNodes: Collection<MemoryNode>,
        gaps: Collection<MemoryGap>,
        timeline: List<MemoryTimelineEntry>,
        availableSourceTurnIds: Set<String>,
        archivedSourceTurnIds: Set<String>
    ): List<String> {
        val covered = activeNodes.flatMapTo(mutableSetOf()) { it.sourceTurnIds }
        val maxCoveredT = timeline
            .filter { it.sourceTurnId in covered }
            .maxOfOrNull { it.displayT }
        val implicitMissing = if (maxCoveredT == null) {
            emptySet()
        } else {
            timeline.asSequence()
                .filter { it.displayT <= maxCoveredT && it.sourceTurnId !in covered }
                .mapTo(mutableSetOf()) { it.sourceTurnId }
        }
        val candidates = gaps.flatMapTo(implicitMissing.toMutableSet()) { it.sourceTurnIds }
        return timeline.asSequence()
            .sortedBy { it.sourceOrder }
            .map { it.sourceTurnId }
            .distinct()
            .filter { sourceTurnId ->
                sourceTurnId in candidates &&
                    sourceTurnId in availableSourceTurnIds &&
                    sourceTurnId in archivedSourceTurnIds &&
                    sourceTurnId !in covered
            }
            .toList()
    }

    fun eligibleDisabledGapSourceTurnIds(
        gaps: Collection<MemoryGap>,
        availableSourceTurnIds: Set<String>,
        stableSourceTurnIds: Set<String>
    ): List<String> = gaps.asSequence()
        .filter { it.reason == MemoryGapReason.DISABLED }
        .flatMap { it.sourceTurnIds.asSequence() }
        .distinct()
        .filter { it in availableSourceTurnIds && it in stableSourceTurnIds }
        .toList()

    private fun List<MemoryTimelineEntry>.toContiguousGaps(): List<MemoryGap> {
        if (isEmpty()) return emptyList()
        val groups = mutableListOf<MutableList<MemoryTimelineEntry>>()
        forEach { entry ->
            val current = groups.lastOrNull()
            if (current == null || entry.displayT != current.last().displayT + 1) {
                groups += mutableListOf(entry)
            } else {
                current += entry
            }
        }
        return groups.map { entries ->
            MemoryGap(
                id = MemoryGap.newId(),
                sourceTurnIds = entries.map { it.sourceTurnId },
                startSourceOrder = entries.first().sourceOrder,
                endSourceOrder = entries.last().sourceOrder,
                reason = MemoryGapReason.LEGACY_UNKNOWN
            )
        }
    }
}
