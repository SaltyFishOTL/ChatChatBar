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
        val alternatives = message.alternatives
        val updatedAlternatives = if (
            alternatives.isNotEmpty() && message.currentAlternativeIndex in alternatives.indices
        ) {
            alternatives.toMutableList().also { it[message.currentAlternativeIndex] = replacement }
        } else {
            alternatives
        }
        return message.copy(
            content = replacement,
            alternatives = updatedAlternatives,
            formatRepairNotice = notice,
            updatedAt = updatedAt
        )
    }

    fun applicableNotice(message: ChatMessage): MessageFormatRepairNotice? =
        message.formatRepairNotice?.takeIf { it.targetContent == message.displayContent }

    fun recoverableNotice(message: ChatMessage): MessageFormatRepairNotice? =
        applicableNotice(message)?.takeIf { it.originalContent != null }

    fun restoreOriginal(message: ChatMessage, updatedAt: Long = System.currentTimeMillis()): ChatMessage? {
        val notice = recoverableNotice(message) ?: return null
        val original = checkNotNull(notice.originalContent)
        return replaceCurrentDisplayContent(
            message = message,
            replacement = original,
            notice = null,
            updatedAt = updatedAt
        )
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
