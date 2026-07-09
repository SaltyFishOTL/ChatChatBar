package com.example.chatbar.ui.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.domain.chat.roleplayBlockMessageId
import com.example.chatbar.domain.chat.roleplayScreenshotBlockIds
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashSet
import java.util.Locale

fun ChatMessage.isSelectableForChatScreenshot(): Boolean =
    roleplayScreenshotBlockIds(this).isNotEmpty()

fun toggleChatScreenshotSelection(
    currentIds: Set<String>,
    blockId: String,
    selectableIds: Set<String>
): Set<String> {
    if (blockId !in selectableIds) return currentIds
    val next = LinkedHashSet(currentIds)
    if (blockId in next) {
        next.remove(blockId)
    } else {
        next.add(blockId)
    }
    return next
}

fun cleanChatScreenshotSelection(
    currentIds: Set<String>,
    messages: List<ChatMessage>
): Set<String> {
    val selectableIds = messages
        .flatMap(::roleplayScreenshotBlockIds)
        .toSet()
    return currentIds.filterTo(LinkedHashSet()) { it in selectableIds }
}

fun orderedChatScreenshotMessages(
    messages: List<ChatMessage>,
    selectedBlockIds: Set<String>
): List<ChatMessage> =
    messages.filter { message ->
        message.isSelectableForChatScreenshot() &&
            selectedBlockIds.any { roleplayBlockMessageId(it) == message.id }
    }

fun latestRegenerableAssistantMessageId(messages: List<ChatMessage>): String? =
    messages
        .asReversed()
        .firstOrNull { message -> message.displayContent.isNotBlank() || message.images.isEmpty() }
        ?.takeIf { message -> message.role == MessageRole.ASSISTANT && message.displayContent.isNotBlank() }
        ?.id

fun buildChatScreenshotFileName(
    title: String,
    timestampMillis: Long = System.currentTimeMillis()
): String {
    val safeTitle = sanitizeChatScreenshotFileSegment(title).ifBlank { "聊天" }
    val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date(timestampMillis))
    return "ChatBar_${safeTitle}_$stamp.png"
}

internal fun sanitizeChatScreenshotFileSegment(value: String): String =
    value
        .trim()
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .replace(Regex("\\s+"), "_")
        .take(40)
        .trim('_', '.', ' ')
