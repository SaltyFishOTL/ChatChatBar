package com.example.chatbar.domain.memory

import com.example.chatbar.data.local.entity.MemoryHead
import com.example.chatbar.data.local.entity.MemoryHeadActionProjection
import com.example.chatbar.data.local.entity.MemoryTimelineEntry
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryHeadUpdatePolicyTest {
    private val stableIds = listOf("source-0", "source-1", "source-2", "source-3")

    @Test
    fun hashlessHeadCannotBecomeFreshWhenItsAffectedSourceIsEditedAway() {
        val timeline = stableIds.mapIndexed { order, id ->
            MemoryTimelineEntry(sourceTurnId = id, sourceOrder = order.toLong(), displayT = order.toLong())
        }
        val original = MemoryHead(throughSourceTurnId = "source-2", goals = "旧目标")
        assertFalse(MemoryHeadUpdatePolicy.hashlessHeadIsStale(original, timeline, emptySet()))
        assertFalse(MemoryHeadUpdatePolicy.hashlessHeadIsStale(original, timeline, setOf("source-3")))
        val observedStale = MemoryHeadUpdatePolicy.hashlessHeadIsStale(original, timeline, setOf("source-1"))
        assertTrue(observedStale)
        assertTrue(MemoryHeadUpdatePolicy.hashlessHeadIsStale(original.copy(stale = observedStale), timeline, emptySet()))
    }

    @Test
    fun legacyRolledHeadKeepsUnprovedMenuPrefixWithheldAcrossEditsAndReload() {
        val json = Json { encodeDefaults = true }
        val timeline = stableIds.mapIndexed { order, id ->
            MemoryTimelineEntry(sourceTurnId = id, sourceOrder = order.toLong(), displayT = order.toLong())
        }
        val rolled = MemoryHead(
            throughSourceTurnId = "source-2",
            goals = "保留旧正文",
            sourceFingerprints = mapOf("source-2" to "known-fingerprint"),
        )
        assertFalse(json.encodeToString(MemoryHead.serializer(), rolled).contains("actionProjection"))
        assertNull(MemoryHeadUpdatePolicy.actionProjection(rolled, timeline, emptySet()))
        assertNull(MemoryHeadUpdatePolicy.actionProjection(rolled, timeline, setOf("source-3")))
        assertNull(MemoryHeadUpdatePolicy.actionProjection(rolled, timeline, setOf("source-2")))

        val withheld = MemoryHeadUpdatePolicy.actionProjection(rolled, timeline, setOf("source-1"))
        assertEquals(MemoryHeadActionProjection.WITHHELD, withheld)
        val persisted = rolled.copy(actionProjection = withheld)
        val cold = json.decodeFromString(MemoryHead.serializer(), json.encodeToString(MemoryHead.serializer(), persisted))
        assertEquals(rolled.goals, cold.goals)
        assertEquals(withheld, MemoryHeadUpdatePolicy.actionProjection(cold, timeline, emptySet()))

        val regenerated = rolled.copy(actionProjection = MemoryHeadActionProjection.VERIFIED)
        assertEquals(MemoryHeadActionProjection.VERIFIED,
            MemoryHeadUpdatePolicy.actionProjection(regenerated, timeline, setOf("source-1")))
    }

    @Test
    fun baselineExcludesLatestCompleteHotTurn() {
        assertEquals("source-2", MemoryHeadUpdatePolicy.baselineSourceTurnId(stableIds))
    }

    @Test
    fun initializationUsesOpeningAndFirstRoundWhenThirdRoundStarts() {
        val plan = MemoryHeadUpdatePolicy.initialize(stableIds.take(3))

        assertEquals(MemoryHeadUpdateMode.INITIALIZE, plan?.mode)
        assertEquals("source-1", plan?.targetSourceTurnId)
        assertEquals(listOf("source-0", "source-1"), plan?.inputSourceTurnIds)
    }

    @Test
    fun initializationWaitsUntilThreeStableGroupsExist() {
        assertNull(MemoryHeadUpdatePolicy.initialize(stableIds.take(2)))
    }

    @Test
    fun rollingUpdateConsumesExactlyNextBaselineGroup() {
        val plan = MemoryHeadUpdatePolicy.update("source-1", stableIds)

        assertEquals(MemoryHeadUpdateMode.UPDATE, plan?.mode)
        assertEquals("source-2", plan?.targetSourceTurnId)
        assertEquals(listOf("source-2"), plan?.inputSourceTurnIds)
    }

    @Test
    fun rollingUpdateRefusesToSkipMultipleGroups() {
        assertNull(MemoryHeadUpdatePolicy.update("source-0", stableIds))
        assertEquals(
            MemoryHeadMaintenanceState.REBUILD,
            MemoryHeadUpdatePolicy.maintenanceState(
                hasHeadContent = true,
                throughSourceTurnId = "source-0",
                stableSourceTurnIds = stableIds,
                hasHistoricalMemory = false
            )
        )
    }

    @Test
    fun expectedNewChatEmptyHeadDoesNotRequestBackfill() {
        assertEquals(
            MemoryHeadMaintenanceState.ROLL_FORWARD,
            MemoryHeadUpdatePolicy.maintenanceState(
                hasHeadContent = false,
                throughSourceTurnId = null,
                stableSourceTurnIds = stableIds.take(3),
                hasHistoricalMemory = false
            )
        )
    }

    @Test
    fun historicalEmptyHeadRequestsBackfill() {
        assertEquals(
            MemoryHeadMaintenanceState.REBUILD,
            MemoryHeadUpdatePolicy.maintenanceState(
                hasHeadContent = false,
                throughSourceTurnId = null,
                stableSourceTurnIds = stableIds,
                hasHistoricalMemory = false
            )
        )
    }

    @Test
    fun maintenanceStateSeparatesNormalLagFromUnavailableSource() {
        assertEquals(
            MemoryHeadMaintenanceState.CURRENT,
            MemoryHeadUpdatePolicy.maintenanceState(true, "source-2", stableIds, true)
        )
        assertEquals(
            MemoryHeadMaintenanceState.ROLL_FORWARD,
            MemoryHeadUpdatePolicy.maintenanceState(true, "source-1", stableIds, true)
        )
        assertEquals(
            MemoryHeadMaintenanceState.REBUILD,
            MemoryHeadUpdatePolicy.maintenanceState(true, "source-0", stableIds, true)
        )
        assertEquals(
            MemoryHeadMaintenanceState.REBUILD,
            MemoryHeadUpdatePolicy.maintenanceState(
                true,
                "source-1",
                stableIds,
                true,
                pathCrossesGap = true
            )
        )
        assertEquals(
            MemoryHeadMaintenanceState.SOURCE_UNAVAILABLE,
            MemoryHeadUpdatePolicy.maintenanceState(
                true,
                "source-1",
                stableIds,
                true,
                sourceAvailable = false
            )
        )
    }

    @Test
    fun blankHeadWaitsSilentlyUntilInitializationEvidenceExists() {
        assertEquals(
            MemoryHeadMaintenanceState.WAITING_FOR_INITIALIZATION,
            MemoryHeadUpdatePolicy.maintenanceState(false, null, stableIds.take(2), false)
        )
        assertEquals(
            MemoryHeadMaintenanceState.ROLL_FORWARD,
            MemoryHeadUpdatePolicy.maintenanceState(false, null, stableIds.take(3), false)
        )
    }

    @Test
    fun lagDoesNotAffectInjectionButUnavailableSourceDoes() {
        assertEquals(
            MemoryHeadMaintenanceState.REBUILD,
            MemoryHeadUpdatePolicy.maintenanceState(true, "source-0", stableIds, true)
        )
        assertTrue(MemoryHeadUpdatePolicy.canInject(true, sourceAvailable = true))
        assertFalse(MemoryHeadUpdatePolicy.canInject(true, sourceAvailable = false))
        assertFalse(MemoryHeadUpdatePolicy.canInject(false, sourceAvailable = true))
    }
}
