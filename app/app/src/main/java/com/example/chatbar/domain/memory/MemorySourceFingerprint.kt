package com.example.chatbar.domain.memory

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.domain.chat.ConfirmedActionChoice
import com.example.chatbar.domain.chat.RoleplayActionOptionsPolicy

/** Stable source evidence: exactly the source semantics shown to memory models. */
object MemorySourceFingerprint {
    fun semantic(
        sourceTurnId: String,
        messages: List<ChatMessage>,
        session: ChatSession
    ): String {
        val sourceMessages = messages
            .filter { it.sourceTurnId == sourceTurnId && it.role != MessageRole.SYSTEM }
            .sortedWith(ChatMessage.TimelineComparator)
        if (sourceMessages.isEmpty()) {
            val tombstone = session.sourceTurnTombstones.firstOrNull { it.sourceTurnId == sourceTurnId }
            return MemoryHashes.text(field(sourceTurnId) + field("tombstone") + field(tombstone?.sourceOrder))
        }
        return MemoryHashes.text(buildString {
            append(field(sourceTurnId))
            sourceMessages.forEach { message ->
                append(field(message.role.name))
                append(field(modelFacingBody(message, RoleplayActionOptionsPolicy.confirmedMemoryChoice(message, messages))))
            }
        })
    }

    /** Compatibility proof for semantic fingerprints written before model-facing normalization. */
    fun previousSemantic(
        sourceTurnId: String,
        messages: List<ChatMessage>,
        session: ChatSession
    ): String {
        val sourceMessages = messages
            .filter { it.sourceTurnId == sourceTurnId && it.role != MessageRole.SYSTEM }
            .sortedWith(ChatMessage.TimelineComparator)
        if (sourceMessages.isEmpty()) {
            val tombstone = session.sourceTurnTombstones.firstOrNull { it.sourceTurnId == sourceTurnId }
            return MemoryHashes.text(field(sourceTurnId) + field("tombstone") + field(tombstone?.sourceOrder))
        }
        return MemoryHashes.text(buildString {
            append(field(sourceTurnId))
            append(field(sourceMessages.first().sourceTurnOrder))
            sourceMessages.forEach { message ->
                append(field(message.id))
                append(field(message.role.name))
                append(field(message.displayContent))
                append(field(message.images.size))
                message.images.forEach { append(field(it)) }
            }
        })
    }

    /** Compatibility proof for records written before semantic fingerprints existed. */
    fun legacy(
        sourceTurnId: String,
        messages: List<ChatMessage>,
        session: ChatSession
    ): String {
        val sourceMessages = messages.filter {
            it.sourceTurnId == sourceTurnId && it.role != MessageRole.SYSTEM
        }
        if (sourceMessages.isEmpty()) {
            val tombstone = session.sourceTurnTombstones.firstOrNull { it.sourceTurnId == sourceTurnId }
            return MemoryHashes.text("$sourceTurnId:tombstone:${tombstone?.sourceOrder}")
        }
        return MemoryHashes.text(sourceMessages.joinToString("\n") { message ->
            "${message.id}:${message.sourceTurnOrder}:${message.orderKey}:${message.updatedAt}:" +
                "${message.displayContent}:${message.images.joinToString(",")}" 
        })
    }

    fun modelFacingBody(message: ChatMessage, choice: ConfirmedActionChoice? = null): String =
        projectSourceBody(message, choice).ifBlank {
            if (message.images.isNotEmpty()) "[图片]" else "（空）"
        }

    internal fun sourceIdsWithProjectedActions(messages: List<ChatMessage>): Set<String> =
        messages.asSequence()
            .filter { projectSourceBody(it, RoleplayActionOptionsPolicy.confirmedMemoryChoice(it, messages)) != it.displayContent }
            .mapNotNull { it.sourceTurnId }
            .toSet()

    private fun projectSourceBody(message: ChatMessage, choice: ConfirmedActionChoice?): String =
        if (message.role == MessageRole.ASSISTANT) {
            RoleplayActionOptionsPolicy.withoutExplicitOptions(message.displayContent, message.displayActionOptionsFormat)
        } else RoleplayActionOptionsPolicy.preserveConfirmedUserChoice(message.displayContent, choice)

    private fun field(value: Any?): String {
        val text = value?.toString().orEmpty()
        return "${text.length}:$text;"
    }
}
