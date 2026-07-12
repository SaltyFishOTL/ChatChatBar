package com.example.chatbar.domain.rag

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.domain.chat.stripRoleplayStatusSegments

data class ChatMemoryMessagePair(
    val userMessage: ChatMessage,
    val assistantMessage: ChatMessage
) {
    val messageIds: Set<String> = setOf(userMessage.id, assistantMessage.id)
}

object ChatMemoryIndexPolicy {
    private val lowInformationReplies = setOf(
        "好", "好的", "好吧", "嗯", "嗯嗯", "哦", "行", "可以", "继续", "继续吧",
        "接着", "接着说", "然后呢", "明白", "知道了", "没问题", "谢谢", "谢了",
        "ok", "okay", "yes", "no"
    )

    fun buildPairs(messages: List<ChatMessage>): List<ChatMemoryMessagePair> {
        val pairs = mutableListOf<ChatMemoryMessagePair>()
        var pendingUser: ChatMessage? = null
        messages.forEach { message ->
            when (message.role) {
                MessageRole.USER -> pendingUser = message
                MessageRole.ASSISTANT -> {
                    val user = pendingUser
                    if (user != null && message.displayContent.isNotBlank()) {
                        pairs += ChatMemoryMessagePair(user, message)
                        pendingUser = null
                    }
                }
                MessageRole.SYSTEM -> Unit
            }
        }
        return pairs
    }

    fun contentForIndex(pair: ChatMemoryMessagePair): String = buildString {
        appendLine("user:")
        appendLine(pair.userMessage.displayContent.trim())
        appendLine()
        appendLine("assistant:")
        append(cleanAssistantContent(pair.assistantMessage.displayContent))
    }.trim()

    fun shouldIndex(pair: ChatMemoryMessagePair): Boolean =
        hasInformation(pair.userMessage.displayContent) ||
            hasInformation(cleanAssistantContent(pair.assistantMessage.displayContent))

    private fun cleanAssistantContent(content: String): String =
        stripRoleplayStatusSegments(content).trim()

    private fun hasInformation(rawContent: String): Boolean {
        val compact = rawContent
            .trim()
            .lowercase()
            .replace(Regex("[\\s\\p{P}\\p{S}]+"), "")
        return compact.length >= 4 && compact !in lowInformationReplies
    }
}
