package com.example.chatbar.domain.memory

import com.example.chatbar.data.local.entity.MemoryGap
import com.example.chatbar.data.local.entity.MemoryNode
import com.example.chatbar.data.local.entity.MemorySourceRepairState
import com.example.chatbar.data.local.entity.MemorySourceRepairStatus
import com.example.chatbar.data.local.entity.MemoryTier
import com.example.chatbar.data.local.entity.MemoryTimelineEntry

data class MemorySafeFrontier(
    val nodes: List<MemoryNode>,
    val omittedRootIds: Set<String> = emptySet()
)

/** 来源修改/删除后的纯规划规则；不调用模型，不写持久层。 */
object MemorySourceRepairPolicy {
    fun startOrResume(
        repair: MemorySourceRepairState,
        pendingRootNodeIds: List<String>,
        repairHead: Boolean
    ): MemorySourceRepairState {
        val completedRoots = when (repair.status) {
            MemorySourceRepairStatus.PAUSED,
            MemorySourceRepairStatus.ERROR -> repair.completedRootCount
            MemorySourceRepairStatus.IDLE,
            MemorySourceRepairStatus.RUNNING -> 0
        }
        return MemorySourceRepairState(
            status = MemorySourceRepairStatus.RUNNING,
            pendingRootNodeIds = pendingRootNodeIds,
            completedRootCount = completedRoots,
            totalRootCount = completedRoots + pendingRootNodeIds.size,
            repairHead = repairHead || repair.repairHead
        )
    }

    fun pauseOrphanedRun(
        repair: MemorySourceRepairState,
        hasActiveRunner: Boolean
    ): MemorySourceRepairState = if (
        repair.status == MemorySourceRepairStatus.RUNNING && !hasActiveRunner
    ) {
        repair.copy(
            status = MemorySourceRepairStatus.PAUSED,
            updatedAt = System.currentTimeMillis()
        )
    } else {
        repair
    }

    fun availableSourceRuns(
        sourceTurnIds: List<String>,
        availableSourceTurnIds: Set<String>,
        timeline: List<MemoryTimelineEntry>,
        gaps: List<MemoryGap>
    ): List<List<String>> {
        val gapIds = gaps.flatMapTo(mutableSetOf()) { it.sourceTurnIds }
        val byId = timeline.associateBy { it.sourceTurnId }
        val runs = mutableListOf<MutableList<String>>()
        sourceTurnIds.forEach { sourceId ->
            if (sourceId !in availableSourceTurnIds || sourceId in gapIds) return@forEach
            val current = runs.lastOrNull()
            val previous = current?.lastOrNull()
            val adjacent = previous != null &&
                byId[previous]?.displayT?.plus(1) == byId[sourceId]?.displayT
            if (current == null || !adjacent) {
                runs += mutableListOf(sourceId)
            } else {
                current += sourceId
            }
        }
        return runs
    }

    /** 仅当每个原child仍一对一且整段连续时，才保留并重生成父层。 */
    fun rebuildableChildren(
        originalParent: MemoryNode,
        repairedByChild: List<List<MemoryNode>>,
        timeline: List<MemoryTimelineEntry>,
        gaps: List<MemoryGap>
    ): List<MemoryNode>? {
        if (repairedByChild.size != originalParent.childIds.size) return null
        if (repairedByChild.any { it.size != 1 }) return null
        val children = repairedByChild.flatten()
        val expectedTier = when (originalParent.tier) {
            MemoryTier.ARC -> MemoryTier.EPISODE
            MemoryTier.ERA -> children.firstOrNull()?.tier ?: return null
            MemoryTier.EPISODE,
            MemoryTier.LEGACY_REFERENCE -> return null
        }
        if (children.any { it.tier != expectedTier }) return null
        if (originalParent.tier == MemoryTier.ERA &&
            expectedTier != MemoryTier.ARC && expectedTier != MemoryTier.ERA
        ) {
            return null
        }
        val sourceIds = children.flatMap { it.sourceTurnIds }
        if (!MemoryTimelinePolicy.isContinuous(sourceIds, timeline, gaps)) return null
        return children
    }

    /**
     * Prompt安全frontier：stale根不发送；可证明未变化的后代替代它。
     * 展开后若超过预算，则整棵stale根暂时省略，避免半段无声截断。
     */
    fun safeFrontier(
        activeNodes: List<MemoryNode>,
        nodesById: Map<String, MemoryNode>,
        staleRootIds: Set<String>,
        currentSourceHashes: Map<String, String>,
        timeline: List<MemoryTimelineEntry>,
        maxChars: Int
    ): MemorySafeFrontier {
        val retained = activeNodes.filterNot { it.id in staleRootIds }.toMutableList()
        var usedChars = retained.sumOf { it.body.length }
        val omitted = mutableSetOf<String>()

        fun isCurrent(node: MemoryNode): Boolean =
            node.sourceTurnIds.isNotEmpty() &&
                node.sourceHashes.keys == node.sourceTurnIds.toSet() &&
                node.sourceHashes.all { (sourceId, storedHash) ->
                    currentSourceHashes[sourceId] == storedHash
                }

        fun safeDescendants(node: MemoryNode, path: MutableSet<String>): List<MemoryNode> {
            if (!path.add(node.id)) return emptyList()
            if (isCurrent(node)) {
                path.remove(node.id)
                return listOf(node)
            }
            if (node.childIds.isEmpty()) {
                path.remove(node.id)
                return emptyList()
            }
            val descendants = node.childIds.flatMap { childId ->
                nodesById[childId]?.let { safeDescendants(it, path) }.orEmpty()
            }
            path.remove(node.id)
            return descendants
        }

        MemoryTimelinePolicy.sortNodes(
            activeNodes.filter { it.id in staleRootIds },
            timeline
        ).forEach { root ->
            val replacements = safeDescendants(root, mutableSetOf())
            val replacementChars = replacements.sumOf { it.body.length }
            if (replacements.isNotEmpty() && usedChars + replacementChars <= maxChars) {
                retained += replacements
                usedChars += replacementChars
            } else {
                omitted += root.id
            }
        }
        return MemorySafeFrontier(
            nodes = MemoryTimelinePolicy.sortNodes(retained.distinctBy { it.id }, timeline),
            omittedRootIds = omitted
        )
    }
}
