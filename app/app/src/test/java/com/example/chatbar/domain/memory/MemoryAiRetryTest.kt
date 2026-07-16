package com.example.chatbar.domain.memory

import java.util.concurrent.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MemoryAiRetryTest {
    @Test
    fun memoryAiRetriesFiveInvalidOutputsBeforeFailing() = runBlocking {
        var attempts = 0

        val failure = try {
            retryMemoryAiOutput(MEMORY_AI_MAX_ATTEMPTS) { _, _ ->
                attempts++
                error("非法摘要")
            }
            throw AssertionError("Expected retry exhaustion")
        } catch (error: IllegalStateException) {
            error
        }

        assertEquals(5, attempts)
        assertEquals("长期记忆AI输出连续5次非法：非法摘要", failure.message)
    }

    @Test
    fun succeedsWithoutUsingRemainingAttempts() = runBlocking {
        var attempts = 0

        val result = retryMemoryAiOutput(MEMORY_AI_MAX_ATTEMPTS) { _, _ ->
            attempts++
            if (attempts < 3) error("暂时非法")
            "有效摘要"
        }

        assertEquals("有效摘要", result)
        assertEquals(3, attempts)
    }

    @Test
    fun cancellationIsNotRetried() = runBlocking {
        var attempts = 0
        val cancellation = CancellationException("用户暂停")

        val thrown = try {
            retryMemoryAiOutput(MEMORY_AI_MAX_ATTEMPTS) { _, _ ->
                attempts++
                throw cancellation
            }
            throw AssertionError("Expected cancellation")
        } catch (error: CancellationException) {
            error
        }

        assertSame(cancellation, thrown)
        assertEquals(1, attempts)
    }
}
