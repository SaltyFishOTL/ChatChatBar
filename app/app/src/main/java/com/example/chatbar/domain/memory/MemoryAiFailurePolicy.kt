package com.example.chatbar.domain.memory

import com.example.chatbar.data.local.entity.MemoryFailureArea
import com.example.chatbar.data.local.entity.MemoryFailureCategory
import com.example.chatbar.data.local.entity.MemoryFailureInfo
import com.example.chatbar.data.local.entity.MemoryFailureStage
import com.example.chatbar.domain.chat.ModelRequestException
import com.example.chatbar.domain.chat.ModelResponseTruncatedException
import kotlinx.serialization.SerializationException

internal fun memoryFailureInfo(area: MemoryFailureArea, error: Throwable): MemoryFailureInfo {
    val retryError = error as? MemoryAiRetryException
    val rootError = retryError?.lastFailure ?: error
    val modelError = rootError as? ModelRequestException
    val category = when {
        rootError is ModelResponseTruncatedException -> MemoryFailureCategory.TRUNCATED
        modelError?.isAuthenticationFailure == true -> MemoryFailureCategory.AUTH
        modelError?.httpStatus != null -> MemoryFailureCategory.HTTP
        modelError != null -> MemoryFailureCategory.NETWORK
        rootError is SerializationException -> MemoryFailureCategory.JSON
        rootError is IllegalStateException -> MemoryFailureCategory.BUSINESS
        else -> MemoryFailureCategory.UNKNOWN
    }
    val stage = when {
        retryError?.taskStage == MemoryAiTaskStage.COMPRESSION_PLANNING -> MemoryFailureStage.RESPONSE
        category == MemoryFailureCategory.TRUNCATED -> MemoryFailureStage.RESPONSE
        category == MemoryFailureCategory.JSON || category == MemoryFailureCategory.BUSINESS ->
            MemoryFailureStage.VALIDATION
        retryError?.failureKind == MemoryAiFailureKind.OUTPUT -> MemoryFailureStage.RESPONSE
        else -> MemoryFailureStage.REQUEST
    }
    return MemoryFailureInfo(
        area = area,
        stage = stage,
        category = category,
        message = error.message ?: error::class.simpleName.orEmpty(),
        automaticallyRetryable = modelError?.isRetryable == true,
        httpStatus = modelError?.httpStatus,
        attemptCount = retryError?.attemptCount ?: 1,
        traceId = modelError?.traceId
    )
}
