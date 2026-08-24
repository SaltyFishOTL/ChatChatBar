package com.example.chatbar.domain.memory

enum class MemoryHeadUpdateMode {
    INITIALIZE,
    BACKFILL,
    UPDATE
}

enum class MemoryHeadMaintenanceState {
    WAITING_FOR_INITIALIZATION,
    CURRENT,
    ROLL_FORWARD,
    REBUILD,
    SOURCE_UNAVAILABLE
}

data class MemoryHeadUpdatePlan(
    val mode: MemoryHeadUpdateMode,
    val targetSourceTurnId: String,
    val inputSourceTurnIds: List<String>
)

object MemoryHeadUpdatePolicy {
    fun canInject(hasHeadContent: Boolean, sourceAvailable: Boolean): Boolean =
        hasHeadContent && sourceAvailable

    /**
     * Stable groups include the complete previous turn kept raw at prompt tail.
     * HEAD therefore targets the group immediately before it.
     */
    fun baselineSourceTurnId(stableSourceTurnIds: List<String>): String? =
        stableSourceTurnIds.getOrNull(stableSourceTurnIds.lastIndex - 1)

    /** T0 opening + T1 first complete conversation become initial HEAD when T3 starts. */
    fun initialize(stableSourceTurnIds: List<String>): MemoryHeadUpdatePlan? {
        if (stableSourceTurnIds.size < 3) return null
        val inputs = stableSourceTurnIds.take(2)
        return MemoryHeadUpdatePlan(
            mode = MemoryHeadUpdateMode.INITIALIZE,
            targetSourceTurnId = inputs.last(),
            inputSourceTurnIds = inputs
        )
    }

    /** Backfill reads compiled Archive plus latest eligible baseline group only. */
    fun backfill(stableSourceTurnIds: List<String>): MemoryHeadUpdatePlan? {
        val target = baselineSourceTurnId(stableSourceTurnIds) ?: return null
        return MemoryHeadUpdatePlan(
            mode = MemoryHeadUpdateMode.BACKFILL,
            targetSourceTurnId = target,
            inputSourceTurnIds = listOf(target)
        )
    }

    /** Normal rolling update advances exactly one baseline group. */
    fun update(
        throughSourceTurnId: String?,
        stableSourceTurnIds: List<String>
    ): MemoryHeadUpdatePlan? {
        val targetIndex = stableSourceTurnIds.lastIndex - 1
        if (targetIndex < 0) return null
        val previousIndex = stableSourceTurnIds.indexOf(throughSourceTurnId)
        if (previousIndex < 0 || targetIndex != previousIndex + 1) return null
        val target = stableSourceTurnIds[targetIndex]
        return MemoryHeadUpdatePlan(
            mode = MemoryHeadUpdateMode.UPDATE,
            targetSourceTurnId = target,
            inputSourceTurnIds = listOf(target)
        )
    }

    fun isUpToDate(
        throughSourceTurnId: String?,
        stableSourceTurnIds: List<String>
    ): Boolean {
        val targetIndex = stableSourceTurnIds.lastIndex - 1
        if (targetIndex < 0) return true
        val previousIndex = stableSourceTurnIds.indexOf(throughSourceTurnId)
        return previousIndex >= targetIndex
    }

    fun maintenanceState(
        hasHeadContent: Boolean,
        throughSourceTurnId: String?,
        stableSourceTurnIds: List<String>,
        hasHistoricalMemory: Boolean,
        sourceAvailable: Boolean = true,
        pathCrossesGap: Boolean = false
    ): MemoryHeadMaintenanceState {
        if (!hasHeadContent && stableSourceTurnIds.size < 3) {
            return MemoryHeadMaintenanceState.WAITING_FOR_INITIALIZATION
        }
        if (!sourceAvailable) return MemoryHeadMaintenanceState.SOURCE_UNAVAILABLE
        val targetIndex = stableSourceTurnIds.lastIndex - 1
        if (targetIndex < 0) {
            return if (hasHeadContent) {
                MemoryHeadMaintenanceState.CURRENT
            } else {
                MemoryHeadMaintenanceState.WAITING_FOR_INITIALIZATION
            }
        }
        if (!hasHeadContent) {
            return if (stableSourceTurnIds.size == 3 && !hasHistoricalMemory) {
                MemoryHeadMaintenanceState.ROLL_FORWARD
            } else {
                MemoryHeadMaintenanceState.REBUILD
            }
        }
        val previousIndex = stableSourceTurnIds.indexOf(throughSourceTurnId)
        if (previousIndex < 0 || pathCrossesGap) return MemoryHeadMaintenanceState.REBUILD
        val lag = targetIndex - previousIndex
        return when {
            lag <= 0 -> MemoryHeadMaintenanceState.CURRENT
            lag == 1 -> MemoryHeadMaintenanceState.ROLL_FORWARD
            else -> MemoryHeadMaintenanceState.REBUILD
        }
    }

}
