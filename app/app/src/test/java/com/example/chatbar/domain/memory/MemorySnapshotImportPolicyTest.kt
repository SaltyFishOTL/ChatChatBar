package com.example.chatbar.domain.memory

import com.example.chatbar.data.local.entity.MemoryCoverageUnit
import com.example.chatbar.data.local.entity.MemoryNode
import com.example.chatbar.data.local.entity.MemorySessionSnapshot
import com.example.chatbar.data.local.entity.MemorySnapshot
import com.example.chatbar.data.local.entity.MemoryTier
import com.example.chatbar.data.local.entity.MemoryTimelineEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemorySnapshotImportPolicyTest {
    @Test
    fun crossSessionImportRekeysWholeTreeAndRecomputesCoverageHashes() {
        val episode0 = episode("episode-0", "s0")
        val episode1 = episode("episode-1", "s1")
        val units = listOf(
            MemoryCoverageUnit(episode0.id, "覆盖T0"),
            MemoryCoverageUnit(episode1.id, "覆盖T1")
        )
        val arc = MemoryNode(
            id = "arc",
            sessionId = "source",
            tier = MemoryTier.ARC,
            sourceTurnIds = listOf("s0", "s1"),
            childIds = listOf(episode0.id, episode1.id),
            coverageUnits = units,
            compressionLevel = 1,
            sourceHashes = episode0.sourceHashes + episode1.sourceHashes,
            sourceHash = MemoryHashes.text(listOf(episode0, episode1).joinToString("\n") { "${it.id}:${it.sourceHash}" }),
            coverageHash = MemoryHashes.parentCoverage(listOf(episode0, episode1), units)
        )
        val timeline = listOf(
            MemoryTimelineEntry("s0", 0, 0),
            MemoryTimelineEntry("s1", 1, 1)
        )
        val snapshot = MemorySnapshot(
            state = MemorySessionSnapshot(
                arcNodeIds = listOf(arc.id),
                timeline = timeline,
                staleSourcesByNodeId = mapOf(arc.id to listOf("s0"))
            ),
            nodes = listOf(arc, episode0, episode1)
        )

        val rebound = MemorySnapshotImportPolicy.rebind(snapshot, "target")
        val nodes = rebound.nodes.associateBy { it.id }
        val reboundArcId = rebound.state!!.arcNodeIds.single()
        val reboundArc = nodes.getValue(reboundArcId)

        assertNotEquals(arc.id, reboundArc.id)
        assertEquals(setOf("target"), rebound.nodes.map { it.sessionId }.toSet())
        assertEquals(reboundArc.childIds, reboundArc.coverageUnits.map { it.sourceId })
        assertEquals(setOf(reboundArc.id), rebound.state!!.staleSourcesByNodeId.keys)
        assertTrue(MemoryContinuityPolicy.validateNode(reboundArc, nodes, timeline).valid)
    }

    @Test
    fun crossSessionImportPreservesAggregateEpisodeStructuralCoverage() {
        val sourceIds = listOf("s0", "s1")
        val hashes = sourceIds.associateWith { MemoryHashes.text(it) }
        val content = "两轮剧情直接形成一段Episode。"
        val episode = MemoryNode(
            id = "aggregate",
            sessionId = "source",
            tier = MemoryTier.EPISODE,
            sourceTurnIds = sourceIds,
            content = content,
            sourceHashes = hashes,
            sourceHash = MemoryHashes.text("source"),
            coverageHash = MemoryHashes.episodeCoverage(sourceIds, hashes, content)
        )
        val timeline = listOf(
            MemoryTimelineEntry("s0", 0, 0),
            MemoryTimelineEntry("s1", 1, 1)
        )
        val rebound = MemorySnapshotImportPolicy.rebind(
            MemorySnapshot(
                state = MemorySessionSnapshot(
                    episodeNodeIds = listOf(episode.id),
                    timeline = timeline
                ),
                nodes = listOf(episode)
            ),
            "target"
        )
        val reboundEpisode = rebound.nodes.single()

        assertTrue(reboundEpisode.coverageUnits.isEmpty())
        assertEquals(content, reboundEpisode.body)
        assertTrue(
            MemoryContinuityPolicy.validateNode(
                reboundEpisode,
                mapOf(reboundEpisode.id to reboundEpisode),
                timeline
            ).valid
        )
    }

    private fun episode(id: String, sourceId: String): MemoryNode {
        val units = listOf(MemoryCoverageUnit(sourceId, "覆盖$sourceId"))
        return MemoryNode(
            id = id,
            sessionId = "source",
            tier = MemoryTier.EPISODE,
            sourceTurnIds = listOf(sourceId),
            coverageUnits = units,
            sourceHashes = mapOf(sourceId to MemoryHashes.text(sourceId)),
            sourceHash = MemoryHashes.text("source:$sourceId"),
            coverageHash = MemoryHashes.coverageUnits(units)
        )
    }
}
