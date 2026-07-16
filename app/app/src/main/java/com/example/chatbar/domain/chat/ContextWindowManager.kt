package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole

data class ChatContextGroup(
    val messages: List<ChatMessage>,
    val userMessage: ChatMessage? = null,
    val assistantMessage: ChatMessage? = null
) {
    val isCompleteTurn: Boolean
        get() = userMessage != null && assistantMessage != null
}

/** RAG等独立功能使用的旧相邻问答配对；不参与上下文窗口的T计数。 */
object ChatAdjacentExchangeGroupPolicy {
    fun groups(messages: List<ChatMessage>): List<ChatContextGroup> {
        val groups = mutableListOf<ChatContextGroup>()
        var pendingUser: ChatMessage? = null

        fun flushPendingUser() {
            pendingUser?.let { user ->
                groups += ChatContextGroup(messages = listOf(user), userMessage = user)
            }
            pendingUser = null
        }

        messages.forEach { message ->
            when (message.role) {
                MessageRole.USER -> {
                    flushPendingUser()
                    pendingUser = message
                }
                MessageRole.ASSISTANT -> {
                    val user = pendingUser
                    if (user == null) {
                        groups += ChatContextGroup(
                            messages = listOf(message),
                            assistantMessage = message
                        )
                    } else {
                        groups += ChatContextGroup(
                            messages = listOf(user, message),
                            userMessage = user,
                            assistantMessage = message
                        )
                        pendingUser = null
                    }
                }
                MessageRole.SYSTEM -> {
                    flushPendingUser()
                    groups += ChatContextGroup(messages = listOf(message))
                }
            }
        }
        flushPendingUser()
        return groups
    }
}

/**
 * 直接上下文按稳定source turn分组：同一T内的回复、追加回复和衍生消息只占一组。
 * 尚未迁移sourceTurnId的旧消息继续使用相邻USER/ASSISTANT兼容算法。
 */
object ChatContextGroupPolicy {
    private data class Partition(
        val countedGroups: List<ChatContextGroup>,
        val previousGroup: ChatContextGroup?,
        val currentGroup: ChatContextGroup?
    )

    fun groups(messages: List<ChatMessage>): List<ChatContextGroup> {
        val groups = mutableListOf<ChatContextGroup>()
        var index = 0
        while (index < messages.size) {
            val message = messages[index]
            val sourceTurnId = if (message.role == MessageRole.SYSTEM) {
                messages.asSequence()
                    .drop(index + 1)
                    .firstOrNull { it.role != MessageRole.SYSTEM }
                    ?.sourceTurnId
            } else {
                message.sourceTurnId
            }
            if (sourceTurnId != null) {
                val start = index
                index++
                while (index < messages.size) {
                    val next = messages[index]
                    if (next.role == MessageRole.SYSTEM || next.sourceTurnId == sourceTurnId) {
                        index++
                    } else {
                        break
                    }
                }
                val turnMessages = messages.subList(start, index)
                groups += ChatContextGroup(
                    messages = turnMessages,
                    userMessage = turnMessages.firstOrNull { it.role == MessageRole.USER },
                    assistantMessage = turnMessages.lastOrNull { it.role == MessageRole.ASSISTANT }
                )
            } else {
                val start = index
                index++
                while (
                    index < messages.size &&
                    (messages[index].role == MessageRole.SYSTEM || messages[index].sourceTurnId == null)
                ) {
                    index++
                }
                groups += ChatAdjacentExchangeGroupPolicy.groups(messages.subList(start, index))
            }
        }
        return groups
    }

    private fun partition(messages: List<ChatMessage>): Partition {
        val groups = groups(messages)
        if (groups.isEmpty()) return Partition(emptyList(), null, null)
        val currentGroup = groups.lastOrNull()
            ?.takeIf { it.userMessage != null && it.assistantMessage == null }
        val withoutCurrent = if (currentGroup == null) groups else groups.dropLast(1)
        val previousGroup = withoutCurrent.lastOrNull()
        return Partition(
            countedGroups = if (previousGroup == null) withoutCurrent else withoutCurrent.dropLast(1),
            previousGroup = previousGroup,
            currentGroup = currentGroup
        )
    }

