package com.example.chatbar.domain.memory

import com.example.chatbar.data.local.entity.MemoryGap
import com.example.chatbar.data.local.entity.MemoryGapReason
import com.example.chatbar.data.local.entity.MemoryNode
import com.example.chatbar.data.local.entity.MemoryTier
import com.example.chatbar.data.local.entity.MemoryTimelineEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryCompressionPolicyTest {
    @Test
    fun `staged newest episode permits consuming all four active episodes`() {
        val nodes = (0..3).map { node("e$it", MemoryTier.EPISODE, it) }
        val timeline = timeline(0..4)

        val candidate = MemoryCompressionPolicy.oldestContinuousLowerCandidate(
            nodes = nodes,
            expectedTier = MemoryTier.EPISODE,
            timeline = timeline,
            gaps = emptyList(),
            newestRetainedOutsideCandidates = true
        )

        assertEquals(4, candidate?.candidates?.size)
        assertEquals(4, candidate?.maxConsume)
    }

    @Test
    fun `without staged same tier newest lower node remains active`() {
        val nodes = (0..3).map { node("e$it", MemoryTier.EPISODE, it) }

        val candidate = MemoryCompressionPolicy.oldestContinuousLowerCandidate(
            nodes = nodes,
            expectedTier = MemoryTier.EPISODE,
            timeline = timeline(0..3),
            gaps = emptyList()
        )

        assertNull(candidate)
    }

    @Test
    fun `node crossing deleted source gap is never compression candidate`() {
        val nodes = (0..4).map { node("e$it", MemoryTier.EPISODE, it) }
        val gap = MemoryGap(
            id = "gap",
            sourceTurnIds = listOf("s2"),
            reason = MemoryGapReason.DELETED_SOURCE
        )

        val candidate = MemoryCompressionPolicy.oldestContinuousLowerCandidate(
            nodes = nodes,
            expectedTier = MemoryTier.EPISODE,
            timeline = timeline(0..4),
            gaps = listOf(gap)
        )

        assertNull(candidate)
    }

    @Test
    fun `era forced prefix consumes longest equally fresh prefix up to ten`() {
        val candidate = MemoryCompressionCandidate(
            candidates = (0..11).map { node("era$it", MemoryTier.ERA, it) },
            minConsume = 3,
            maxConsume = 10
        )

        assertEquals(10, MemoryCompressionPolicy.forcedEraPrefix(candidate).size)
    }

    @Test
    fun `era forced prefix stops before more worn node`() {
        val candidate = MemoryCompressionCandidate(
            candidates = (0..4).map { t ->
                node("era$t", MemoryTier.ERA, t, level = if (t < 4) 0 else 1)
            },
            minConsume = 3,
            maxConsume = 5
        )

        assertEquals(4, MemoryCompressionPolicy.forcedEraPrefix(candidate).size)
    }

    @Test
    fun lowerTierSendsAtMostTwentyFiveConsumesFourToTwentyAndKeepsLatest() {
        val episodes = (0..25).map { node("e$it", MemoryTier.EPISODE, it) }

        val candidate = MemoryCompressionPolicy.oldestContinuousLowerCandidate(
            episodes,
            MemoryTier.EPISODE,
            timeline(0..25),
            emptyList()
        )!!

        assertEquals(25, candidate.candidates.size)
        assertEquals(4, candidate.minConsume)
        assertEquals(20, candidate.maxConsume)
        assertFalse("e25" in candidate.candidates.map { it.id })
        assertTrue(
            MemoryCompressionPolicy.validateConsumedPrefix(
                candidate,
                candidate.candidates.take(4).map { it.id }
            ).valid
        )
        assertFalse(
            MemoryCompressionPolicy.validateConsumedPrefix(
                candidate,
                listOf("e0", "e1", "e3", "e4")
            ).valid
        )
    }

    @Test
    fun fourLowerNodesCannotCompressBecauseNewestMustRemain() {
        val four = (0..3).map { node("e$it", MemoryTier.EPISODE, it) }
        val five = (0..4).map { node("e$it", MemoryTier.EPISODE, it) }

        assertNull(
            MemoryCompressionPolicy.oldestContinuousLowerCandidate(
                four,
                MemoryTier.EPISODE,
                timeline(0..4),
                emptyList()
            )
        )
        assertEquals(
            4,
            MemoryCompressionPolicy.oldestContinuousLowerCandidate(
                five,
                MemoryTier.EPISODE,
                timeline(0..4),
                emptyList()
            )!!.maxConsume
        )
    }

    @Test
    fun eraPrefersOldestFreshWindowInsteadOfRecompressingWornPrefix() {
        val eras = listOf(
            node("worn", MemoryTier.ERA, 0, level = 2),
            node("fresh-1", MemoryTier.ERA, 1),
            node("fresh-2", MemoryTier.ERA, 2),
            node("fresh-3", MemoryTier.ERA, 3),
            node("fresh-4", MemoryTier.ERA, 4)
        )

        val candidate = MemoryCompressionPolicy.eraCandidate(
            eras,
            timeline(0..4),
            emptyList()
        )!!

        assertEquals("fresh-1", candidate.candidates.first().id)
        assertEquals(3, candidate.minConsume)
        assertEquals(4, candidate.maxConsume)
    }

    @Test
    fun gapSplitsCompressionCandidates() {
        val episodes = (0..8).map { node("e$it", MemoryTier.EPISODE, it) }
        val gap = MemoryGap("gap", listOf("s4"), reason = MemoryGapReason.DISABLED)

        val candidate = MemoryCompressionPolicy.oldestContinuousLowerCandidate(
            episodes.filterNot { it.id == "e4" },
            MemoryTier.EPISODE,
            timeline(0..8),
            listOf(gap)
        )

        assertNull(candidate)
    }

    private fun node(id: String, tier: MemoryTier, t: Int, level: Int = 0) = MemoryNode(
        id = id,
        sessionId = "session",
        tier = tier,
        sourceTurnIds = listOf("s$t"),
        compressionLevel = level
    )

    private fun timeline(range: IntRange) = range.map { t ->
        MemoryTimelineEntry("s$t", t.toLong(), t.toLong())
    }
}
