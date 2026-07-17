package com.example.chatbar.domain.memory

import com.example.chatbar.data.local.entity.MemoryCoverageUnit
import com.example.chatbar.data.local.entity.MemoryGap
import com.example.chatbar.data.local.entity.MemoryGapReason
import com.example.chatbar.data.local.entity.MemoryNode
import com.example.chatbar.data.local.entity.MemorySourceRepairState
import com.example.chatbar.data.local.entity.MemorySourceRepairStatus
import com.example.chatbar.data.local.entity.MemoryTier
import com.example.chatbar.data.local.entity.MemoryTimelineEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemorySourceRepairPolicyTest {
    private val timeline = (0L..4L).map { index ->
        MemoryTimelineEntry("s$index", index, index)
    }

    @Test
    fun deletedInteriorSourceSplitsEpisodeIntoContinuousRuns() {
        val runs = MemorySourceRepairPolicy.availableSourceRuns(
            sourceTurnIds = listOf("s0", "s1", "s2"),
            availableSourceTurnIds = setOf("s0", "s2"),
            timeline = timeline,
            gaps = listOf(
                MemoryGap(
                    id = "gap",
                    sourceTurnIds = listOf("s1"),
                    reason = MemoryGapReason.DELETED_SOURCE
                )
            )
        )

        assertEquals(listOf(listOf("s0"), listOf("s2")), runs)
    }

    @Test
    fun parentRebuildRequiresOneReplacementPerChildAndNoGap() {
        val left = episode("left", listOf("s0"))
        val right = episode("right", listOf("s1"))
        val parent = parent("arc", listOf(left, right))

        assertEquals(
            listOf(left, right),
            MemorySourceRepairPolicy.rebuildableChildren(
                parent,
                listOf(listOf(left), listOf(right)),
                timeline,
                emptyList()
            )
        )
        assertEquals(
            null,
            MemorySourceRepairPolicy.rebuildableChildren(
                parent,
                listOf(listOf(left), emptyList()),
                timeline,
                emptyList()
            )
        )
    }

    @Test
    fun safeFrontierKeepsOnlyCurrentDescendantsOfStaleRoot() {
        val oldHash = MemoryHashes.text("old")
        val newHash = MemoryHashes.text("new")
        val currentHash = MemoryHashes.text("current")
        val stale = episode("stale", listOf("s0"), mapOf("s0" to oldHash))
        val current = episode("current", listOf("s1"), mapOf("s1" to currentHash))
        val root = parent("root", listOf(stale, current))
        val nodes = listOf(root, stale, current).associateBy { it.id }

        val frontier = MemorySourceRepairPolicy.safeFrontier(
            activeNodes = listOf(root),
            nodesById = nodes,
            staleRootIds = setOf(root.id),
            currentSourceHashes = mapOf("s0" to newHash, "s1" to currentHash),
            timeline = timeline,
            maxChars = 1000
        )

        assertEquals(listOf(current.id), frontier.nodes.map { it.id })
        assertTrue(frontier.omittedRootIds.isEmpty())
    }

    @Test
    fun safeFrontierOmitsWholeRootWhenExpandedChildrenExceedBudget() {
        val currentHash = MemoryHashes.text("current")
        val staleHash = MemoryHashes.text("stale")
        val child = episode("child", listOf("s0"), mapOf("s0" to currentHash), "long body")
        val staleSibling = episode("stale", listOf("s1"), mapOf("s1" to staleHash))
        val root = parent("root", listOf(child, staleSibling))

        val frontier = MemorySourceRepairPolicy.safeFrontier(
            activeNodes = listOf(root),
            nodesById = listOf(root, child, staleSibling).associateBy { it.id },
            staleRootIds = setOf(root.id),
            currentSourceHashes = mapOf("s0" to currentHash, "s1" to MemoryHashes.text("changed")),
            timeline = timeline,
            maxChars = 2
        )

        assertTrue(frontier.nodes.isEmpty())
        assertEquals(setOf(root.id), frontier.omittedRootIds)
    }

    @Test
    fun orphanedRunningRepairBecomesPaused() {
        val paused = MemorySourceRepairPolicy.pauseOrphanedRun(
            MemorySourceRepairState(status = MemorySourceRepairStatus.RUNNING),
            hasActiveRunner = false
        )

        assertEquals(MemorySourceRepairStatus.PAUSED, paused.status)
        assertEquals(
            MemorySourceRepairStatus.RUNNING,
            MemorySourceRepairPolicy.pauseOrphanedRun(
                MemorySourceRepairState(status = MemorySourceRepairStatus.RUNNING),
                hasActiveRunner = true
            ).status
        )
    }

    @Test
    fun pausedRepairKeepsCommittedProgressWhenResumed() {
        val resumed = MemorySourceRepairPolicy.startOrResume(
            repair = MemorySourceRepairState(
                status = MemorySourceRepairStatus.PAUSED,
                pendingRootNodeIds = listOf("old-pending"),
                completedRootCount = 2,
                totalRootCount = 3,
                repairHead = true
            ),
            pendingRootNodeIds = listOf("remaining", "newly-stale"),
            repairHead = false
        )

        assertEquals(MemorySourceRepairStatus.RUNNING, resumed.status)
        assertEquals(listOf("remaining", "newly-stale"), resumed.pendingRootNodeIds)
        assertEquals(2, resumed.completedRootCount)
        assertEquals(4, resumed.totalRootCount)
        assertTrue(resumed.repairHead)
    }

    private fun episode(
        id: String,
        sourceIds: List<String>,
        hashes: Map<String, String> = sourceIds.associateWith { MemoryHashes.text(it) },
        body: String = id
    ): MemoryNode = MemoryNode(
        id = id,
        sessionId = "session",
        tier = MemoryTier.EPISODE,
        sourceTurnIds = sourceIds,
        content = body,
        sourceHashes = hashes,
        sourceHash = MemoryHashes.sourceIds(sourceIds, sourceIds.map { hashes.getValue(it) }),
        coverageHash = MemoryHashes.episodeCoverage(sourceIds, hashes, body)
    )

    private fun parent(id: String, children: List<MemoryNode>): MemoryNode {
        val units = children.map { MemoryCoverageUnit(it.id, "coverage ${it.id}") }
        return MemoryNode(
            id = id,
            sessionId = "session",
            tier = MemoryTier.ARC,
            sourceTurnIds = children.flatMap { it.sourceTurnIds },
            childIds = children.map { it.id },
            coverageUnits = units,
            content = id,
            sourceHashes = children.flatMap { it.sourceHashes.entries }.associate { it.key to it.value },
            sourceHash = MemoryHashes.text(children.joinToString("\n") { "${it.id}:${it.sourceHash}" }),
            coverageHash = MemoryHashes.parentCoverage(children, units)
        )
    }
}
