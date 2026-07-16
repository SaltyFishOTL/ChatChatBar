package com.example.chatbar.domain.memory

import com.example.chatbar.data.local.entity.MemoryNode
import com.example.chatbar.data.local.entity.MemoryPageState
import com.example.chatbar.data.local.entity.MemorySessionState
import com.example.chatbar.data.local.entity.MemoryTier
import com.example.chatbar.data.local.entity.MemoryTimelineEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryPageOrderPolicyTest {
    @Test
    fun `normalizes complete verifiable page to ascending T order`() {
        val nodes = listOf(node("late", 2), node("early", 0), node("middle", 1))
            .associateBy { it.id }
        val state = state(listOf("late", "early", "middle"))

        val normalized = MemoryPageOrderPolicy.normalize(state, nodes)

        assertEquals(listOf("early", "middle", "late"), normalized.episodePage.activeNodeIds)
        assertTrue(
            MemoryIntegrityAudit.warnings(normalized, nodes)
                .none { "未按T时间线升序排列" in it.message }
        )
    }

    @Test
    fun `audit reports real stored disorder even when display can sort nodes`() {
        val nodes = listOf(node("early", 0), node("late", 1)).associateBy { it.id }
        val state = state(listOf("late", "early"))

        assertEquals(
            listOf("early", "late"),
            MemoryTimelinePolicy.sortNodes(
                state.episodePage.activeNodeIds.mapNotNull(nodes::get),
                state.timeline
            ).map { it.id }
        )
        assertTrue(
            MemoryIntegrityAudit.warnings(state, nodes)
                .any { "未按T时间线升序排列" in it.message }
        )
    }

    @Test
    fun `does not reorder or order-warn when T proof is incomplete`() {
        val nodes = listOf(node("known", 0), node("unknown", 9)).associateBy { it.id }
        val state = state(listOf("unknown", "known"), timelineEnd = 0)

        val normalized = MemoryPageOrderPolicy.normalize(state, nodes)

        assertEquals(state, normalized)
        assertFalse(
            MemoryIntegrityAudit.warnings(state, nodes)
                .any { "未按T时间线升序排列" in it.message }
        )
    }

    private fun state(ids: List<String>, timelineEnd: Int = 2) = MemorySessionState(
        sessionId = "session",
        episodePage = MemoryPageState(MemoryTier.EPISODE, activeNodeIds = ids),
        timeline = (0..timelineEnd).map { t ->
            MemoryTimelineEntry("s$t", t.toLong(), t.toLong())
        }
    )

    private fun node(id: String, t: Int) = MemoryNode(
        id = id,
        sessionId = "session",
        tier = MemoryTier.EPISODE,
        sourceTurnIds = listOf("s$t")
    )
}
