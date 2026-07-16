package com.example.chatbar.domain.memory

import com.example.chatbar.data.local.entity.MemoryNode
import com.example.chatbar.data.local.entity.MemorySessionState
import com.example.chatbar.data.local.entity.MemoryTimelineEntry

/** Active page IDs are persisted in ascending derived-T order. */
object MemoryPageOrderPolicy {
    fun normalize(
        state: MemorySessionState,
        nodesById: Map<String, MemoryNode>
    ): MemorySessionState {
        var normalized = state
        listOf(state.episodePage, state.arcPage, state.eraPage).forEach { page ->
            val orderedIds = orderedNodeIdsOrNull(
                nodeIds = page.activeNodeIds,
                nodesById = nodesById,
                timeline = state.timeline
            ) ?: return@forEach
            if (orderedIds != page.activeNodeIds) {
                normalized = normalized.replacePage(page.copy(activeNodeIds = orderedIds))
            }
        }
        return normalized
    }

    /** Returns null when persistence cannot be safely reordered from program-owned T metadata. */
    fun orderedNodeIdsOrNull(
        nodeIds: List<String>,
        nodesById: Map<String, MemoryNode>,
        timeline: List<MemoryTimelineEntry>
    ): List<String>? {
        val nodes = nodeIds.mapNotNull(nodesById::get)
        if (nodes.size != nodeIds.size) return null
        if (nodes.any { MemoryTimelinePolicy.range(it, timeline) == null }) return null
        return MemoryTimelinePolicy.sortNodes(nodes, timeline).map { it.id }
    }
}
