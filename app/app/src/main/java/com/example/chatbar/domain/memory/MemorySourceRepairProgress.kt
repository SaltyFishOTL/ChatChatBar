package com.example.chatbar.domain.memory

enum class MemorySourceRepairPhase {
    PREPARING,
    GENERATING_EPISODE,
    REBUILDING_PARENT,
    SAVING_ROOT,
    UPDATING_HEAD
}

/** 仅供当前进程展示；流式正文不写入会话文件或SaveSlot。 */
data class MemorySourceRepairProgress(
    val phase: MemorySourceRepairPhase,
    val totalRoots: Int,
    val completedRoots: Int,
    val currentRootNodeId: String? = null,
    val currentSourceTurnIds: List<String> = emptyList(),
    val currentRangeLabel: String = "",
    val streamingSummary: String = ""
) {
    val fraction: Float
        get() = if (totalRoots <= 0) {
            if (phase == MemorySourceRepairPhase.UPDATING_HEAD) 1f else 0f
        } else {
            (completedRoots.toFloat() / totalRoots).coerceIn(0f, 1f)
        }
}
