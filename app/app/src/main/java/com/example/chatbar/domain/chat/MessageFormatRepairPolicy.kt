package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageFormatRepairNotice

object MessageFormatRepairPolicy {
    fun progressiveOverlay(original: String, repairedPrefix: String): String {
        if (repairedPrefix.isEmpty()) return original
        val prefixLength = repairedPrefix.codePointCount()
        val originalLength = original.codePointCount()
        if (prefixLength >= originalLength) return repairedPrefix
        val suffixStart = original.offsetByCodePoints(0, prefixLength)
        return repairedPrefix + original.substring(suffixStart)
    }

    fun isLengthAnomalous(original: String, repaired: String): Boolean {
        val originalLength = original.codePointCount()
        if (originalLength == 0) return repaired.isNotEmpty()
        val repairedLength = repaired.codePointCount()
        return repairedLength * 2 < originalLength || repairedLength > originalLength * 2
    }

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

    fun restoreOriginal(message: ChatMessage, updatedAt: Long = System.currentTimeMillis()): ChatMessage? {
        val notice = applicableNotice(message) ?: return null
        val original = notice.originalContent ?: return null
        return replaceCurrentDisplayContent(
            message = message,
            replacement = original,
            notice = null,
            updatedAt = updatedAt
        )
    }
}

private fun String.codePointCount(): Int = codePointCount(0, length)
