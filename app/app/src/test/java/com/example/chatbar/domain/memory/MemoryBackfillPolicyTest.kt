package com.example.chatbar.domain.memory

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MemoryBackfillState
import com.example.chatbar.data.local.entity.MemoryBackfillStatus
import com.example.chatbar.data.local.entity.MemoryGap
import com.example.chatbar.data.local.entity.MemoryGapReason
import com.example.chatbar.data.local.entity.MemoryNode
import com.example.chatbar.data.local.entity.MemoryTier
import com.example.chatbar.data.local.entity.MemoryTimelineEntry
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.domain.chat.ContextWindowManager
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryBackfillPolicyTest {
    @Test
    fun activeRunnerKeepsRunningState() {
        val running = MemoryBackfillState(
            status = MemoryBackfillStatus.RUNNING,
            pendingSourceTurnIds = listOf("s0")
        )

        assertEquals(
            running,
            MemoryBackfillPolicy.pauseOrphanedRun(running, hasActiveRunner = true)
        )
    }

    @Test
    fun orphanedRunningStatePausesAfterProcessRestart() {
        val running = MemoryBackfillState(
            status = MemoryBackfillStatus.RUNNING,
            pendingSourceTurnIds = listOf("s0")
        )

        val reconciled = MemoryBackfillPolicy.pauseOrphanedRun(
            running,
            hasActiveRunner = false
        )

        assertEquals(MemoryBackfillStatus.PAUSED, reconciled.status)
        assertEquals(listOf("s0"), reconciled.pendingSourceTurnIds)
    }

    @Test
    fun changingContextFromTenToFifteenPreservesGapAndShowsOnlyArchivedPart() {
        val messages = (0..23).flatMap { turn ->
            listOf(
                message("u$turn", MessageRole.USER, turn),
                message("a$turn", MessageRole.ASSISTANT, turn)
            )
        }
        val contextWindowManager = ContextWindowManager()
        val archivedWithTen = contextWindowManager.getMessagesToArchive(messages, 10)
            .mapNotNull { it.sourceTurnId }
            .distinct()
        val archivedWithFifteen = contextWindowManager.getMessagesToArchive(messages, 15)
            .mapNotNull { it.sourceTurnId }
            .distinct()
        val boundary = MemoryBackfillPolicy.reconcileToCurrentArchive(
            gaps = listOf(
                MemoryGap("gap", archivedWithTen, reason = MemoryGapReason.LEGACY_UNKNOWN)
            ),
            backfill = MemoryBackfillState(
                status = MemoryBackfillStatus.PAUSED,
                pendingSourceTurnIds = archivedWithTen
            ),
            timeline = timeline(0..23),
            availableSourceTurnIds = sourceIds(0..23).toSet(),
            archivedSourceTurnIds = archivedWithFifteen.toSet()
        )

        assertEquals(sourceIds(0..12), archivedWithTen)
        assertEquals(sourceIds(0..7), archivedWithFifteen)
        assertEquals(sourceIds(0..12), boundary.gaps.single().sourceTurnIds)
        assertEquals(sourceIds(0..7), boundary.backfill.pendingSourceTurnIds)
        assertEquals(
            sourceIds(0..7),
            MemoryBackfillPolicy.eligibleSourceTurnIds(
                activeNodes = emptyList(),
                gaps = boundary.gaps,
                timeline = timeline(0..23),
                availableSourceTurnIds = sourceIds(0..23).toSet(),
                archivedSourceTurnIds = archivedWithFifteen.toSet()
            )
        )
    }

    @Test
    fun contextExpansionHidesBackfillWithoutDeletingDurableGap() {
        val sourceIds = sourceIds(0..23)
        val boundary = MemoryBackfillPolicy.reconcileToCurrentArchive(
            gaps = listOf(
                MemoryGap(
                    id = "gap",
                    sourceTurnIds = sourceIds.take(13),
                    reason = MemoryGapReason.LEGACY_UNKNOWN
                )
            ),
            backfill = MemoryBackfillState(
                status = MemoryBackfillStatus.PAUSED,
                pendingSourceTurnIds = sourceIds.take(13)
            ),
            timeline = timeline(0..23),
            availableSourceTurnIds = sourceIds.toSet(),
            archivedSourceTurnIds = sourceIds.take(8).toSet()
        )

        assertEquals(sourceIds.take(13), boundary.gaps.single().sourceTurnIds)
        assertEquals(sourceIds.take(8), boundary.backfill.pendingSourceTurnIds)
    }

    @Test
    fun expandingThenShrinkingContextMakesSameGapEligibleAgain() {
        val sourceIds = sourceIds(0..23)
        val expanded = MemoryBackfillPolicy.reconcileToCurrentArchive(
            gaps = listOf(
                MemoryGap(
                    id = "gap",
                    sourceTurnIds = sourceIds.take(13),
                    reason = MemoryGapReason.LEGACY_UNKNOWN
                )
            ),
            backfill = MemoryBackfillState(
                status = MemoryBackfillStatus.PAUSED,
                pendingSourceTurnIds = sourceIds.take(13)
            ),
            timeline = timeline(0..23),
            availableSourceTurnIds = sourceIds.toSet(),
            archivedSourceTurnIds = emptySet()
        )
        val afterShrink = MemoryBackfillPolicy.eligibleSourceTurnIds(
            activeNodes = emptyList(),
            gaps = expanded.gaps,
            timeline = timeline(0..23),
            availableSourceTurnIds = sourceIds.toSet(),
            archivedSourceTurnIds = sourceIds.take(8).toSet()
        )

        assertEquals(sourceIds.take(13), expanded.gaps.single().sourceTurnIds)
        assertEquals(MemoryBackfillStatus.IDLE, expanded.backfill.status)
        assertEquals(sourceIds.take(8), afterShrink)
    }

    @Test
    fun oneTimeRecoveryRestoresPreviouslyDeletedGapMarkers() {
        val sourceIds = sourceIds(0..23)
        val recovered = MemoryBackfillPolicy.reconcileToCurrentArchive(
            gaps = emptyList(),
            backfill = MemoryBackfillState(),
            timeline = timeline(0..23),
            availableSourceTurnIds = sourceIds.toSet(),
            archivedSourceTurnIds = sourceIds.take(8).toSet(),
            discoverUntrackedArchived = true
        )

        assertEquals(sourceIds.take(8), recovered.gaps.single().sourceTurnIds)
        assertEquals(0L, recovered.gaps.single().startSourceOrder)
        assertEquals(7L, recovered.gaps.single().endSourceOrder)
        assertEquals(true, recovered.recoveryCompleted)
    }

    @Test
    fun oneTimeRecoveryWaitsUntilUntrackedTurnsLeaveDirectContext() {
        val sourceIds = sourceIds(0..23)
        val whileExpanded = MemoryBackfillPolicy.reconcileToCurrentArchive(
            gaps = emptyList(),
            backfill = MemoryBackfillState(),
            timeline = timeline(0..23),
            availableSourceTurnIds = sourceIds.toSet(),
            archivedSourceTurnIds = emptySet(),
            discoverUntrackedArchived = true
        )
        val afterShrink = MemoryBackfillPolicy.reconcileToCurrentArchive(
            gaps = whileExpanded.gaps,
            backfill = whileExpanded.backfill,
            timeline = timeline(0..23),
            availableSourceTurnIds = sourceIds.toSet(),
            archivedSourceTurnIds = sourceIds.take(8).toSet(),
            discoverUntrackedArchived = true
        )

        assertEquals(false, whileExpanded.recoveryCompleted)
        assertEquals(emptyList<MemoryGap>(), whileExpanded.gaps)
        assertEquals(sourceIds.take(8), afterShrink.gaps.single().sourceTurnIds)
        assertEquals(true, afterShrink.recoveryCompleted)
    }

    @Test
    fun recoveryExcludesCoveredAndNormalPendingTurns() {
        val sourceIds = sourceIds(0..7)
        val recovered = MemoryBackfillPolicy.reconcileToCurrentArchive(
            gaps = emptyList(),
            backfill = MemoryBackfillState(),
            timeline = timeline(0..7),
            availableSourceTurnIds = sourceIds.toSet(),
            archivedSourceTurnIds = sourceIds.toSet(),
            coveredSourceTurnIds = setOf("s0", "s1"),
            normalPendingSourceTurnIds = setOf("s2", "s3"),
            discoverUntrackedArchived = true
        )

        assertEquals(sourceIds(4..7), recovered.gaps.single().sourceTurnIds)
    }

    @Test
    fun recoveryHonorsPermanentClearWatermark() {
        val sourceIds = sourceIds(0..7)
        val recovered = MemoryBackfillPolicy.reconcileToCurrentArchive(
            gaps = emptyList(),
            backfill = MemoryBackfillState(),
            timeline = timeline(0..7),
            availableSourceTurnIds = sourceIds.toSet(),
            archivedSourceTurnIds = sourceIds.toSet(),
            recordingStartsAfterSourceOrder = 7L,
            discoverUntrackedArchived = true
        )

        assertEquals(emptyList<MemoryGap>(), recovered.gaps)
        assertEquals(true, recovered.recoveryCompleted)
    }

    @Test
    fun recoveryIsIdempotent() {
        val sourceIds = sourceIds(0..7)
        val first = MemoryBackfillPolicy.reconcileToCurrentArchive(
            gaps = emptyList(),
            backfill = MemoryBackfillState(),
            timeline = timeline(0..7),
            availableSourceTurnIds = sourceIds.toSet(),
            archivedSourceTurnIds = sourceIds.toSet(),
            discoverUntrackedArchived = true
        )
        val second = MemoryBackfillPolicy.reconcileToCurrentArchive(
            gaps = first.gaps,
            backfill = first.backfill,
            timeline = timeline(0..7),
            availableSourceTurnIds = sourceIds.toSet(),
            archivedSourceTurnIds = sourceIds.toSet(),
            discoverUntrackedArchived = true
        )

        assertEquals(first.gaps, second.gaps)
    }

    @Test
    fun explicitRefreshDiscoversTurnsThatBecomeArchivedAfterContextShrink() {
        val sourceIds = sourceIds(0..23)
        val whileAllDirect = MemoryBackfillPolicy.reconcileToCurrentArchive(
            gaps = emptyList(),
            backfill = MemoryBackfillState(),
            timeline = timeline(0..23),
            availableSourceTurnIds = sourceIds.toSet(),
            archivedSourceTurnIds = emptySet(),
            discoverUntrackedArchived = true
        )
        val afterShrink = MemoryBackfillPolicy.reconcileToCurrentArchive(
            gaps = whileAllDirect.gaps,
            backfill = whileAllDirect.backfill,
            timeline = timeline(0..23),
            availableSourceTurnIds = sourceIds.toSet(),
            archivedSourceTurnIds = sourceIds.take(18).toSet(),
            discoverUntrackedArchived = true
        )

        assertEquals(emptyList<MemoryGap>(), whileAllDirect.gaps)
        assertEquals(sourceIds.take(18), afterShrink.gaps.single().sourceTurnIds)
        assertEquals(
            sourceIds.take(18),
            MemoryBackfillPolicy.eligibleSourceTurnIds(
                activeNodes = emptyList(),
                gaps = afterShrink.gaps,
                timeline = timeline(0..23),
                availableSourceTurnIds = sourceIds.toSet(),
                archivedSourceTurnIds = sourceIds.take(18).toSet()
            )
        )
    }

    @Test
    fun backfillCandidatesAreMissingAndArchivedIntersection() {
        val sourceIds = sourceIds(0..23)
        val candidates = MemoryBackfillPolicy.eligibleSourceTurnIds(
            activeNodes = emptyList(),
            gaps = listOf(
                MemoryGap(
                    id = "gap",
                    sourceTurnIds = sourceIds.take(13),
                    reason = MemoryGapReason.LEGACY_UNKNOWN
                )
            ),
            timeline = timeline(0..23),
            availableSourceTurnIds = sourceIds.toSet(),
            archivedSourceTurnIds = sourceIds.take(8).toSet()
        )

        assertEquals(sourceIds.take(8), candidates)
    }

    @Test
    fun uncoveredHoleInsideActiveRangeWaitsUntilItLeavesContext() {
        val active = listOf(node("episode-0", listOf("s0")), node("episode-2", listOf("s2")))
        val whileDirect = MemoryBackfillPolicy.eligibleSourceTurnIds(
            activeNodes = active,
            gaps = emptyList(),
            timeline = timeline(0..2),
            availableSourceTurnIds = sourceIds(0..2).toSet(),
            archivedSourceTurnIds = setOf("s0")
        )
        val afterLeaving = MemoryBackfillPolicy.eligibleSourceTurnIds(
            activeNodes = active,
            gaps = emptyList(),
            timeline = timeline(0..2),
            availableSourceTurnIds = sourceIds(0..2).toSet(),
            archivedSourceTurnIds = setOf("s0", "s1")
        )

        assertEquals(emptyList<String>(), whileDirect)
        assertEquals(listOf("s1"), afterLeaving)
    }

    private fun sourceIds(range: IntRange): List<String> = range.map { "s$it" }

    private fun timeline(range: IntRange): List<MemoryTimelineEntry> = range.map { t ->
        MemoryTimelineEntry("s$t", t.toLong(), t.toLong())
    }

    private fun node(id: String, sourceTurnIds: List<String>) = MemoryNode(
        id = id,
        sessionId = "session",
        tier = MemoryTier.EPISODE,
        sourceTurnIds = sourceTurnIds
    )

    private fun message(id: String, role: MessageRole, turn: Int) = ChatMessage(
        id = id,
        sessionId = "session",
        role = role,
        content = id,
        sourceTurnId = "s$turn",
        sourceTurnOrder = turn.toLong(),
        createdAt = turn.toLong(),
        updatedAt = turn.toLong()
    )
}
