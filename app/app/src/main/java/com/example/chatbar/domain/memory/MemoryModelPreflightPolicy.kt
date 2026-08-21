package com.example.chatbar.domain.memory

import com.example.chatbar.data.local.entity.MemoryBackfillStatus
import com.example.chatbar.data.local.entity.MemoryFailureInfo
import com.example.chatbar.data.local.entity.MemoryFailureStage
import com.example.chatbar.data.local.entity.MemorySessionState

internal const val MEMORY_MODEL_CONFIGURATION_ERROR = "对话模型未配置或缺少鉴权"

internal data class MemoryModelPreflightClearResult(
    val state: MemorySessionState,
    val archiveCleared: Boolean,
    val headCleared: Boolean,
    val backfillCleared: Boolean
) {
    val changed: Boolean
        get() = archiveCleared || headCleared || backfillCleared
}

internal object MemoryModelPreflightPolicy {
    fun clearResolvedConfigurationErrors(
        state: MemorySessionState,
        now: Long = System.currentTimeMillis()
    ): MemoryModelPreflightClearResult {
        val archiveCleared = state.archiveFailure.isModelConfigurationError()
        val headCleared = state.headFailure.isModelConfigurationError()
        val backfillCleared =
            state.backfill.status == MemoryBackfillStatus.ERROR &&
                state.backfill.error == MEMORY_MODEL_CONFIGURATION_ERROR
        if (!archiveCleared && !headCleared && !backfillCleared) {
            return MemoryModelPreflightClearResult(state, false, false, false)
        }
        return MemoryModelPreflightClearResult(
            state = state.copy(
                archiveFailure = state.archiveFailure.takeUnless { archiveCleared },
                headFailure = state.headFailure.takeUnless { headCleared },
                backfill = if (backfillCleared) {
                    state.backfill.copy(
                        status = MemoryBackfillStatus.IDLE,
                        error = null,
                        updatedAt = now
                    )
                } else {
                    state.backfill
                },
                revision = state.revision + 1,
                updatedAt = now
            ),
            archiveCleared = archiveCleared,
            headCleared = headCleared,
            backfillCleared = backfillCleared
        )
    }

    private fun MemoryFailureInfo?.isModelConfigurationError(): Boolean =
        this != null && stage == MemoryFailureStage.PREFLIGHT &&
            message == MEMORY_MODEL_CONFIGURATION_ERROR
}
