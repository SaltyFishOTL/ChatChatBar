package com.example.chatbar.domain.memory

import com.example.chatbar.data.local.entity.MemoryGap
import com.example.chatbar.data.local.entity.MemoryNode
import com.example.chatbar.data.local.entity.MemoryTimelineEntry

data class MemoryDisplayRange(val startT: Long, val endT: Long)

object MemoryTimelinePolicy {
    fun normalize(entries: List<MemoryTimelineEntry>): List<MemoryTimelineEntry> = entries
        .distinctBy { it.sourceTurnId }
        .sortedWith(compareBy<MemoryTimelineEntry> { it.sourceOrder }.thenBy { it.sourceTurnId })
        .mapIndexed { index, entry -> entry.copy(displayT = index.toLong()) }

    fun range(
        node: MemoryNode,
        timeline: List<MemoryTimelineEntry>
    ): MemoryDisplayRange? = range(node.sourceTurnIds, timeline)

    fun range(
        sourceTurnIds: List<String>,
        timeline: List<MemoryTimelineEntry>
    ): MemoryDisplayRange? {
        if (sourceTurnIds.isEmpty()) return null
        val byId = timeline.associateBy { it.sourceTurnId }
        val display = sourceTurnIds.map { byId[it]?.displayT ?: return null }
        return MemoryDisplayRange(display.first(), display.last())
    }

    fun isContinuous(
        sourceTurnIds: List<String>,
        timeline: List<MemoryTimelineEntry>,
        gaps: List<MemoryGap> = emptyList()
    ): Boolean {
        if (sourceTurnIds.isEmpty() || sourceTurnIds.distinct().size != sourceTurnIds.size) return false
        val byId = timeline.associateBy { it.sourceTurnId }
        val display = sourceTurnIds.map { byId[it]?.displayT ?: return false }
        if (display.zipWithNext().any { (left, right) -> right != left + 1 }) return false
        val gapIds = gaps.flatMapTo(mutableSetOf()) { it.sourceTurnIds }
        return sourceTurnIds.none { it in gapIds }
    }

    fun sortNodes(
        nodes: List<MemoryNode>,
        timeline: List<MemoryTimelineEntry>
    ): List<MemoryNode> = nodes.sortedWith(
        compareBy<MemoryNode> { range(it, timeline)?.startT ?: Long.MAX_VALUE }
            .thenBy { range(it, timeline)?.endT ?: Long.MAX_VALUE }
            .thenBy { it.createdAt }
            .thenBy { it.id }
    )

    fun displayT(sourceTurnId: String?, timeline: List<MemoryTimelineEntry>): Long? =
        sourceTurnId?.let { id -> timeline.firstOrNull { it.sourceTurnId == id }?.displayT }
}
