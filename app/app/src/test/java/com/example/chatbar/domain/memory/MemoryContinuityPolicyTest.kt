package com.example.chatbar.domain.memory

import com.example.chatbar.data.local.entity.MemoryCoverageUnit
import com.example.chatbar.data.local.entity.MemoryGap
import com.example.chatbar.data.local.entity.MemoryGapReason
import com.example.chatbar.data.local.entity.MemoryNode
import com.example.chatbar.data.local.entity.MemoryTier
import com.example.chatbar.data.local.entity.MemoryTimelineEntry
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryContinuityPolicyTest {
    @Test
    fun recursiveEraProvesEverySourceTurnFromT0() {
        val episodes = (0L..7L).map { t -> episode("e$t", listOf(t)) }
        val arcs = listOf(
            parent("a0", MemoryTier.ARC, episodes.take(4)),
            parent("a1", MemoryTier.ARC, episodes.drop(4))
        )
        val era0 = parent("era0", MemoryTier.ERA, arcs)
        val era1 = parent(
            "era1",
            MemoryTier.ERA,
            listOf(parent("a2", MemoryTier.ARC, listOf(episode("e8", listOf(8L)))))
        )
        val recursive = parent("recursive", MemoryTier.ERA, listOf(era0, era1))
        val nodes = (descendants + recursive).associateBy { it.id }

        val result = MemoryContinuityPolicy.validateNode(recursive, nodes, timeline(0..8))

        assertTrue(result.error, result.valid)
    }

    @Test
    fun rejectsSkippedSourceTurnAndGapCrossing() {
        val skipped = episode("skipped", listOf(0L, 2L))
        val gap = MemoryGap("gap", listOf("s1"), reason = MemoryGapReason.DISABLED)
        assertFalse(MemoryContinuityPolicy.validateNode(skipped, mapOf(skipped.id to skipped), timeline(0..2)).valid)

        val crossing = episode("crossing", listOf(0L, 1L, 2L))
        assertFalse(
            MemoryContinuityPolicy.validateNode(
                crossing,
                mapOf(crossing.id to crossing),
                timeline(0..2),
                listOf(gap)
            ).valid
        )
    }

    @Test
    fun rejectsMissingChildProofWrongTierAndForgedCoverageHash() {
        val e0 = episode("e0", listOf(0L))
        val e1 = episode("e1", listOf(1L))
        val arc = parent("arc", MemoryTier.ARC, listOf(e0, e1))
        val nodes = (descendants + arc).associateBy { it.id }

        assertFalse(
            MemoryContinuityPolicy.validateNode(
                arc.copy(coverageUnits = arc.coverageUnits.dropLast(1)),
                nodes,
                timeline(0..1)
            ).valid
        )
        assertFalse(
            MemoryContinuityPolicy.validateNode(
                arc.copy(coverageHash = "forged"),
                nodes,
                timeline(0..1)
            ).valid
        )
        val wrongEra = parent("wrong-era", MemoryTier.ERA, listOf(e0, e1))
        assertFalse(
            MemoryContinuityPolicy.validateNode(
                wrongEra,
                (descendants + wrongEra).associateBy { it.id },
                timeline(0..1)
            ).valid
        )
    }

    @Test
    fun aggregateEpisodeUsesProgramOwnedStructuralCoverageWithoutPerTurnText() {
        val sourceIds = listOf("s0", "s1")
        val sourceHashes = sourceIds.associateWith { MemoryHashes.text(it) }
        val content = "两轮剧情被直接合并为一段摘要。"
        val episode = MemoryNode(
            id = "aggregate",
            sessionId = "session",
            tier = MemoryTier.EPISODE,
            sourceTurnIds = sourceIds,
            coverageUnits = emptyList(),
            content = content,
            sourceHashes = sourceHashes,
            sourceHash = MemoryHashes.text("sources"),
            coverageHash = MemoryHashes.episodeCoverage(sourceIds, sourceHashes, content)
        )

        assertTrue(
            MemoryContinuityPolicy.validateNode(
                episode,
                mapOf(episode.id to episode),
                timeline(0..1)
            ).valid
        )
        assertFalse(
            MemoryContinuityPolicy.validateNode(
                episode.copy(coverageHash = "forged"),
                mapOf(episode.id to episode),
                timeline(0..1)
            ).valid
        )
    }

    private val descendants = mutableListOf<MemoryNode>()

    private fun episode(id: String, turns: List<Long>): MemoryNode {
        val sourceIds = turns.map { "s$it" }
        val units = sourceIds.map { MemoryCoverageUnit(it, "事件 $it") }
        return MemoryNode(
            id = id,
            sessionId = "session",
            tier = MemoryTier.EPISODE,
            sourceTurnIds = sourceIds,
            coverageUnits = units,
            sourceHashes = sourceIds.associateWith { MemoryHashes.text(it) },
            sourceHash = MemoryHashes.text(sourceIds.joinToString()),
            coverageHash = MemoryHashes.coverageUnits(units)
        )
    }

    private fun parent(id: String, tier: MemoryTier, children: List<MemoryNode>): MemoryNode {
        descendants += children
        val units = children.map { MemoryCoverageUnit(it.id, "覆盖 ${it.id}") }
        return MemoryNode(
            id = id,
            sessionId = "session",
            tier = tier,
            sourceTurnIds = children.flatMap { it.sourceTurnIds },
            childIds = children.map { it.id },
            coverageUnits = units,
            compressionLevel = children.maxOf { it.compressionLevel } + 1,
            sourceHashes = children.flatMap { it.sourceHashes.entries }.associate { it.key to it.value },
            sourceHash = MemoryHashes.text(children.joinToString("\n") { "${it.id}:${it.sourceHash}" }),
            coverageHash = MemoryHashes.parentCoverage(children, units)
        ).also { descendants += it }
    }

    private fun timeline(range: IntRange) = range.map { t ->
        MemoryTimelineEntry("s$t", t.toLong(), t.toLong())
    }
}
