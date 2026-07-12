package com.example.chatbar.ui.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.domain.chat.roleplayBlockMessageId
import com.example.chatbar.domain.chat.roleplayScreenshotBlockIds
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashSet
import java.util.Locale

fun ChatMessage.isSelectableForChatScreenshot(
    assistantSegmentedBubblesEnabled: Boolean = true
): Boolean =
    roleplayScreenshotBlockIds(this, assistantSegmentedBubblesEnabled).isNotEmpty()

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

fun toggleChatScreenshotMessageSelection(
    currentIds: Set<String>,
    messageBlockIds: Collection<String>,
    selectableIds: Set<String>
): Set<String> {
    val selectableMessageIds = messageBlockIds.filterTo(LinkedHashSet()) { it in selectableIds }
    if (selectableMessageIds.isEmpty()) return currentIds
    val next = LinkedHashSet(currentIds)
    if (selectableMessageIds.all(next::contains)) {
        next.removeAll(selectableMessageIds)
    } else {
        next.addAll(selectableMessageIds)
    }
    return next
}

fun cleanChatScreenshotSelection(
    currentIds: Set<String>,
    messages: List<ChatMessage>,
    assistantSegmentedBubblesEnabled: Boolean = true
): Set<String> {
    val selectableIds = messages
        .flatMap { message -> roleplayScreenshotBlockIds(message, assistantSegmentedBubblesEnabled) }
        .toSet()
    return currentIds.filterTo(LinkedHashSet()) { it in selectableIds }
}

fun orderedChatScreenshotMessages(
    messages: List<ChatMessage>,
    selectedBlockIds: Set<String>,
    assistantSegmentedBubblesEnabled: Boolean = true
): List<ChatMessage> =
    messages.filter { message ->
        message.isSelectableForChatScreenshot(assistantSegmentedBubblesEnabled) &&
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
