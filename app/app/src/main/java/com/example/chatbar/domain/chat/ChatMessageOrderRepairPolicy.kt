package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MESSAGE_ORDER_STEP
import com.example.chatbar.data.local.entity.MessageRole

data class ChatMessageOrderSnapshot(
    val messageId: String,
    val orderKey: Long,
    val updatedAt: Long
)

data class ChatMessageOrderMove(
    val messageId: String,
    val fromIndex: Int,
    val toIndex: Int
)

data class ChatMessageOrderRepairPlan(
    val baseline: List<ChatMessageOrderSnapshot>,
    val repairedMessages: List<ChatMessage>,
    val moves: List<ChatMessageOrderMove>,
    val anchoredMessageCount: Int,
    val orphanedAnchorMessageIds: List<String>,
    val cyclicAnchorMessageIds: List<String>
) {
    val requiresRepair: Boolean
        get() = moves.isNotEmpty()
}

object ChatMessageOrderRepairPolicy {
    fun plan(messages: List<ChatMessage>): ChatMessageOrderRepairPlan {
        val current = messages.sortedWith(ChatMessage.TimelineComparator)
        val byId = current.associateBy(ChatMessage::id)
        val rawParentByChild = current.mapNotNull { message ->
            val parentId = message.generatedFromMessageId
                ?.takeIf { it != message.id && it in byId }
                ?: return@mapNotNull null
            message.id to parentId
        }.toMap()
        val cyclic = findCyclicAnchorMessageIds(
            rawParentByChild,
            current.filter { it.generatedFromMessageId == it.id }.map(ChatMessage::id)
        )
        val parentByChild = rawParentByChild.filterKeys { it !in cyclic }
        val orphaned = current
            .filter { message ->
                val parentId = message.generatedFromMessageId
                parentId != null && parentId !in byId
            }
            .map(ChatMessage::id)
        val childrenByParent = parentByChild.entries
            .groupBy({ it.value }, { byId.getValue(it.key) })
            .mapValues { (_, children) -> children.sortedWith(childComparator) }
        val roots = current.filter { it.id !in parentByChild }
        val units = buildRootUnits(roots)
        val ordered = mutableListOf<ChatMessage>()
        val visited = mutableSetOf<String>()
        val visiting = mutableSetOf<String>()

        fun appendTree(message: ChatMessage) {
            if (message.id in visited) return
            if (!visiting.add(message.id)) return
            ordered += message
            visited += message.id
            childrenByParent[message.id].orEmpty().forEach { child ->
                appendTree(child)
            }
            visiting -= message.id
        }

        units.forEach { unit -> unit.roots.forEach(::appendTree) }
        current.filterNot { it.id in visited }
            .sortedWith(rootComparator)
            .forEach(::appendTree)

        val currentIds = current.map(ChatMessage::id)
        val repairedIds = ordered.map(ChatMessage::id)
        val repaired = if (currentIds == repairedIds) {
            current
        } else {
            ordered.mapIndexed { index, message ->
                message.copy(orderKey = (index + 1L) * MESSAGE_ORDER_STEP)
            }
        }
        val oldIndexById = currentIds.withIndex().associate { it.value to it.index }
        val moves = repairedIds.mapIndexedNotNull { newIndex, messageId ->
            val oldIndex = oldIndexById.getValue(messageId)
            ChatMessageOrderMove(messageId, oldIndex, newIndex).takeIf { oldIndex != newIndex }
        }

        return ChatMessageOrderRepairPlan(
            baseline = current.map { ChatMessageOrderSnapshot(it.id, it.orderKey, it.updatedAt) },
            repairedMessages = repaired,
            moves = moves,
            anchoredMessageCount = parentByChild.size,
            orphanedAnchorMessageIds = orphaned,
            cyclicAnchorMessageIds = cyclic.sorted()
        )
    }

    private fun findCyclicAnchorMessageIds(
        parentByChild: Map<String, String>,
        selfAnchoredIds: List<String>
    ): Set<String> {
        val cyclic = selfAnchoredIds.toMutableSet()
        parentByChild.keys.forEach { start ->
            val path = mutableListOf<String>()
            val pathIndex = mutableMapOf<String, Int>()
            var current: String? = start
            while (current != null && current !in cyclic) {
                val existingIndex = pathIndex[current]
                if (existingIndex != null) {
                    cyclic += path.drop(existingIndex)
                    break
                }
                pathIndex[current] = path.size
                path += current
                current = parentByChild[current]
            }
        }
        return cyclic
    }

    private data class RootUnit(
        val roots: List<ChatMessage>,
        val sourceOrder: Long?,
        val rawCreatedAt: Long,
        val effectiveCreatedAt: Long = rawCreatedAt,
        val stableId: String
    )

    private fun buildRootUnits(roots: List<ChatMessage>): List<RootUnit> {
        val grouped = roots.groupBy { message ->
            when {
                message.role == MessageRole.SYSTEM -> "system:${message.id}"
                message.sourceTurnId != null -> "source:${message.sourceTurnId}"
                message.timelineTurn != null -> "legacy:${message.timelineTurn}"
                else -> "message:${message.id}"
            }
        }.map { (stableId, messages) ->
            RootUnit(
                roots = messages.sortedWith(rootComparator),
                sourceOrder = messages.mapNotNull { it.sourceTurnOrder ?: it.timelineTurn }.minOrNull(),
                rawCreatedAt = messages.minOf(ChatMessage::createdAt),
                stableId = stableId
            )
        }
        val adjustedById = mutableMapOf<String, RootUnit>()
        var previousEffective = Long.MIN_VALUE
        grouped.filter { it.sourceOrder != null }
            .sortedWith(compareBy<RootUnit> { it.sourceOrder }.thenBy { it.rawCreatedAt }.thenBy { it.stableId })
            .forEach { unit ->
                val minimum = if (previousEffective == Long.MAX_VALUE) Long.MAX_VALUE else previousEffective + 1
                val effective = maxOf(unit.rawCreatedAt, minimum)
                adjustedById[unit.stableId] = unit.copy(effectiveCreatedAt = effective)
                previousEffective = effective
            }
        return grouped.map { adjustedById[it.stableId] ?: it }
            .sortedWith(
                compareBy<RootUnit> { it.effectiveCreatedAt }
                    .thenBy { it.sourceOrder ?: Long.MAX_VALUE }
                    .thenBy { it.stableId }
            )
    }

    private val rootComparator = compareBy<ChatMessage> { it.createdAt }
        .thenBy { roleRank(it.role) }
        .thenBy { it.id }

    private val childComparator = compareBy<ChatMessage> { it.createdAt }
        .thenBy { it.id }

    private fun roleRank(role: MessageRole): Int = when (role) {
        MessageRole.USER -> 0
        MessageRole.ASSISTANT -> 1
        MessageRole.SYSTEM -> 2
    }
}
