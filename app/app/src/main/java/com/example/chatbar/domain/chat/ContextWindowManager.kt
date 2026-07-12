package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole

/** 会话上下文统一按用户轮次分组；典型一组为一条 USER 加其后续 ASSISTANT 回复。 */
object ChatContextGroupPolicy {
    private data class Partition(
        val countedGroups: List<List<ChatMessage>>,
        val trailingMessages: List<ChatMessage>,
        val promptTailMessages: List<ChatMessage>
    )

    private fun partition(messages: List<ChatMessage>): Partition {
        if (messages.isEmpty()) return Partition(emptyList(), emptyList(), emptyList())
        var candidateEnd = messages.size
        val promptTail = mutableListOf<ChatMessage>()
        if (messages.getOrNull(candidateEnd - 1)?.role == MessageRole.USER) {
            promptTail.add(0, messages[candidateEnd - 1])
            candidateEnd--
        }
        if (messages.getOrNull(candidateEnd - 1)?.role == MessageRole.ASSISTANT) {
            promptTail.add(0, messages[candidateEnd - 1])
            candidateEnd--
        }

        val groups = mutableListOf<List<ChatMessage>>()
        var current = mutableListOf<ChatMessage>()
        messages.take(candidateEnd).forEach { message ->
            if (message.role == MessageRole.USER && current.isNotEmpty()) {
                groups += current
                current = mutableListOf()
            }
            current += message
        }
        if (current.isNotEmpty()) groups += current
        val last = groups.lastOrNull().orEmpty()
        val lastIsIncompleteUserTurn = last.any { it.role == MessageRole.USER } &&
            last.none { it.role == MessageRole.ASSISTANT }
        return Partition(
            countedGroups = if (lastIsIncompleteUserTurn) groups.dropLast(1) else groups,
            trailingMessages = if (lastIsIncompleteUserTurn) last else emptyList(),
            promptTailMessages = promptTail
        )
    }

    fun groups(messages: List<ChatMessage>): List<List<ChatMessage>> =
        partition(messages).let { result ->
            buildList {
                addAll(result.countedGroups)
                if (result.trailingMessages.isNotEmpty()) add(result.trailingMessages)
                if (result.promptTailMessages.isNotEmpty()) add(result.promptTailMessages)
            }
        }

    fun recentMessages(messages: List<ChatMessage>, groupLimit: Int): List<ChatMessage> {
        val partition = partition(messages)
        return partition.countedGroups.takeLast(groupLimit.coerceAtLeast(1)).flatten() +
            partition.trailingMessages +
            partition.promptTailMessages
    }

    fun archivedMessages(messages: List<ChatMessage>, groupLimit: Int): List<ChatMessage> =
        partition(messages).countedGroups.dropLast(groupLimit.coerceAtLeast(1)).flatten()

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
        val previousMessage: ChatMessage?
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
        val previousIndex = if (latestIndex != null) {
            latestIndex - 1
        } else {
            contextMessages.lastIndex
        }
        val previousMessage = previousIndex
            .takeIf { it >= 0 }
            ?.let { contextMessages[it] }
        val excludedIds = mutableSetOf<String>()
        latestMessageId?.let { excludedIds.add(it) }
        previousMessage?.id?.let { excludedIds.add(it) }

        return PromptMessageGroups(
            historyMessages = contextMessages.filterNot { it.id in excludedIds },
            previousMessage = previousMessage
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
