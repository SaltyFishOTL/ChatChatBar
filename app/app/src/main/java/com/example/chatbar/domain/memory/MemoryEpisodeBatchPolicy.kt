package com.example.chatbar.domain.memory

import com.example.chatbar.data.local.entity.MemoryGapReason
import com.example.chatbar.data.local.entity.MemoryNode
import com.example.chatbar.data.local.entity.MemorySessionState

enum class MemoryEpisodeBatchMode {
    NORMAL,
    HISTORICAL_BACKFILL
}

/** Episode source grouping. Target count is exact; partial tails are never normal work. */
object MemoryEpisodeBatchPolicy {
    fun nextBatch(
        pendingSourceTurnIds: List<String>,
        state: MemorySessionState,
        activeNodes: Collection<MemoryNode>,
        targetSourceTurns: Int,
        mode: MemoryEpisodeBatchMode
    ): List<String> {
        val target = targetSourceTurns.coerceIn(1, 6)
        val byId = state.timeline.associateBy { it.sourceTurnId }
        val sorted = pendingSourceTurnIds.distinct().sortedBy { byId[it]?.sourceOrder ?: Long.MAX_VALUE }
        if (sorted.isEmpty()) return emptyList()
        val segment = mutableListOf<String>()
        for (sourceId in sorted) {
            val entry = byId[sourceId] ?: break
            if (entry.tombstone) break
            val previous = segment.lastOrNull()
            if (previous != null && byId[previous]?.displayT?.plus(1) != entry.displayT) break
            segment += sourceId
        }
        if (segment.size >= target) return segment.take(target)
        if (mode != MemoryEpisodeBatchMode.HISTORICAL_BACKFILL || segment.size != 1) {
            return emptyList()
        }
        val sourceId = segment.single()
        val entry = byId[sourceId] ?: return emptyList()
        val explicitGap = state.gaps.firstOrNull { sourceId in it.sourceTurnIds }
        if (explicitGap?.reason in setOf(
                MemoryGapReason.DISABLED,
                MemoryGapReason.DELETED_SOURCE,
                MemoryGapReason.DECLINED_BACKFILL
            )
        ) {
            return emptyList()
        }
        val covered = activeNodes.flatMapTo(mutableSetOf()) { it.sourceTurnIds }
        val left = state.timeline.firstOrNull { it.displayT == entry.displayT - 1 }
        val right = state.timeline.firstOrNull { it.displayT == entry.displayT + 1 }
        return if (
            left != null && right != null &&
            !left.tombstone && !right.tombstone &&
            left.sourceTurnId in covered && right.sourceTurnId in covered
        ) {
            listOf(sourceId)
        } else {
            emptyList()
        }
    }

    fun completeBatchCount(sourceTurnCount: Int, targetSourceTurns: Int): Int =
        sourceTurnCount.coerceAtLeast(0) / targetSourceTurns.coerceIn(1, 6)

    fun trailingWaitCount(sourceTurnCount: Int, targetSourceTurns: Int): Int =
        sourceTurnCount.coerceAtLeast(0) % targetSourceTurns.coerceIn(1, 6)
}
