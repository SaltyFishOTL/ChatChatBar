package com.example.chatbar.domain.memory

import com.example.chatbar.data.local.entity.MemoryGap
import com.example.chatbar.data.local.entity.MemoryGapReason
import com.example.chatbar.data.local.entity.MemoryNode
import com.example.chatbar.data.local.entity.MemorySessionState
import com.example.chatbar.data.local.entity.MemoryTier
import com.example.chatbar.data.local.entity.MemoryTimelineEntry
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryEpisodeBatchPolicyTest {
    private val timeline = (0..5).map { MemoryTimelineEntry("s$it", it.toLong(), it.toLong()) }

    @Test fun `normal batch requires exact target`() {
        assertEquals(listOf("s0", "s1"), batch(listOf("s0", "s1", "s2"), 2))
        assertEquals(emptyList<String>(), batch(listOf("s0"), 2))
        assertEquals(1, MemoryEpisodeBatchPolicy.trailingWaitCount(3, 2))
    }

    @Test fun `internal odd hole becomes full batch then bounded singleton`() {
        val nodes = listOf(node("left", listOf("s0")), node("right", listOf("s4")))
        val state = MemorySessionState(sessionId = "session", timeline = timeline)
        assertEquals(
            listOf("s1", "s2"),
            MemoryEpisodeBatchPolicy.nextBatch(
                listOf("s1", "s2", "s3"), state, nodes, 2,
                MemoryEpisodeBatchMode.HISTORICAL_BACKFILL
            )
        )
        assertEquals(
            listOf("s3"),
            MemoryEpisodeBatchPolicy.nextBatch(
                listOf("s3"), state, nodes + node("new", listOf("s1", "s2")), 2,
                MemoryEpisodeBatchMode.HISTORICAL_BACKFILL
            )
        )
    }

    @Test fun `disabled deleted and declined gaps reject singleton`() {
        val nodes = listOf(node("left", listOf("s0")), node("right", listOf("s2")))
        for (reason in listOf(
            MemoryGapReason.DISABLED,
            MemoryGapReason.DELETED_SOURCE,
            MemoryGapReason.DECLINED_BACKFILL
        )) {
            val state = MemorySessionState(
                sessionId = "session",
                timeline = timeline,
                gaps = listOf(MemoryGap("gap", listOf("s1"), reason = reason))
            )
            assertEquals(
                emptyList<String>(),
                MemoryEpisodeBatchPolicy.nextBatch(
                    listOf("s1"), state, nodes, 2, MemoryEpisodeBatchMode.HISTORICAL_BACKFILL
                )
            )
        }
    }

    private fun batch(ids: List<String>, target: Int) = MemoryEpisodeBatchPolicy.nextBatch(
        ids,
        MemorySessionState(sessionId = "session", timeline = timeline),
        emptyList(),
        target,
        MemoryEpisodeBatchMode.NORMAL
    )

    private fun node(id: String, ids: List<String>) = MemoryNode(
        id = id, sessionId = "session", tier = MemoryTier.EPISODE, sourceTurnIds = ids
    )
}
