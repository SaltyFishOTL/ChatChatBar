package com.example.chatbar.domain.memory

import com.example.chatbar.domain.chat.ModelRequestException
import com.example.chatbar.domain.chat.ModelResponseTruncatedException
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
            retryMemoryAiOutput(
                MEMORY_AI_MAX_ATTEMPTS,
                MemoryAiTaskStage.COMPRESSION_SUMMARY
            ) { _, _ ->
                attempts++
                error("非法摘要")
            }
            throw AssertionError("Expected retry exhaustion")
        } catch (error: IllegalStateException) {
            error
        }

        assertEquals(5, attempts)
        assertEquals("正式压缩：输出连续5次失败；最后错误：非法摘要", failure.message)
    }

    @Test
    fun succeedsWithoutUsingRemainingAttempts() = runBlocking {
        var attempts = 0

        val result = retryMemoryAiOutput(
            MEMORY_AI_MAX_ATTEMPTS,
            MemoryAiTaskStage.EPISODE
        ) { _, _ ->
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
            retryMemoryAiOutput(
                MEMORY_AI_MAX_ATTEMPTS,
                MemoryAiTaskStage.HEAD
            ) { _, _ ->
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

    @Test
    fun compressionPlannerRetriesFiveTruncatedOutputs() = runBlocking {
        var attempts = 0

        val failure = try {
            retryMemoryAiOutput(
                MEMORY_AI_MAX_ATTEMPTS,
                MemoryAiTaskStage.COMPRESSION_PLANNING
            ) { _, _ ->
                attempts++
                throw ModelResponseTruncatedException()
            }
            throw AssertionError("Expected retry exhaustion")
        } catch (error: MemoryAiRetryException) {
            error
        }

        assertEquals(5, attempts)
        assertEquals(MemoryAiFailureKind.OUTPUT, failure.failureKind)
        assertEquals(5, failure.attemptCount)
        assertEquals(
            "压缩规划：输出连续5次失败；最后错误：模型输出因token上限截断",
            failure.message
        )
    }

    @Test
    fun nonRetryableRequestStopsImmediatelyWithStage() = runBlocking {
        var attempts = 0

        val failure = try {
            retryMemoryAiOutput(
                MEMORY_AI_MAX_ATTEMPTS,
                MemoryAiTaskStage.COMPRESSION_SUMMARY
            ) { _, _ ->
                attempts++
                throw ModelRequestException("参数错误", httpStatus = 400)
            }
            throw AssertionError("Expected request failure")
        } catch (error: MemoryAiRetryException) {
            error
        }

        assertEquals(1, attempts)
        assertEquals(MemoryAiFailureKind.NON_RETRYABLE_REQUEST, failure.failureKind)
        assertEquals("正式压缩：第1次请求失败且不可重试；错误：参数错误", failure.message)
    }

    @Test
    fun retryableTransportFailureStopsAfterThreeRequests() = runBlocking {
        var attempts = 0

        val failure = try {
            retryMemoryAiOutput(
                MEMORY_AI_MAX_ATTEMPTS,
                MemoryAiTaskStage.HEAD
            ) { _, _ ->
                attempts++
                throw ModelRequestException(
                    message = "服务繁忙",
                    httpStatus = 503,
                    retryAfterMillis = 0
                )
            }
            throw AssertionError("Expected transport failure")
        } catch (error: MemoryAiRetryException) {
            error
        }

        assertEquals(3, attempts)
        assertEquals(MemoryAiFailureKind.TRANSPORT, failure.failureKind)
        assertEquals(3, failure.attemptCount)
        assertEquals("HEAD生成：请求连续3次失败；最后错误：服务繁忙", failure.message)
    }

    @Test
    fun truncationTokenBudgetExpandsWithoutResetting() {
        val budget = MemoryOutputTokenBudget(initial = 1800, modelMaxOutputTokens = null)

        assertEquals(1800, budget.current)
        budget.expandAfterTruncation()
        assertEquals(3600, budget.current)
        budget.expandAfterTruncation()
        assertEquals(4096, budget.current)
        budget.expandAfterTruncation()
        assertEquals(4096, budget.current)
    }

    @Test
    fun truncationTokenBudgetHonorsConfiguredModelCap() {
        val budget = MemoryOutputTokenBudget(initial = 1800, modelMaxOutputTokens = 1000)

        assertEquals(1000, budget.current)
        budget.expandAfterTruncation()
        assertEquals(1000, budget.current)
    }
}
