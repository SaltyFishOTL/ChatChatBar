package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatNarrativeMode
import java.nio.charset.StandardCharsets
import java.util.UUID

data class MessageVersionSnapshot(
    val id: String,
    val content: String,
    val narrativeMode: ChatNarrativeMode? = ChatNarrativeMode.FOCUS
)

data class MessageVersionIdentity(val alternativeVersionIds: List<String>, val currentAlternativeVersionId: String)

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
                    content = message.content,
                    narrativeMode = message.narrativeMode
                )
            )
        }
        val ids = alignedVersionIds(message)
        return message.alternatives.mapIndexed { index, content ->
            MessageVersionSnapshot(
                ids[index], content,
                if (index in message.alternativeNarrativeModes.indices) {
                    message.alternativeNarrativeModes[index]
                } else ChatNarrativeMode.FOCUS
            )
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
                content = message.content,
                narrativeMode = message.narrativeMode
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
            currentAlternativeVersionId = active.id,
            narrativeMode = active.narrativeMode,
            alternativeNarrativeModes = if (message.alternatives.isEmpty()) {
                emptyList()
            } else {
                versions(message).map(MessageVersionSnapshot::narrativeMode)
            }
        )
    }

    fun append(
        message: ChatMessage,
        content: String,
        newVersionId: String,
        maxVersions: Int = Int.MAX_VALUE,
        updatedAt: Long = System.currentTimeMillis(),
        narrativeMode: ChatNarrativeMode? = message.narrativeMode
    ): ChatMessage {
        require(newVersionId.isNotBlank()) { "消息版本 ID 不能为空" }
        require(maxVersions > 0) { "消息版本数量上限必须大于 0" }
        val existing = versions(message)
        require(existing.none { it.id == newVersionId }) { "消息版本 ID 重复" }
        val retained = (
            existing + MessageVersionSnapshot(
                id = newVersionId,
                content = content,
                narrativeMode = narrativeMode
            )
        ).takeLast(maxVersions)
        return message.copy(
            content = content,
            alternatives = retained.map(MessageVersionSnapshot::content),
            alternativeVersionIds = retained.map(MessageVersionSnapshot::id),
            currentAlternativeIndex = retained.lastIndex,
            currentAlternativeVersionId = newVersionId,
            narrativeMode = narrativeMode,
            alternativeNarrativeModes = retained.map(MessageVersionSnapshot::narrativeMode),
            updatedAt = updatedAt
        )
    }

    /** Removes one stable version; shared message media and surviving version identities are unchanged. */
    fun remove(message: ChatMessage, versionId: String, updatedAt: Long = System.currentTimeMillis()): ChatMessage? {
        val all = versions(message)
        val removedIndex = all.indexOfFirst { it.id == versionId }
        require(removedIndex >= 0) { "消息版本不存在" }
        val retained = all.filterNot { it.id == versionId }
        if (retained.isEmpty()) return null
        val activeId = activeVersionId(message)
        val selectedIndex = retained.indexOfFirst { it.id == activeId }.takeIf { it >= 0 }
            ?: removedIndex.coerceAtMost(retained.lastIndex)
        val selected = retained[selectedIndex]
        return message.copy(
            content = selected.content,
            alternatives = retained.map { it.content },
            alternativeVersionIds = retained.map { it.id },
            currentAlternativeIndex = selectedIndex,
            currentAlternativeVersionId = selected.id,
            narrativeMode = selected.narrativeMode,
            alternativeNarrativeModes = retained.map { it.narrativeMode },
            formatRepairNotice = message.formatRepairNotice?.takeUnless { notice ->
                (notice.versionId ?: activeId) == versionId
            },
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
            narrativeMode = selected.narrativeMode,
            alternativeNarrativeModes = versions.map(MessageVersionSnapshot::narrativeMode),
            updatedAt = updatedAt
        )
    }

    fun editCurrentContent(
        message: ChatMessage,
        content: String,
        updatedAt: Long = System.currentTimeMillis()
    ): ChatMessage {
        return normalize(message).copy(
            content = content,
            alternatives = message.alternatives.mapIndexed { index, previous ->
                if (index == message.currentAlternativeIndex) content else previous
            },
            updatedAt = updatedAt
        )
    }

    private fun alignedVersionIds(message: ChatMessage): List<String> = identity(
        message.id, message.alternatives.size, message.alternativeVersionIds,
        message.currentAlternativeIndex, message.currentAlternativeVersionId
    ).alternativeVersionIds

    /** Metadata-only identity resolution, also used before backup message IDs are remapped. */
    fun identity(
        messageId: String,
        alternativeCount: Int,
        explicitIds: List<String>,
        currentIndex: Int,
        currentId: String?
    ): MessageVersionIdentity {
        require(alternativeCount >= 0)
        val used = mutableSetOf<String>()
        val ids = (0 until alternativeCount).map { index ->
            val preferred = explicitIds.getOrNull(index)?.takeIf(String::isNotBlank)
                ?: currentId?.takeIf { index == currentIndex && it.isNotBlank() }
                ?: legacyVersionId(messageId, index)
            var candidate = preferred
            var collisionIndex = 0
            while (!used.add(candidate)) candidate = legacyVersionId(messageId, index, collisionIndex++)
            candidate
        }
        return MessageVersionIdentity(ids, ids.getOrNull(currentIndex)
            ?: currentId?.takeIf(String::isNotBlank) ?: messageId)
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
