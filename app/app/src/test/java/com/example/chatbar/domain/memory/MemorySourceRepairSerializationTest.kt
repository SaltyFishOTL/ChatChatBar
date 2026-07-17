package com.example.chatbar.domain.memory

import com.example.chatbar.data.local.entity.MemorySessionSnapshot
import com.example.chatbar.data.local.entity.MemorySessionState
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
}
