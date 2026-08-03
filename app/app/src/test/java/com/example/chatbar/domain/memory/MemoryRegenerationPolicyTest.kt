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
import org.junit.Assert.assertFalse
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

    @Test
    fun abortingFullRegenerationKeepsRecordedMemoryAndRemainingGaps() {
        val current = MemorySessionState(
            sessionId = "session",
            episodePage = MemoryPageState(MemoryTier.EPISODE, listOf("episode")),
            arcPage = MemoryPageState(MemoryTier.ARC, listOf("arc")),
            head = MemoryHead(throughSourceTurnId = "s2", location = "partial"),
            gaps = listOf(
                MemoryGap(
                    id = "gap",
                    sourceTurnIds = listOf("s3", "s4"),
                    reason = MemoryGapReason.LEGACY_UNKNOWN
                )
            ),
            pendingSourceTurnIds = listOf("s4"),
            backfill = MemoryBackfillState(
                status = MemoryBackfillStatus.ERROR,
                pendingSourceTurnIds = listOf("s3", "s4"),
                completedSourceTurnIds = listOf("s0", "s1"),
                completedEpisodeCount = 1,
                error = "正式压缩：输出连续5次失败"
            ),
            fullRegenerationPending = true,
            revision = 41,
            createdAt = 10,
            updatedAt = 20
        )

        val aborted = MemoryRegenerationPolicy.abortFullRegeneration(current, now = 99)

        assertFalse(aborted.fullRegenerationPending)
        assertEquals(listOf("episode"), aborted.episodePage.activeNodeIds)
        assertEquals(listOf("arc"), aborted.arcPage.activeNodeIds)
        assertEquals("partial", aborted.head.location)
        assertEquals(current.gaps, aborted.gaps)
        assertEquals(listOf("s4"), aborted.pendingSourceTurnIds)
        assertEquals(MemoryBackfillStatus.IDLE, aborted.backfill.status)
        assertTrue(aborted.backfill.pendingSourceTurnIds.isEmpty())
        assertNull(aborted.backfill.error)
        assertEquals(42L, aborted.revision)
        assertEquals(99L, aborted.updatedAt)
    }

    @Test
    fun abortWithoutPendingFullRegenerationStillResetsBackfillToIdle() {
        val current = MemorySessionState(
            sessionId = "session",
            backfill = MemoryBackfillState(
                status = MemoryBackfillStatus.ERROR,
                pendingSourceTurnIds = listOf("s0"),
                error = "模型调用失败"
            ),
            revision = 5
        )

        val aborted = MemoryRegenerationPolicy.abortFullRegeneration(current, now = 7)

        assertEquals(MemoryBackfillStatus.IDLE, aborted.backfill.status)
        assertTrue(aborted.backfill.pendingSourceTurnIds.isEmpty())
        assertNull(aborted.backfill.error)
        assertEquals(6L, aborted.revision)
    }
}
