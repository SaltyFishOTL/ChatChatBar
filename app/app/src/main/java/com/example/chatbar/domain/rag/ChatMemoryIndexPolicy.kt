package com.example.chatbar.domain.rag

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.VectorChunk
import com.example.chatbar.domain.chat.ChatContextGroupPolicy
import com.example.chatbar.domain.chat.stripRoleplayStatusSegments

data class ChatMemoryTurn(
    val sourceTurnId: String?,
    val sourceTurnOrder: Long?,
    val messages: List<ChatMessage>
) {
    val messageIds: Set<String> = messages.mapTo(linkedSetOf()) { it.id }
    val identityKey: String = sourceTurnId
        ?: "legacy:" + messages.joinToString(",") { it.id }
    val anchorMessage: ChatMessage = messages.lastOrNull { it.role == MessageRole.ASSISTANT }
        ?: messages.last()
}

object ChatMemoryIndexPolicy {
    const val INDEX_MODE = "timeline_turn"
    const val CONTENT_VERSION = "6"

    private const val LONG_TURN_SPLIT_THRESHOLD = 600
    private const val SPLIT_SEARCH_START = 300
    private const val SPLIT_SEARCH_END = 400
    private const val FALLBACK_SPLIT_OFFSET = 350

    private val automaticIndexModes = setOf(
        "single_message_contextual",
        "single_message",
        "message_pair",
        "memory_node",
        INDEX_MODE
    )
    private val lowInformationReplies = setOf(
        "好", "好的", "好吧", "嗯", "嗯嗯", "哦", "行", "可以", "继续", "继续吧",
        "接着", "接着说", "然后呢", "明白", "知道了", "没问题", "谢谢", "谢了",
        "ok", "okay", "yes", "no"
    )

    /** 一个稳定source turn对应一个原始对话身份单元；SYSTEM永不进入RAG正文。 */
    fun buildTurns(messages: List<ChatMessage>): List<ChatMemoryTurn> =
        ChatContextGroupPolicy.groups(messages).mapNotNull { group ->
            val storyMessages = group.messages.filter { it.role != MessageRole.SYSTEM }
            val hasStableSourceTurn = storyMessages.isNotEmpty() &&
                storyMessages.all { it.sourceTurnId != null }
            if (storyMessages.isEmpty() || (!hasStableSourceTurn && !group.isCompleteTurn)) {
                null
            } else {
                ChatMemoryTurn(
                    sourceTurnId = storyMessages.mapNotNull { it.sourceTurnId }.distinct().singleOrNull(),
                    sourceTurnOrder = storyMessages.mapNotNull { it.sourceTurnOrder }.distinct().singleOrNull(),
                    messages = storyMessages
                )
            }
        }

    fun buildIndexableTurns(
        messages: List<ChatMessage>,
        activeMessageIds: Set<String>
    ): List<ChatMemoryTurn> = buildTurns(messages)
        .filter { turn -> turn.messageIds.none { it in activeMessageIds } }
        .filter(::shouldIndex)

    fun contentForIndex(turn: ChatMemoryTurn): String = turn.messages.mapNotNull { message ->
        val content = when (message.role) {
            MessageRole.USER -> message.displayContent.trim()
            MessageRole.ASSISTANT -> cleanAssistantContent(message.displayContent)
            MessageRole.SYSTEM -> ""
        }
        content.takeIf(String::isNotBlank)?.let { text ->
            "${message.role.name.lowercase()}:\n$text"
        }
    }.joinToString("\n\n")

    fun contentsForIndex(turn: ChatMemoryTurn): List<String> =
        splitLongTurnContent(contentForIndex(turn))

    fun shouldIndex(turn: ChatMemoryTurn): Boolean = turn.messages.any { message ->
        when (message.role) {
            MessageRole.USER -> hasInformation(message.displayContent)
            MessageRole.ASSISTANT -> hasInformation(cleanAssistantContent(message.displayContent))
            MessageRole.SYSTEM -> false
        }
    }

    fun isAutomaticChunk(chunk: VectorChunk): Boolean =
        chunk.metadata["indexMode"] in automaticIndexModes

    fun needsAutomaticRebuild(chunk: VectorChunk): Boolean = when (chunk.metadata["indexMode"]) {
        "single_message_contextual", "single_message", "message_pair", "memory_node" -> true
        INDEX_MODE -> chunk.metadata["contentVersion"] != CONTENT_VERSION
        else -> false
    }

    private fun cleanAssistantContent(content: String): String =
        stripRoleplayStatusSegments(content).trim()

    private fun hasInformation(rawContent: String): Boolean {
        val compact = rawContent
            .trim()
            .lowercase()
            .replace(Regex("[\\s\\p{P}\\p{S}]+"), "")
        return compact.length >= 4 && compact !in lowInformationReplies
    }

    internal fun splitLongTurnContent(content: String): List<String> {
        if (content.length <= LONG_TURN_SPLIT_THRESHOLD) return listOf(content)

        val chunks = mutableListOf<String>()
        var chunkStart = 0
        while (content.length - chunkStart > SPLIT_SEARCH_END) {
            val searchStart = chunkStart + SPLIT_SEARCH_START - 1
            val searchEndExclusive = (chunkStart + SPLIT_SEARCH_END).coerceAtMost(content.length)
            val paragraphEnd = (searchStart until searchEndExclusive)
                .firstOrNull { index -> content[index] == '\n' || content[index] == '\r' }
                ?.let { it + 1 }
            val sentenceEnd = (searchStart until searchEndExclusive)
                .firstOrNull { index -> content[index] == '。' || content[index] == '.' }
                ?.let { it + 1 }
            val chunkEnd = paragraphEnd
                ?: sentenceEnd
                ?: (chunkStart + FALLBACK_SPLIT_OFFSET).coerceAtMost(content.length)

            content.substring(chunkStart, chunkEnd)
                .trim()
                .takeIf(String::isNotEmpty)
                ?.let(chunks::add)
            chunkStart = chunkEnd
        }

        content.substring(chunkStart)
            .trim()
            .takeIf(String::isNotEmpty)
            ?.let(chunks::add)
        return chunks
    }
}
