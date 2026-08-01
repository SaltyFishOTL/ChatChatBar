package com.example.chatbar.domain.memory

import com.example.chatbar.data.local.entity.MemoryFailureArea
import com.example.chatbar.data.local.entity.MemoryFailureCategory
import com.example.chatbar.data.local.entity.MemoryFailureStage
import com.example.chatbar.domain.chat.ModelResponseTruncatedException
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryAiFailurePolicyTest {
    @Test
    fun plannerFailurePersistsStageAndAttemptCount() {
        val error = MemoryAiRetryException(
            taskStage = MemoryAiTaskStage.COMPRESSION_PLANNING,
            failureKind = MemoryAiFailureKind.OUTPUT,
            attemptCount = 5,
            lastFailure = ModelResponseTruncatedException()
        )

        val failure = memoryFailureInfo(MemoryFailureArea.ARCHIVE, error)

        assertEquals(MemoryFailureStage.RESPONSE, failure.stage)
        assertEquals(MemoryFailureCategory.TRUNCATED, failure.category)
        assertEquals(5, failure.attemptCount)
        assertEquals(error.message, failure.message)
    }
}
