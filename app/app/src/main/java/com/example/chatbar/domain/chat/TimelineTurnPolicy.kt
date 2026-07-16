package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.SourceTurnTombstone

data class TimelineMigrationResult(
    val messages: List<ChatMessage>,
    val nextTimelineTurn: Long,
    val nextSourceTurnOrder: Long = nextTimelineTurn
)

data class SourceTurnAssignment(
    val sourceTurnId: String,
    val sourceTurnOrder: Long
)

/**
 * sourceTurnId是稳定身份；sourceTurnOrder是不可复用的绝对顺序；显示T由长期记忆状态派生。
 * 旧timelineTurn仅作为迁移输入保存，不能再被当作显示T引用。
 */
object TimelineTurnPolicy {
    fun migrate(
        messages: List<ChatMessage>,
        initialNextTurn: Long,
        initialNextSourceTurnOrder: Long = initialNextTurn
    ): TimelineMigrationResult {
        var nextOrder = maxOf(1, initialNextTurn, initialNextSourceTurnOrder)
        var currentId: String? = null
        var currentOrder: Long? = null
        var hasStoryMessage = false
        val assignmentByMessageId = mutableMapOf<String, SourceTurnAssignment>()
        val assignmentByLegacyTurn = mutableMapOf<Long, SourceTurnAssignment>()

        val migrated = messages.map { message ->
            if (message.role == MessageRole.SYSTEM) return@map message

            val existingOrder = message.sourceTurnOrder ?: message.timelineTurn
            val inherited = message.generatedFromMessageId
                ?.let(assignmentByMessageId::get)
                ?: existingOrder?.let(assignmentByLegacyTurn::get)
            val assignment = when {
                message.sourceTurnId != null && existingOrder != null -> SourceTurnAssignment(
                    sourceTurnId = message.sourceTurnId,
                    sourceTurnOrder = existingOrder
                )

                inherited != null -> inherited

                message.role == MessageRole.USER -> {
                    val order = existingOrder ?: if (!hasStoryMessage) 0 else nextOrder++
                    SourceTurnAssignment(
                        sourceTurnId = message.sourceTurnId ?: "legacy-turn:${message.id}",
                        sourceTurnOrder = order
                    )
                }

                currentId != null && currentOrder != null -> SourceTurnAssignment(currentId!!, currentOrder!!)

                else -> {
                    val order = existingOrder ?: if (!hasStoryMessage) 0 else nextOrder++
                    SourceTurnAssignment(
                        sourceTurnId = message.sourceTurnId ?: "legacy-turn:${message.id}",
                        sourceTurnOrder = order
                    )
                }
            }

            currentId = assignment.sourceTurnId
            currentOrder = assignment.sourceTurnOrder
            hasStoryMessage = true
            assignmentByMessageId[message.id] = assignment
            message.timelineTurn?.let { assignmentByLegacyTurn.putIfAbsent(it, assignment) }
            nextOrder = maxOf(nextOrder, assignment.sourceTurnOrder + 1)

            if (
                message.sourceTurnId == assignment.sourceTurnId &&
                message.sourceTurnOrder == assignment.sourceTurnOrder
            ) {
                message
            } else {
                message.copy(
                    sourceTurnId = assignment.sourceTurnId,
                    sourceTurnOrder = assignment.sourceTurnOrder,
                    timelineTurn = message.timelineTurn ?: assignment.sourceTurnOrder
                )
            }
        }

        return TimelineMigrationResult(
            messages = migrated,
            nextTimelineTurn = nextOrder,
            nextSourceTurnOrder = nextOrder
        )
    }

    fun nextForAppend(
        message: ChatMessage,
        existingMessages: List<ChatMessage>,
        nextSourceTurnOrder: Long,
        tombstones: List<SourceTurnTombstone>,
        newSourceTurnId: String
    ): SourceTurnAssignment? {
        if (message.role == MessageRole.SYSTEM) return null
        if (message.sourceTurnId != null && message.sourceTurnOrder != null) {
            return SourceTurnAssignment(message.sourceTurnId, message.sourceTurnOrder)
        }

        val anchor = message.generatedFromMessageId
            ?.let { sourceId -> existingMessages.firstOrNull { it.id == sourceId } }
        val inherited = anchor
            ?: if (message.role == MessageRole.ASSISTANT) {
                existingMessages.lastOrNull { it.role == MessageRole.USER }
                    ?: existingMessages.lastOrNull { it.role != MessageRole.SYSTEM }
            } else {
                null
            }
        val inheritedId = inherited?.sourceTurnId
        val inheritedOrder = inherited?.sourceTurnOrder
        if (inheritedId != null && inheritedOrder != null) {
            return SourceTurnAssignment(inheritedId, inheritedOrder)
        }

        val hasStory = existingMessages.any { it.role != MessageRole.SYSTEM }
        val order = if (!hasStory && tombstones.none { it.sourceOrder == 0L } && nextSourceTurnOrder <= 1L) {
            0L
        } else {
            maxOf(
                nextSourceTurnOrder,
                existingMessages.mapNotNull { it.sourceTurnOrder }.maxOrNull()?.plus(1) ?: 1L,
                tombstones.maxOfOrNull { it.sourceOrder }?.plus(1) ?: 1L
            )
        }
        return SourceTurnAssignment(newSourceTurnId, order)
    }

    /** v2草稿测试兼容；返回值是绝对source order，不是显示T。 */
    fun nextForAppend(
        message: ChatMessage,
        existingMessages: List<ChatMessage>,
        nextTimelineTurn: Long,
        tombstones: Set<Long>
    ): Long? = nextForAppend(
        message = message,
        existingMessages = existingMessages,
        nextSourceTurnOrder = nextTimelineTurn,
        tombstones = tombstones.map { SourceTurnTombstone("legacy-tombstone:$it", it) },
        newSourceTurnId = "legacy-turn:${message.id}"
    )?.sourceTurnOrder
}