    fun recentMessages(messages: List<ChatMessage>, groupLimit: Int): List<ChatMessage> {
        val partition = partition(messages)
        return buildList {
            partition.countedGroups.takeLast(groupLimit.coerceAtLeast(1)).forEach { addAll(it.messages) }
            partition.previousGroup?.let { addAll(it.messages) }
            partition.currentGroup?.let { addAll(it.messages) }
        }
    }

    fun archivedMessages(messages: List<ChatMessage>, groupLimit: Int): List<ChatMessage> =
        partition(messages).countedGroups
            .dropLast(groupLimit.coerceAtLeast(1))
            .flatMap(ChatContextGroup::messages)

    fun count(messages: List<ChatMessage>): Int = partition(messages).countedGroups.size
}

/**
 * 上下文窗口管理器 — 控制发送给 LLM 的消息范围
 *
 * 职责：
 * - 从全部消息中截取最近 N 组作为上下文
 * - 识别需要归档（向量化）的旧消息
 * - 判断是否需要刷新系统提示词
 */
class ContextWindowManager {
    data class PromptMessageGroups(
        val historyMessages: List<ChatMessage>,
        val previousTurnMessages: List<ChatMessage>
    )

    /**
     * 获取最近的消息（发送给 LLM 的上下文）
     *
     * @param allMessages 全部消息列表（按时间正序）
     * @param windowSize  上下文窗口大小（消息组数）
     * @return 最近 [windowSize] 组内的全部消息
     */
    fun getRecentMessages(
        allMessages: List<ChatMessage>,
        windowSize: Int
    ): List<ChatMessage> {
        return ChatContextGroupPolicy.recentMessages(allMessages, windowSize)
    }

    fun getPromptMessageGroups(
        contextMessages: List<ChatMessage>,
        latestMessageId: String?
    ): PromptMessageGroups {
        val latestIndex = latestMessageId
            ?.let { id -> contextMessages.indexOfLast { it.id == id } }
            ?.takeIf { it >= 0 }
        val messagesBeforeLatest = if (latestIndex == null) {
            contextMessages
        } else {
            contextMessages.take(latestIndex)
        }
        val previousTurnMessages = ChatContextGroupPolicy.groups(messagesBeforeLatest)
            .lastOrNull()
            ?.messages
            .orEmpty()
        val excludedIds = mutableSetOf<String>()
        latestMessageId?.let { excludedIds.add(it) }
        previousTurnMessages.forEach { excludedIds.add(it.id) }

        return PromptMessageGroups(
            historyMessages = contextMessages.filterNot { it.id in excludedIds },
            previousTurnMessages = previousTurnMessages
        )
    }

    /**
     * 获取需要归档的消息（已滑出上下文窗口的旧消息）
     *
     * 这些消息可以被向量化存入 RAG 作为长期记忆。
     *
     * @param allMessages 全部消息列表（按时间正序）
     * @param windowSize  上下文窗口大小
     * @return 滑出窗口的旧消息列表
     */
    fun getMessagesToArchive(
        allMessages: List<ChatMessage>,
        windowSize: Int
    ): List<ChatMessage> {
        return ChatContextGroupPolicy.archivedMessages(allMessages, windowSize)
    }

    /**
     * 判断是否需要刷新系统提示词
     *
     * 当消息组数超过窗口大小时，说明有旧消息被裁剪，
     * 此时应该用 RAG 重新组装系统提示词以包含相关记忆。
     *
     * @param allMessages 当前全部消息
     * @param windowSize   上下文窗口大小
     * @return 是否需要刷新
     */
    fun shouldRefreshSystemPrompt(
        allMessages: List<ChatMessage>,
        windowSize: Int
    ): Boolean {
        return ChatContextGroupPolicy.count(allMessages) > windowSize.coerceAtLeast(1)
    }

    fun messageGroupCount(messages: List<ChatMessage>): Int =
        ChatContextGroupPolicy.count(messages)
}
