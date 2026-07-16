package com.example.chatbar.domain.memory

import com.example.chatbar.data.local.entity.MemoryCoverageUnit
import com.example.chatbar.data.local.entity.MemoryHead
import com.example.chatbar.data.local.entity.MemoryNode
import com.example.chatbar.data.local.entity.MemoryPageState
import com.example.chatbar.data.local.entity.MemorySessionState
import com.example.chatbar.data.local.entity.MemoryTier
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryBudgetPolicyTest {
    @Test
    fun budgetCountsOnlyActiveEpisodeArcEraBodies() {
        val episode = node("episode", MemoryTier.EPISODE, "1234")
        val arc = node("arc", MemoryTier.ARC, "12345")
        val inactive = node("inactive", MemoryTier.ERA, "ignored")
        val state = MemorySessionState(
            sessionId = "session",
            episodePage = MemoryPageState(MemoryTier.EPISODE, listOf(episode.id)),
            arcPage = MemoryPageState(MemoryTier.ARC, listOf(arc.id)),
            head = MemoryHead(location = "HEAD不计预算"),
            pendingSourceTurnIds = listOf("pending")
        )

        assertEquals(9, MemoryBudgetPolicy.archiveChars(state, listOf(episode, arc, inactive).associateBy { it.id }))
    }

    @Test
    fun limitStartsAtTwoThousandAndIncreasesToTwentyThousand() {
        assertEquals(2000, MemoryBudgetPolicy.normalizedLimit(0))
        assertEquals(4000, MemoryBudgetPolicy.increase(2000))
        assertEquals(20000, MemoryBudgetPolicy.increase(19000))
        assertFalse(MemoryBudgetPolicy.canIncrease(20000))
        assertTrue(MemoryBudgetPolicy.canIncrease(18000))
    }

    @Test
    fun exactLimitFitsButOneExtraCharacterDoesNot() {
        val state = MemorySessionState(
            sessionId = "session",
            episodePage = MemoryPageState(MemoryTier.EPISODE, listOf("node"))
        )
        assertFalse(MemoryBudgetPolicy.isOverLimit(state, mapOf("node" to node("node", MemoryTier.EPISODE, "x".repeat(2000))), 2000))
        assertTrue(MemoryBudgetPolicy.isOverLimit(state, mapOf("node" to node("node", MemoryTier.EPISODE, "x".repeat(2001))), 2000))
    }

    @Test
    fun budgetCountsAggregateBodyInsteadOfCoverageProof() {
        val node = MemoryNode(
            id = "episode",
            sessionId = "session",
            tier = MemoryTier.EPISODE,
            content = "合并摘要",
            coverageUnits = listOf(
                MemoryCoverageUnit("s0", "很长的逐轮覆盖证明"),
                MemoryCoverageUnit("s1", "另一段很长的逐轮覆盖证明")
            )
        )
        val state = MemorySessionState(
            sessionId = "session",
            episodePage = MemoryPageState(MemoryTier.EPISODE, listOf(node.id))
        )

        assertEquals("合并摘要".length, MemoryBudgetPolicy.archiveChars(state, mapOf(node.id to node)))
    }

    private fun node(id: String, tier: MemoryTier, body: String) = MemoryNode(
        id = id,
        sessionId = "session",
        tier = tier,
        coverageUnits = listOf(MemoryCoverageUnit("source-$id", body))
    )
}
