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
    fun `without staged same tier four nodes consume three and retain newest`() {
        val nodes = (0..3).map { node("e$it", MemoryTier.EPISODE, it) }

        val candidate = MemoryCompressionPolicy.oldestContinuousLowerCandidate(
            nodes = nodes,
            expectedTier = MemoryTier.EPISODE,
            timeline = timeline(0..3),
            gaps = emptyList()
        )

        assertEquals(4, candidate?.candidates?.size)
        assertEquals(3, candidate?.maxConsume)
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
    fun `era forced prefix consumes longest equally fresh prefix up to five`() {
        val candidate = MemoryCompressionCandidate(
            candidates = (0..6).map { node("era$it", MemoryTier.ERA, it) },
            minConsume = 2,
            maxConsume = 5
        )

        assertEquals(5, MemoryCompressionPolicy.forcedEraPrefix(candidate).size)
    }

    @Test
    fun `era forced prefix stops before more worn node`() {
        val candidate = MemoryCompressionCandidate(
            candidates = (0..4).map { t ->
                node("era$t", MemoryTier.ERA, t, level = if (t < 4) 0 else 1)
            },
            minConsume = 2,
            maxConsume = 5
        )

        assertEquals(4, MemoryCompressionPolicy.forcedEraPrefix(candidate).size)
    }

    @Test
    fun `era forced prefix extends across wear boundary to permit minimum summary`() {
        val candidate = MemoryCompressionCandidate(
            candidates = listOf(
                node("era0", MemoryTier.ERA, 0, level = 0).copy(content = "a".repeat(20)),
                node("era1", MemoryTier.ERA, 1, level = 0).copy(content = "b".repeat(20)),
                node("era2", MemoryTier.ERA, 2, level = 1).copy(content = "c".repeat(11))
            ),
            minConsume = 2,
            maxConsume = 3
        )

        assertEquals(3, MemoryCompressionPolicy.forcedEraPrefix(candidate).size)
    }

    @Test
    fun `era candidate source must be longer than minimum summary`() {
        val exactlyMinimum = listOf(
            node("era0", MemoryTier.ERA, 0).copy(content = "a".repeat(25)),
            node("era1", MemoryTier.ERA, 1).copy(content = "b".repeat(25))
        )
        val longerThanMinimum = listOf(
            exactlyMinimum.first(),
            exactlyMinimum.last().copy(content = "b".repeat(26))
        )

        assertNull(
            MemoryCompressionPolicy.eraCandidate(
                exactlyMinimum,
                timeline(0..1),
                emptyList()
            )
        )
        assertEquals(
            2,
            MemoryCompressionPolicy.eraCandidate(
                longerThanMinimum,
                timeline(0..1),
                emptyList()
            )?.candidates?.size
        )
    }

    @Test
    fun lowerTierConsumesThreeToTenAndKeepsFiveBoundaryReferences() {
        val episodes = (0..25).map { node("e$it", MemoryTier.EPISODE, it) }

        val candidate = MemoryCompressionPolicy.oldestContinuousLowerCandidate(
            episodes,
            MemoryTier.EPISODE,
            timeline(0..25),
            emptyList()
        )!!

        assertEquals(15, candidate.candidates.size)
        assertEquals(3, candidate.minConsume)
        assertEquals(10, candidate.maxConsume)
        assertFalse("e25" in candidate.candidates.map { it.id })
        assertTrue(
            MemoryCompressionPolicy.validateConsumedPrefix(
                candidate,
                candidate.candidates.take(3).map { it.id }
            ).valid
        )
        assertTrue(
            MemoryCompressionPolicy.validateConsumedPrefix(
                candidate,
                candidate.candidates.take(10).map { it.id }
            ).valid
        )
        assertFalse(
            MemoryCompressionPolicy.validateConsumedPrefix(
                candidate,
                candidate.candidates.take(11).map { it.id }
            ).valid
        )
        assertFalse(
            MemoryCompressionPolicy.validateConsumedPrefix(
                candidate,
                listOf("e0", "e1", "e3")
            ).valid
        )
    }

    @Test
    fun threeLowerNodesCannotCompressBecauseNewestMustRemain() {
        val three = (0..2).map { node("e$it", MemoryTier.EPISODE, it) }
        val four = (0..3).map { node("e$it", MemoryTier.EPISODE, it) }

        assertNull(
            MemoryCompressionPolicy.oldestContinuousLowerCandidate(
                three,
                MemoryTier.EPISODE,
                timeline(0..3),
                emptyList()
            )
        )
        assertEquals(
            3,
            MemoryCompressionPolicy.oldestContinuousLowerCandidate(
                four,
                MemoryTier.EPISODE,
                timeline(0..3),
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
        assertEquals(2, candidate.minConsume)
        assertEquals(4, candidate.maxConsume)
    }

    @Test
    fun gapSplitsCompressionCandidatesWithoutCrossingIt() {
        val episodes = (0..8).map { node("e$it", MemoryTier.EPISODE, it) }
        val gap = MemoryGap("gap", listOf("s4"), reason = MemoryGapReason.DISABLED)

        val candidate = MemoryCompressionPolicy.oldestContinuousLowerCandidate(
            episodes.filterNot { it.id == "e4" },
            MemoryTier.EPISODE,
            timeline(0..8),
            listOf(gap)
        )

        assertEquals(listOf("e0", "e1", "e2", "e3"), candidate?.candidates?.map { it.id })
        assertEquals(3, candidate?.maxConsume)
    }

    @Test
    fun compressionSummaryMustStayWithinFiftyToFourHundredCharacters() {
        assertFalse(MemoryCompressionPolicy.validateSummary("短".repeat(49)).valid)
        assertTrue(MemoryCompressionPolicy.validateSummary("中".repeat(50)).valid)
        assertTrue(MemoryCompressionPolicy.validateSummary("长".repeat(400)).valid)
        assertFalse(MemoryCompressionPolicy.validateSummary("超".repeat(401)).valid)
    }

    @Test
    fun parentCoverageUsesProgramOwnedChildHashesInsteadOfSummaryText() {
        val children = listOf(
            node("e1", MemoryTier.EPISODE, 1).copy(content = "正文一", coverageHash = "hash-1"),
            node("e2", MemoryTier.EPISODE, 2).copy(content = "正文二", coverageHash = "hash-2")
        )

        val units = MemoryHashes.parentCoverageUnits(children)

        assertEquals(listOf("e1", "e2"), units.map { it.sourceId })
        assertEquals(listOf("hash-1", "hash-2"), units.map { it.text })
        assertFalse(units.any { it.text.contains("正文") })
    }

    private fun node(id: String, tier: MemoryTier, t: Int, level: Int = 0) = MemoryNode(
        id = id,
        sessionId = "session",
        tier = tier,
        sourceTurnIds = listOf("s$t"),
        compressionLevel = level,
        content = "x".repeat(120)
    )

    private fun timeline(range: IntRange) = range.map { t ->
        MemoryTimelineEntry("s$t", t.toLong(), t.toLong())
    }
}
