package com.example.chatbar.domain.memory

import com.example.chatbar.data.local.entity.MemoryAuthor
import com.example.chatbar.data.local.entity.MemoryCoverageUnit
import com.example.chatbar.data.local.entity.MemoryHead
import com.example.chatbar.data.local.entity.MemoryNode
import com.example.chatbar.data.local.entity.MemorySourceTurnRef
import com.example.chatbar.data.local.entity.MemoryTier
import com.example.chatbar.data.local.entity.MemoryTimelineEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryDeletionProjectionPolicyTest {
    @Test
    fun tombstoneIsRemovedAndLaterTurnsAreRenumberedContinuously() {
        val timeline = MemoryTimelinePolicy.normalize(
            listOf(
                entry("s1", 1),
                entry("s2", 2).copy(tombstone = true),
                entry("s3", 3)
            )
        )

        assertEquals(listOf("s1", "s3"), timeline.map { it.sourceTurnId })
        assertEquals(listOf(0L, 1L), timeline.map { it.displayT })
    }

    @Test
    fun beginningEndAndConsecutiveDeletesAlwaysProduceDenseDisplayT() {
        val source = (1L..6L).map { order -> entry("s$order", order) }
        val beginning = MemoryTimelinePolicy.normalize(
            source.map { it.copy(tombstone = it.sourceTurnId == "s1") }
        )
        val end = MemoryTimelinePolicy.normalize(
            source.map { it.copy(tombstone = it.sourceTurnId == "s6") }
        )
        val consecutive = MemoryTimelinePolicy.normalize(
            source.map { it.copy(tombstone = it.sourceTurnId in setOf("s2", "s3", "s4")) }
        )

        assertEquals((0L..4L).toList(), beginning.map { it.displayT })
        assertEquals((0L..4L).toList(), end.map { it.displayT })
        assertEquals(listOf("s1", "s5", "s6"), consecutive.map { it.sourceTurnId })
        assertEquals(listOf(0L, 1L, 2L), consecutive.map { it.displayT })
    }

    @Test
    fun deletingMiddleTurnCompactsTimelineAndProjectsOnlyAffectedEpisode() {
        val timeline = MemoryTimelinePolicy.normalize(
            listOf(entry("s1", 1), entry("s3", 3))
        )
        val first = episode("e1", listOf("s1", "s2"), "原正文A")
        val second = episode("e2", listOf("s3"), "原正文B")

        val result = project(
            active = mapOf(MemoryTier.EPISODE to listOf(first.id, second.id)),
            nodes = listOf(first, second),
            deleted = setOf("s2"),
            timeline = timeline
        )

        val active = result.activeNodeIdsByTier.getValue(MemoryTier.EPISODE)
        val projectedFirst = result.nodesById.getValue(active.first())
        assertEquals(listOf("s1"), projectedFirst.sourceTurnIds)
        assertEquals("原正文A", projectedFirst.body)
        assertEquals("e2", active.last())
        assertSame(second, result.nodesById.getValue("e2"))
        assertEquals(1L, MemoryTimelinePolicy.displayT("s3", timeline))
    }

    @Test
    fun emptyEpisodeDeletesParentsOnlyWhenTheirLastChildDisappears() {
        val deletedEpisode = episode("e1", listOf("s1"), "删")
        val keptEpisode = episode("e2", listOf("s2"), "留")
        val arc = parent("a1", MemoryTier.ARC, listOf(deletedEpisode, keptEpisode), "Arc正文")
        val era = parent("r1", MemoryTier.ERA, listOf(arc), "Era正文")
        val timeline = MemoryTimelinePolicy.normalize(listOf(entry("s2", 2)))

        val partial = project(
            active = mapOf(MemoryTier.ERA to listOf(era.id)),
            nodes = listOf(deletedEpisode, keptEpisode, arc, era),
            deleted = setOf("s1"),
            timeline = timeline
        )
        val projectedEra = partial.nodesById.getValue(
            partial.activeNodeIdsByTier.getValue(MemoryTier.ERA).single()
        )
        val projectedArc = partial.nodesById.getValue(projectedEra.childIds.single())
        assertEquals("Era正文", projectedEra.body)
        assertEquals("Arc正文", projectedArc.body)
        assertEquals(listOf("e2"), projectedArc.childIds)

        val empty = project(
            active = mapOf(MemoryTier.ERA to listOf(era.id)),
            nodes = listOf(deletedEpisode, keptEpisode, arc, era),
            deleted = setOf("s1", "s2"),
            timeline = emptyList()
        )
        assertTrue(empty.activeNodeIdsByTier.getValue(MemoryTier.ERA).isEmpty())
    }

    @Test
    fun recursiveEraKeepsItsBodyAndSingletonChildAfterOtherBranchDisappears() {
        val deletedEpisode = episode("e1", listOf("s1"), "删除分支")
        val keptEpisode = episode("e2", listOf("s2"), "保留分支")
        val deletedArc = parent("a1", MemoryTier.ARC, listOf(deletedEpisode), "Arc删")
        val keptArc = parent("a2", MemoryTier.ARC, listOf(keptEpisode), "Arc留")
        val deletedEra = parent("r1", MemoryTier.ERA, listOf(deletedArc), "Era删")
        val keptEra = parent("r2", MemoryTier.ERA, listOf(keptArc), "Era留")
        val recursiveEra = parent(
            "r3",
            MemoryTier.ERA,
            listOf(deletedEra, keptEra),
            "递归Era正文"
        )

        val result = project(
            active = mapOf(MemoryTier.ERA to listOf(recursiveEra.id)),
            nodes = listOf(
                deletedEpisode,
                keptEpisode,
                deletedArc,
                keptArc,
                deletedEra,
                keptEra,
                recursiveEra
            ),
            deleted = setOf("s1"),
            timeline = MemoryTimelinePolicy.normalize(listOf(entry("s2", 2)))
        )
        val projected = result.nodesById.getValue(
            result.activeNodeIdsByTier.getValue(MemoryTier.ERA).single()
        )

        assertEquals("递归Era正文", projected.body)
        assertEquals(listOf("r2"), projected.childIds)
        assertSame(keptEra, result.nodesById.getValue("r2"))
    }

    @Test
    fun unaffectedTreeKeepsIdentityAndCreatesNothing() {
        val episode = episode("e1", listOf("s1"), "正文")
        val timeline = listOf(entry("s1", 1))
        val result = project(
            active = mapOf(MemoryTier.EPISODE to listOf(episode.id)),
            nodes = listOf(episode),
            deleted = setOf("other"),
            timeline = timeline
        )

        assertFalse(result.changed)
        assertTrue(result.createdNodes.isEmpty())
        assertSame(episode, result.nodesById.getValue(episode.id))
    }

    @Test
    fun deletionProjectionDoesNotBlessAnotherEditedSource() {
        val episode = episode("e1", listOf("s1", "s2"), "旧正文").copy(
            sourceFingerprints = mapOf("s1" to "old-s1", "s2" to "hash-s2")
        )
        val timeline = MemoryTimelinePolicy.normalize(listOf(entry("s1", 1)))
        val result = MemoryDeletionProjectionPolicy.project(
            activeNodeIdsByTier = mapOf(MemoryTier.EPISODE to listOf(episode.id)),
            nodesById = mapOf(episode.id to episode),
            deletedSourceTurnIds = setOf("s2"),
            sourceRefsById = mapOf("s1" to sourceRef("s1").copy(sourceFingerprint = "new-s1")),
            timeline = timeline,
            newNodeId = { "new" }
        )

        val projected = result.nodesById.getValue("new")
        assertEquals("old-s1", projected.sourceFingerprints.getValue("s1"))
        assertEquals("旧正文", projected.body)
    }

    @Test
    fun deletedHeadEvidenceClearsHeadWhileUnrelatedDeleteOnlyRenumbersThroughT() {
        val timeline = MemoryTimelinePolicy.normalize(listOf(entry("s1", 1), entry("s3", 3)))
        val head = MemoryHead(
            throughSourceTurnId = "s3",
            throughT = 2,
            location = "旧地点",
            sourceHashes = mapOf("s2" to "hash-s2", "s3" to "hash-s3"),
            version = 4
        )

        val cleared = MemoryDeletionProjectionPolicy.projectHead(head, setOf("s2"), timeline)
        assertTrue(cleared.render().isBlank())
        assertEquals(5L, cleared.version)

        val retained = MemoryDeletionProjectionPolicy.projectHead(head, setOf("other"), timeline)
        assertEquals("旧地点", retained.location)
        assertEquals(1L, retained.throughT)
        assertEquals(4L, retained.version)
    }

    private fun project(
        active: Map<MemoryTier, List<String>>,
        nodes: List<MemoryNode>,
        deleted: Set<String>,
        timeline: List<MemoryTimelineEntry>
    ): MemoryDeletionProjection {
        var nextId = 0
        val allSourceIds = nodes.flatMap { it.sourceTurnIds }.filterNot(deleted::contains).toSet()
        return MemoryDeletionProjectionPolicy.project(
            activeNodeIdsByTier = listOf(MemoryTier.EPISODE, MemoryTier.ARC, MemoryTier.ERA)
                .associateWith { active[it].orEmpty() },
            nodesById = nodes.associateBy { it.id },
            deletedSourceTurnIds = deleted,
            sourceRefsById = allSourceIds.associateWith(::sourceRef),
            timeline = timeline,
            newNodeId = { "new-${nextId++}" }
        )
    }

    private fun entry(id: String, order: Long) = MemoryTimelineEntry(id, order, order)

    private fun sourceRef(id: String) = MemorySourceTurnRef(
        sourceTurnId = id,
        sourceOrder = id.removePrefix("s").toLong(),
        sourceHash = "hash-$id",
        sourceFingerprint = "hash-$id"
    )

    private fun episode(id: String, sources: List<String>, body: String): MemoryNode {
        val hashes = sources.associateWith { "hash-$it" }
        return MemoryNode(
            id = id,
            sessionId = "session",
            tier = MemoryTier.EPISODE,
            sourceTurnIds = sources,
            content = body,
            sourceHashes = hashes,
            sourceFingerprints = hashes,
            sourceHash = MemoryHashes.sourceIds(sources, sources.map(hashes::getValue)),
            coverageHash = MemoryHashes.episodeCoverage(sources, hashes, body),
            author = MemoryAuthor.AI
        )
    }

    private fun parent(
        id: String,
        tier: MemoryTier,
        children: List<MemoryNode>,
        body: String
    ): MemoryNode {
        val units = children.map { MemoryCoverageUnit(it.id, it.coverageHash) }
        return MemoryNode(
            id = id,
            sessionId = "session",
            tier = tier,
            sourceTurnIds = children.flatMap { it.sourceTurnIds },
            childIds = children.map { it.id },
            coverageUnits = units,
            content = body,
            sourceHashes = children.flatMap { it.sourceHashes.entries }.associate { it.toPair() },
            sourceFingerprints = children
                .flatMap { it.sourceFingerprints.entries }
                .associate { it.toPair() },
            sourceHash = MemoryHashes.text(children.joinToString("\n") { "${it.id}:${it.sourceHash}" }),
            coverageHash = MemoryHashes.parentCoverage(children, units),
            author = MemoryAuthor.AI
        )
    }
}
