package com.example.chatbar.domain.memory

import com.example.chatbar.data.local.entity.DEFAULT_MEMORY_LIMIT_CHARS
import com.example.chatbar.data.local.entity.MAX_MEMORY_LIMIT_CHARS
import com.example.chatbar.data.local.entity.MEMORY_LIMIT_STEP_CHARS
import com.example.chatbar.data.local.entity.MemoryNode
import com.example.chatbar.data.local.entity.MemorySessionState

object MemoryBudgetPolicy {
    fun archiveChars(state: MemorySessionState, nodesById: Map<String, MemoryNode>): Int =
        state.activeNodeIds.mapNotNull(nodesById::get).sumOf { it.body.length }

    fun normalizedLimit(value: Int): Int = value.coerceIn(
        DEFAULT_MEMORY_LIMIT_CHARS,
        MAX_MEMORY_LIMIT_CHARS
    )

    fun canIncrease(value: Int): Boolean = normalizedLimit(value) < MAX_MEMORY_LIMIT_CHARS

    fun increase(value: Int): Int = (normalizedLimit(value) + MEMORY_LIMIT_STEP_CHARS)
        .coerceAtMost(MAX_MEMORY_LIMIT_CHARS)

    fun isOverLimit(
        state: MemorySessionState,
        nodesById: Map<String, MemoryNode>,
        limitChars: Int
    ): Boolean = archiveChars(state, nodesById) > normalizedLimit(limitChars)
}
