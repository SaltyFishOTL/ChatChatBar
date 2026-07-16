package com.example.chatbar.domain.memory

import com.example.chatbar.data.local.entity.MemoryCompressionKind
import com.example.chatbar.data.local.entity.MemoryNode
import com.example.chatbar.data.local.entity.MemoryTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryNodeRegenerationPolicyTest {
    @Test
    fun `episode regeneration reads original source turns without children`() {
        val episode = node("episode", MemoryTier.EPISODE, listOf("s1", "s2"))

        val plan = MemoryNodeRegenerationPolicy.plan(episode, mapOf(episode.id to episode))

        assertTrue(plan.children.isEmpty())
        assertEquals(null, plan.compressionKind)
        assertEquals(listOf("s1", "s2"), plan.node.sourceTurnIds)
    }

    @Test
    fun `arc regeneration preserves ordered episode evidence`() {
        val children = (1..4).map { index ->
            node("e$index", MemoryTier.EPISODE, listOf("s$index"))
        }
        val arc = node(
            id = "arc",
            tier = MemoryTier.ARC,
            sourceTurnIds = children.flatMap { it.sourceTurnIds },
            childIds = children.map { it.id }
        )

        val plan = MemoryNodeRegenerationPolicy.plan(
            arc,
            (children + arc).associateBy { it.id }
        )

        assertEquals(MemoryCompressionKind.EPISODE_TO_ARC, plan.compressionKind)
        assertEquals(children.map { it.id }, plan.children.map { it.id })
    }

    @Test
    fun `era regeneration selects same-tier recompression protocol`() {
        val children = (1..3).map { index ->
            node("era$index", MemoryTier.ERA, listOf("s$index"))
        }
        val era = node(
            id = "parent",
            tier = MemoryTier.ERA,
            sourceTurnIds = children.flatMap { it.sourceTurnIds },
            childIds = children.map { it.id }
        )

        val plan = MemoryNodeRegenerationPolicy.plan(
            era,
            (children + era).associateBy { it.id }
        )

        assertEquals(MemoryCompressionKind.ERA_TO_ERA, plan.compressionKind)
    }

    @Test
    fun `regeneration rejects child coverage mismatch`() {
        val children = (1..4).map { index ->
            node("e$index", MemoryTier.EPISODE, listOf("s$index"))
        }
        val arc = node(
            id = "arc",
            tier = MemoryTier.ARC,
            sourceTurnIds = listOf("s1", "s2", "s4", "s3"),
            childIds = children.map { it.id }
        )

        assertThrows(IllegalArgumentException::class.java) {
            MemoryNodeRegenerationPolicy.plan(arc, (children + arc).associateBy { it.id })
        }
    }

    @Test
    fun `unrelated node checkpoint does not invalidate regeneration guard`() {
        val target = node("target", MemoryTier.EPISODE, listOf("s1"))
        val originalPlan = MemoryNodeRegenerationPolicy.plan(target, mapOf(target.id to target))
        val unrelatedReplacement = node("other-new", MemoryTier.EPISODE, listOf("s2"))
        val currentPlan = MemoryNodeRegenerationPolicy.plan(
            target,
            mapOf(target.id to target, unrelatedReplacement.id to unrelatedReplacement)
        )

        MemoryNodeRegenerationPolicy.requireStillCurrent(
            originalPlan = originalPlan,
            originalEvidenceHash = "target-evidence",
            currentPlan = currentPlan,
            currentEvidenceHash = "target-evidence"
        )
    }

    @Test
    fun `target replacement invalidates only that target regeneration`() {
        val original = node("target", MemoryTier.EPISODE, listOf("s1"))
        val changed = original.copy(content = "changed target")

        assertThrows(IllegalStateException::class.java) {
            MemoryNodeRegenerationPolicy.requireStillCurrent(
                originalPlan = MemoryNodeRegenerationPlan(original),
                originalEvidenceHash = "same-evidence",
                currentPlan = MemoryNodeRegenerationPlan(changed),
                currentEvidenceHash = "same-evidence"
            )
        }
    }

    @Test
    fun `source change invalidates that node regeneration`() {
        val target = node("target", MemoryTier.EPISODE, listOf("s1"))
        val plan = MemoryNodeRegenerationPlan(target)

        assertThrows(IllegalStateException::class.java) {
            MemoryNodeRegenerationPolicy.requireStillCurrent(
                originalPlan = plan,
                originalEvidenceHash = "old-evidence",
                currentPlan = plan,
                currentEvidenceHash = "new-evidence"
            )
        }
    }

    private fun node(
        id: String,
        tier: MemoryTier,
        sourceTurnIds: List<String>,
        childIds: List<String> = emptyList()
    ): MemoryNode = MemoryNode(
        id = id,
        sessionId = "session",
        tier = tier,
        sourceTurnIds = sourceTurnIds,
        childIds = childIds,
        content = "$id body",
        sourceHash = "$id source",
        coverageHash = "$id coverage"
    )
}
