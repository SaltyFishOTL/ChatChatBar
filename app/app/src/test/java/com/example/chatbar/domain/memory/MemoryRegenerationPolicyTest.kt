package com.example.chatbar.domain.memory

import com.example.chatbar.data.local.entity.MemoryBackfillState
import com.example.chatbar.data.local.entity.MemoryBackfillStatus
import com.example.chatbar.data.local.entity.MemoryGap
import com.example.chatbar.data.local.entity.MemoryGapReason
import com.example.chatbar.data.local.entity.MemoryHead
import com.example.chatbar.data.local.entity.MemoryPageState
import com.example.chatbar.data.local.entity.MemorySessionState
import com.example.chatbar.data.local.entity.MemoryTier
import com.example.chatbar.data.local.entity.MemoryTimelineEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryRegenerationPolicyTest {
    @Test
    fun fullRegenerationClearsDerivedMemoryAndPermanentClearBoundary() {
        val current = MemorySessionState(
            sessionId = "session",
            episodePage = MemoryPageState(MemoryTier.EPISODE, listOf("episode")),
            arcPage = MemoryPageState(MemoryTier.ARC, listOf("arc")),
            eraPage = MemoryPageState(MemoryTier.ERA, listOf("era")),
            legacyReferenceNodeIds = listOf("legacy"),
            head = MemoryHead(throughSourceTurnId = "s8", location = "old"),
            gaps = listOf(MemoryGap("gap", listOf("s3"), reason = MemoryGapReason.DISABLED)),
            pendingSourceTurnIds = listOf("s4"),
            backfill = MemoryBackfillState(
                status = MemoryBackfillStatus.PAUSED,
                pendingSourceTurnIds = listOf("s3")
            ),
            recordingStartsAfterSourceOrder = 8,
            revision = 41,
            createdAt = 10,
            updatedAt = 20
        )
        val timeline = listOf(
            MemoryTimelineEntry("s0", 0, 0),
            MemoryTimelineEntry("s1", 1, 1)
        )

        val reset = MemoryRegenerationPolicy.resetForFullRegeneration(
            current = current,
            timeline = timeline,
            backfillSourceTurnIds = listOf("s0", "s1"),
            memoryEnabled = true,
            now = 99
        )

        assertTrue(reset.activeNodeIds.isEmpty())
        assertTrue(reset.legacyReferenceNodeIds.isEmpty())
        assertTrue(reset.head.render().isBlank())
        assertEquals(1, reset.gaps.size)
        assertEquals(listOf("s0", "s1"), reset.gaps.single().sourceTurnIds)
        assertTrue(reset.pendingSourceTurnIds.isEmpty())
        assertTrue(reset.fullRegenerationPending)
        assertEquals(MemoryBackfillStatus.IDLE, reset.backfill.status)
        assertNull(reset.recordingStartsAfterSourceOrder)
        assertEquals(timeline, reset.timeline)
        assertEquals(42L, reset.revision)
        assertEquals(10L, reset.createdAt)
        assertEquals(99L, reset.updatedAt)
        assertTrue(reset.memoryWasEnabled)
    }

    @Test
    fun fullRegenerationCreatesSeparateBackfillGapsAcrossTimelineBreaks() {
        val timeline = listOf(
            MemoryTimelineEntry("s0", 0, 0),
            MemoryTimelineEntry("s1", 1, 1),
            MemoryTimelineEntry("deleted", 2, 2, tombstone = true),
            MemoryTimelineEntry("s3", 3, 3)
        )

        val reset = MemoryRegenerationPolicy.resetForFullRegeneration(
            current = MemorySessionState(sessionId = "session"),
            timeline = timeline,
            backfillSourceTurnIds = listOf("s0", "s1", "deleted", "s3", "missing"),
            memoryEnabled = true,
            now = 99
        )

        assertEquals(
            listOf(listOf("s0", "s1"), listOf("s3")),
            reset.gaps.map { it.sourceTurnIds }
        )
    }

    @Test
    fun incompleteFullRegenerationTailReturnsToNormalPending() {
        val current = MemorySessionState(
            sessionId = "session",
            timeline = listOf(
                MemoryTimelineEntry("s0", 0, 0),
                MemoryTimelineEntry("s1", 1, 1),
                MemoryTimelineEntry("s2", 2, 2)
            ),
            gaps = listOf(
                MemoryGap(
                    id = "gap",
                    sourceTurnIds = listOf("s0", "s1", "s2"),
                    reason = MemoryGapReason.LEGACY_UNKNOWN
                )
            ),
            pendingSourceTurnIds = listOf("s2"),
            backfill = MemoryBackfillState(
                status = MemoryBackfillStatus.RUNNING,
                pendingSourceTurnIds = listOf("s0", "s1")
            ),
            fullRegenerationPending = true
        )

        val deferred = MemoryRegenerationPolicy.deferIncompleteBackfillTail(current)!!

        assertEquals(listOf("s0", "s1", "s2"), deferred.pendingSourceTurnIds)
        assertTrue(deferred.gaps.isEmpty())
        assertTrue(deferred.backfill.pendingSourceTurnIds.isEmpty())
        assertEquals(MemoryBackfillStatus.RUNNING, deferred.backfill.status)
    }

    @Test
    fun pausedFullRegenerationKeepsBackfillTailForResume() {
        val current = MemorySessionState(
            sessionId = "session",
            backfill = MemoryBackfillState(
                status = MemoryBackfillStatus.PAUSED,
                pendingSourceTurnIds = listOf("s0")
            ),
            fullRegenerationPending = true
        )

        assertNull(MemoryRegenerationPolicy.deferIncompleteBackfillTail(current))
    }
}
