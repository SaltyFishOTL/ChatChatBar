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
        notice: MessageFormatRepairNotice? = message.formatRepairNotice,
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
        return message.copy(
            alternativeVersionIds = identity.alternativeVersionIds,
            currentAlternativeVersionId = identity.currentAlternativeVersionId,
            content = replacement,
            alternatives = updatedAlternatives,
            formatRepairNotice = notice?.copy(versionId = identity.currentAlternativeVersionId),
            updatedAt = updatedAt
        )
    }

    fun applicableNotice(message: ChatMessage): MessageFormatRepairNotice? =
        message.formatRepairNotice?.takeIf { notice ->
            val checkpoint = if (notice.restored) notice.originalContent else notice.targetContent
            if (checkpoint != message.displayContent) return@takeIf false
            val ownerId = notice.versionId ?: MessageAlternativeVersionPolicy.versions(message)
                .singleOrNull { it.content == checkpoint }?.id
            ownerId == MessageAlternativeVersionPolicy.activeVersionId(message)
        }

    /** A successful no-op clears the current candidate's failure while retaining valid recovery data. */
    fun retainedNoticeAfterUnchangedSuccess(message: ChatMessage): MessageFormatRepairNotice? =
        applicableNotice(message)?.takeIf { it.originalContent != null }?.withoutAttemptError()
            ?: inactiveRecoveryNotice(message)

    internal fun inactiveRecoveryNotice(message: ChatMessage): MessageFormatRepairNotice? {
        val notice = message.formatRepairNotice?.takeIf { it.originalContent != null } ?: return null
        val checkpoint = if (notice.restored) notice.originalContent else notice.targetContent
        val owner = MessageAlternativeVersionPolicy.versions(message).singleOrNull { version ->
            version.content == checkpoint && (notice.versionId == null || notice.versionId == version.id)
        } ?: return null
        return notice.takeIf { owner.id != MessageAlternativeVersionPolicy.activeVersionId(message) }
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
