package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageFormatRepairNotice
import com.example.chatbar.data.local.entity.MessageFormatRepairNoticeKind
import com.example.chatbar.domain.prompt.PromptTemplates

object MessageFormatRepairPolicy {
    fun progressiveOverlay(original: String, repairedPrefix: String): String {
        if (isPossibleUnchangedMarkerPrefix(repairedPrefix)) return original
        if (repairedPrefix.isEmpty()) return original
        val prefixLength = repairedPrefix.codePointCount()
        val originalLength = original.codePointCount()
        if (prefixLength >= originalLength) return repairedPrefix
        val suffixStart = original.offsetByCodePoints(0, prefixLength)
        return repairedPrefix + original.substring(suffixStart)
    }

    fun isUnchangedResult(modelOutput: String): Boolean =
        modelOutput.trim() == PromptTemplates.MESSAGE_FORMAT_REPAIR_UNCHANGED_MARKER

    fun completedRepairNotice(original: String, repaired: String): MessageFormatRepairNotice =
        MessageFormatRepairNotice(
            kind = MessageFormatRepairNoticeKind.APPLIED,
            targetContent = repaired,
            originalContent = original
        )

    fun replaceCurrentDisplayContent(
        message: ChatMessage,
        replacement: String,
        notice: MessageFormatRepairNotice? = applicableNotice(message),
        updatedAt: Long = System.currentTimeMillis()
    ): ChatMessage {
        val identity = MessageAlternativeVersionPolicy.identity(message.id, message.alternatives.size,
            message.alternativeVersionIds, message.currentAlternativeIndex, message.currentAlternativeVersionId)
        val alternatives = message.alternatives
        val updatedAlternatives = if (
            alternatives.isNotEmpty() && message.currentAlternativeIndex in alternatives.indices
        ) {
            alternatives.toMutableList().also { it[message.currentAlternativeIndex] = replacement }
        } else {
            alternatives
        }
        val updated = message.copy(
            alternativeVersionIds = identity.alternativeVersionIds,
            currentAlternativeVersionId = identity.currentAlternativeVersionId,
            content = replacement,
            alternatives = updatedAlternatives,
            updatedAt = updatedAt
        )
        return updateNotice(message, updated, notice)
    }

    fun applicableNotice(message: ChatMessage): MessageFormatRepairNotice? {
        val notices = message.repairNotices()
        if (notices.isEmpty()) return null
        val active = MessageAlternativeVersionPolicy.activeVersion(message)
        val versions = MessageAlternativeVersionPolicy.versions(message)
        return notices.firstOrNull { it.owner(versions) == active }
    }

    internal fun retainVersionNotices(previous: ChatMessage, updated: ChatMessage): ChatMessage =
        updated.withNotices(validNoticesAfterChange(previous, updated))

    internal fun updateCurrentNotice(message: ChatMessage, notice: MessageFormatRepairNotice?): ChatMessage =
        updateNotice(message, message, notice)

    /** A successful no-op clears the current candidate's failure while retaining valid recovery data. */
    internal fun afterUnchangedRepair(message: ChatMessage): ChatMessage =
        updateCurrentNotice(message, applicableNotice(message)?.takeIf { it.originalContent != null }?.withoutAttemptError())

    private fun updateNotice(
        previous: ChatMessage,
        updated: ChatMessage,
        notice: MessageFormatRepairNotice?
    ): ChatMessage {
        val activeId = MessageAlternativeVersionPolicy.activeVersionId(updated)
        val otherNotices = validNoticesAfterChange(previous, updated).filterNot { it.versionId == activeId }
        return updated.withNotices(listOfNotNull(notice?.copy(versionId = activeId)) + otherNotices)
    }

