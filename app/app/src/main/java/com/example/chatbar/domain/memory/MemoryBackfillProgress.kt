package com.example.chatbar.domain.memory

enum class MemoryBackfillPhase {
    PREPARING,
    GENERATING_EPISODE,
    CHECKING_SPACE,
    SAVING_EPISODE,
    UPDATING_HEAD
}

/** 仅供当前进程展示；正文流不会写入会话文件或SaveSlot。 */
data class MemoryBackfillProgress(
    val phase: MemoryBackfillPhase,
    val totalSourceTurns: Int,
    val completedSourceTurns: Int,
    val completedEpisodes: Int,
    val currentBatchSourceTurnIds: List<String> = emptyList(),
    val currentRangeLabel: String = "",
    val streamingSummary: String = ""
) {
    val fraction: Float
        get() = if (totalSourceTurns <= 0) 0f else {
            (completedSourceTurns.toFloat() / totalSourceTurns).coerceIn(0f, 1f)
        }
}
