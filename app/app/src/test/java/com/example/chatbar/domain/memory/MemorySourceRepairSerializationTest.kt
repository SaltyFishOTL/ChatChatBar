package com.example.chatbar.domain.memory

import com.example.chatbar.data.local.entity.MemorySessionSnapshot
import com.example.chatbar.data.local.entity.MemorySessionState
import com.example.chatbar.data.local.entity.MemoryCommitJournal
import com.example.chatbar.data.local.entity.MemorySourceRepairState
import com.example.chatbar.data.local.entity.MemorySourceRepairStatus
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MemorySourceRepairSerializationTest {
    private val json = Json { encodeDefaults = true }

    @Test
    fun oldStateAndSnapshotDecodeWithIdleRepairDefaults() {
        val state = json.decodeFromString(
            MemorySessionState.serializer(),
            """{"sessionId":"session"}"""
        )
        val snapshot = json.decodeFromString(
            MemorySessionSnapshot.serializer(),
            "{}"
        )

        assertEquals(MemorySourceRepairStatus.IDLE, state.sourceRepair.status)
        assertEquals(MemorySourceRepairStatus.IDLE, snapshot.sourceRepair.status)
        assertTrue(state.sourceRepair.pendingRootNodeIds.isEmpty())
        assertTrue(!state.fullRegenerationPending)
        assertTrue(!snapshot.fullRegenerationPending)
        assertTrue(state.projectedDeletedSourceTurnIds.isEmpty())
        assertTrue(snapshot.projectedDeletedSourceTurnIds.isEmpty())
    }

    @Test
    fun repairStateRoundTripsPendingWorkAndFailure() {
        val state = MemorySessionState(
            sessionId = "session",
            sourceRepair = MemorySourceRepairState(
                status = MemorySourceRepairStatus.ERROR,
                pendingRootNodeIds = listOf("root"),
                completedRootCount = 1,
                totalRootCount = 2,
                repairHead = true,
                error = "model failed"
            )
        )

        val decoded = json.decodeFromString(
            MemorySessionState.serializer(),
            json.encodeToString(MemorySessionState.serializer(), state)
        )

        assertEquals(state.sourceRepair, decoded.sourceRepair)
    }

    @Test
    fun fullRegenerationPendingRoundTrips() {
        val state = MemorySessionState(
            sessionId = "session",
            fullRegenerationPending = true
        )

        val decoded = json.decodeFromString(
            MemorySessionState.serializer(),
            json.encodeToString(MemorySessionState.serializer(), state)
        )

        assertTrue(decoded.fullRegenerationPending)
    }

    @Test
    fun deletionProjectionAndJournalCleanupFieldsRoundTripWithOldDefaults() {
        val oldJournal = json.decodeFromString(
            MemoryCommitJournal.serializer(),
            """{"id":"journal","sessionId":"session","expectedStateRevision":0,"nextState":{"sessionId":"session"}}"""
        )
        assertTrue(oldJournal.deleteNodeIds.isEmpty())
        assertTrue(oldJournal.deleteRevisionIds.isEmpty())
        assertTrue(oldJournal.deleteTransactionIds.isEmpty())

        val journal = oldJournal.copy(
            deleteNodeIds = listOf("node"),
            deleteRevisionIds = listOf("revision"),
            deleteTransactionIds = listOf("transaction"),
            nextState = oldJournal.nextState.copy(
                projectedDeletedSourceTurnIds = setOf("source")
            )
        )
        val decoded = json.decodeFromString(
            MemoryCommitJournal.serializer(),
            json.encodeToString(MemoryCommitJournal.serializer(), journal)
        )
        assertEquals(journal.deleteNodeIds, decoded.deleteNodeIds)
        assertEquals(journal.deleteRevisionIds, decoded.deleteRevisionIds)
        assertEquals(journal.deleteTransactionIds, decoded.deleteTransactionIds)
        assertEquals(setOf("source"), decoded.nextState.projectedDeletedSourceTurnIds)
    }
}