    private fun validNoticesAfterChange(previous: ChatMessage, updated: ChatMessage): List<MessageFormatRepairNotice> {
        val notices = previous.repairNotices()
        if (notices.isEmpty()) return emptyList()
        val previousVersions = MessageAlternativeVersionPolicy.versions(previous)
        val updatedVersions = MessageAlternativeVersionPolicy.versions(updated).associateBy { it.id }
        return notices.mapNotNull { notice ->
            val owner = notice.owner(previousVersions) ?: return@mapNotNull null
            notice.copy(versionId = owner.id).takeIf { updatedVersions[owner.id]?.content == owner.content }
        }.distinctBy { it.versionId }
    }

    private fun ChatMessage.repairNotices(): List<MessageFormatRepairNotice> =
        listOfNotNull(formatRepairNotice) + otherFormatRepairNotices

    private fun ChatMessage.withNotices(notices: List<MessageFormatRepairNotice>): ChatMessage =
        copy(formatRepairNotice = notices.firstOrNull(), otherFormatRepairNotices = notices.drop(1))

    private fun MessageFormatRepairNotice.owner(versions: List<MessageVersionSnapshot>): MessageVersionSnapshot? {
        val checkpoint = if (restored) originalContent else targetContent
        return versions.singleOrNull { it.content == checkpoint && (versionId == null || it.id == versionId) }
    }

    internal fun inactiveRecoveryNotice(message: ChatMessage): MessageFormatRepairNotice? {
        val notices = message.repairNotices()
        if (notices.isEmpty()) return null
        val versions = MessageAlternativeVersionPolicy.versions(message)
        val activeId = MessageAlternativeVersionPolicy.activeVersionId(message)
        return notices.firstOrNull { notice ->
            notice.originalContent != null && notice.owner(versions)?.id?.let { it != activeId } == true
        }
    }

    /** Attach the latest request error without replacing the selected version's recovery pair. */
    fun failedAttemptNotice(message: ChatMessage, error: String): MessageFormatRepairNotice =
        applicableNotice(message)?.takeIf { it.originalContent != null }?.copy(errorMessage = error)
            ?: MessageFormatRepairNotice(
                kind = MessageFormatRepairNoticeKind.ERROR,
                targetContent = message.displayContent,
                errorMessage = error,
                versionId = MessageAlternativeVersionPolicy.activeVersionId(message)
            )

    private fun MessageFormatRepairNotice.withoutAttemptError(): MessageFormatRepairNotice = copy(
        kind = if (kind == MessageFormatRepairNoticeKind.ERROR) MessageFormatRepairNoticeKind.APPLIED else kind,
        errorMessage = null
    )

    fun recoverableNotice(message: ChatMessage): MessageFormatRepairNotice? =
        applicableNotice(message)?.takeIf { it.originalContent != null && !it.restored }

    fun restoreOriginal(message: ChatMessage, updatedAt: Long = System.currentTimeMillis()): ChatMessage? {
        val notice = recoverableNotice(message) ?: return null
        val original = checkNotNull(notice.originalContent)
        return replaceCurrentDisplayContent(
            message = message,
            replacement = original,
            notice = notice.withoutAttemptError().copy(restored = true),
            updatedAt = updatedAt
        )
    }

    fun redoRepair(message: ChatMessage, updatedAt: Long = System.currentTimeMillis()): ChatMessage? {
        val notice = applicableNotice(message)?.takeIf { it.restored } ?: return null
        return replaceCurrentDisplayContent(message, notice.targetContent, notice.withoutAttemptError().copy(restored = false), updatedAt)
    }

    private fun isPossibleUnchangedMarkerPrefix(outputPrefix: String): Boolean {
        val marker = PromptTemplates.MESSAGE_FORMAT_REPAIR_UNCHANGED_MARKER
        val trimmedStart = outputPrefix.trimStart()
        if (trimmedStart.isEmpty() || marker.startsWith(trimmedStart)) return true
        if (!trimmedStart.startsWith(marker)) return false
        return trimmedStart.removePrefix(marker).isBlank()
    }
}

private fun String.codePointCount(): Int = codePointCount(0, length)
