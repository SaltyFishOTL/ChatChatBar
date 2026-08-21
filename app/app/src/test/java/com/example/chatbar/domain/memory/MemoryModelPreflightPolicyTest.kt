package com.example.chatbar.domain.memory

import com.example.chatbar.data.local.entity.MemoryBackfillState
import com.example.chatbar.data.local.entity.MemoryBackfillStatus
import com.example.chatbar.data.local.entity.MemoryFailureArea
import com.example.chatbar.data.local.entity.MemoryFailureCategory
import com.example.chatbar.data.local.entity.MemoryFailureInfo
import com.example.chatbar.data.local.entity.MemoryFailureStage
import com.example.chatbar.data.local.entity.MemorySessionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryModelPreflightPolicyTest {
    @Test fun resolvedModelConfigurationClearsOnlyMatchingFailures() {
        val modelFailure = modelFailure()
        val state = MemorySessionState(
            sessionId = "session",
            archiveFailure = modelFailure,
            headFailure = modelFailure,
            backfill = MemoryBackfillState(
                status = MemoryBackfillStatus.ERROR,
                error = MEMORY_MODEL_CONFIGURATION_ERROR
            ),
            revision = 4L
        )

        val result = MemoryModelPreflightPolicy.clearResolvedConfigurationErrors(state, now = 10L)

        assertTrue(result.changed)
        assertTrue(result.archiveCleared)
        assertTrue(result.headCleared)
        assertTrue(result.backfillCleared)
        assertNull(result.state.archiveFailure)
        assertNull(result.state.headFailure)
        assertEquals(MemoryBackfillStatus.IDLE, result.state.backfill.status)
        assertNull(result.state.backfill.error)
        assertEquals(5L, result.state.revision)
        assertEquals(10L, result.state.updatedAt)
    }

    @Test fun successfulModelPreflightPreservesUnrelatedFailures() {
        val requestFailure = MemoryFailureInfo(
            area = MemoryFailureArea.ARCHIVE,
            stage = MemoryFailureStage.REQUEST,
            category = MemoryFailureCategory.AUTH,
            message = MEMORY_MODEL_CONFIGURATION_ERROR
        )
        val state = MemorySessionState(
            sessionId = "session",
            archiveFailure = requestFailure,
            backfill = MemoryBackfillState(
                status = MemoryBackfillStatus.ERROR,
                error = "Episode输出校验失败"
            ),
            revision = 4L
        )

        val result = MemoryModelPreflightPolicy.clearResolvedConfigurationErrors(state, now = 10L)

        assertFalse(result.changed)
        assertEquals(state, result.state)
    }

    private fun modelFailure() = MemoryFailureInfo(
        area = MemoryFailureArea.ARCHIVE,
        stage = MemoryFailureStage.PREFLIGHT,
        category = MemoryFailureCategory.AUTH,
        message = MEMORY_MODEL_CONFIGURATION_ERROR
    )
}
