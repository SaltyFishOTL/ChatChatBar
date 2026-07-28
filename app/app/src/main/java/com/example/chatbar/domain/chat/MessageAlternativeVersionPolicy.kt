package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatMessage
import java.nio.charset.StandardCharsets
import java.util.UUID

data class MessageVersionSnapshot(
    val id: String,
    val content: String
)

object MessageAlternativeVersionPolicy {
    fun newVersionId(
        message: ChatMessage,
        idFactory: () -> String = { UUID.randomUUID().toString() }
    ): String {
        val existingIds = versions(message).mapTo(mutableSetOf(), MessageVersionSnapshot::id)
        repeat(100) {
            val candidate = idFactory().trim()
            if (candidate.isNotEmpty() && candidate !in existingIds) return candidate
        }
        error("无法生成唯一消息版本 ID")
    }

    fun versions(message: ChatMessage): List<MessageVersionSnapshot> {
        if (message.alternatives.isEmpty()) {
            return listOf(
                MessageVersionSnapshot(
                    id = message.currentAlternativeVersionId
                        ?.takeIf(String::isNotBlank)
                        ?: message.id,
                    content = message.content
                )
            )
        }
        val ids = alignedVersionIds(message)
        return message.alternatives.mapIndexed { index, content ->
            MessageVersionSnapshot(ids[index], content)
        }
    }

    fun activeVersion(message: ChatMessage): MessageVersionSnapshot {
        val versions = versions(message)
        return if (
            message.alternatives.isNotEmpty() &&
            message.currentAlternativeIndex in versions.indices
        ) {
            versions[message.currentAlternativeIndex]
        } else {
            MessageVersionSnapshot(
                id = message.currentAlternativeVersionId
                    ?.takeIf(String::isNotBlank)
                    ?: message.id,
                content = message.content
            )
        }
    }

    fun activeVersionId(message: ChatMessage): String = activeVersion(message).id

    fun normalize(message: ChatMessage): ChatMessage {
        val active = activeVersion(message)
        return message.copy(
            alternativeVersionIds = if (message.alternatives.isEmpty()) {
                emptyList()
            } else {
                alignedVersionIds(message)
            },
            currentAlternativeVersionId = active.id
        )
    }

    fun append(
        message: ChatMessage,
        content: String,
        newVersionId: String,
        maxVersions: Int = 5,
        updatedAt: Long = System.currentTimeMillis()
    ): ChatMessage {
        require(newVersionId.isNotBlank()) { "消息版本 ID 不能为空" }
        require(maxVersions > 0) { "消息版本数量上限必须大于 0" }
        val existing = versions(message)
        require(existing.none { it.id == newVersionId }) { "消息版本 ID 重复" }
        val retained = (
            existing + MessageVersionSnapshot(
                id = newVersionId,
                content = content
            )
        ).takeLast(maxVersions)
        return message.copy(
            content = content,
            alternatives = retained.map(MessageVersionSnapshot::content),
            alternativeVersionIds = retained.map(MessageVersionSnapshot::id),
            currentAlternativeIndex = retained.lastIndex,
            currentAlternativeVersionId = newVersionId,
            updatedAt = updatedAt
        )
    }

    fun select(
        message: ChatMessage,
        alternativeIndex: Int,
        updatedAt: Long = System.currentTimeMillis()
    ): ChatMessage {
        val versions = versions(message)
        require(message.alternatives.isNotEmpty()) { "消息没有替代版本" }
        require(alternativeIndex in versions.indices) { "消息版本索引越界" }
        val selected = versions[alternativeIndex]
        return message.copy(
            content = selected.content,
            alternativeVersionIds = versions.map(MessageVersionSnapshot::id),
            currentAlternativeIndex = alternativeIndex,
            currentAlternativeVersionId = selected.id,
            updatedAt = updatedAt
        )
    }

    fun collapseToEditedContent(
        message: ChatMessage,
        content: String,
        updatedAt: Long = System.currentTimeMillis()
    ): ChatMessage = message.copy(
        content = content,
        alternatives = emptyList(),
        alternativeVersionIds = emptyList(),
        currentAlternativeIndex = 0,
        currentAlternativeVersionId = activeVersionId(message),
        updatedAt = updatedAt
    )

    private fun alignedVersionIds(message: ChatMessage): List<String> {
        val used = mutableSetOf<String>()
        return message.alternatives.indices.map { index ->
            val preferred = message.alternativeVersionIds.getOrNull(index)
                ?.takeIf(String::isNotBlank)
                ?: message.currentAlternativeVersionId
                    ?.takeIf { index == message.currentAlternativeIndex && it.isNotBlank() }
                ?: legacyVersionId(message.id, index)
            var candidate = preferred
            var collisionIndex = 0
            while (!used.add(candidate)) {
                candidate = legacyVersionId(message.id, index, collisionIndex++)
            }
            candidate
        }
    }

    private fun legacyVersionId(
        messageId: String,
        index: Int,
        collisionIndex: Int? = null
    ): String {
        val source = buildString {
            append("chatbar-message-version:")
            append(messageId)
            append(':')
            append(index)
            collisionIndex?.let {
                append(':')
                append(it)
            }
        }
        return UUID.nameUUIDFromBytes(
            source.toByteArray(StandardCharsets.UTF_8)
        ).toString()
    }
}
