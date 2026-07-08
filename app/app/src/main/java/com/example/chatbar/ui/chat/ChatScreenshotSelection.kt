package com.example.chatbar.ui.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import java.text.SimpleDateFormat
import java.util.Date
import java.util.LinkedHashSet
import java.util.Locale

fun ChatMessage.isSelectableForChatScreenshot(): Boolean =
    role == MessageRole.USER || role == MessageRole.ASSISTANT

fun toggleChatScreenshotSelection(
    currentIds: Set<String>,
    messageId: String,
    selectableIds: Set<String>
): Set<String> {
    if (messageId !in selectableIds) return currentIds
    val next = LinkedHashSet(currentIds)
    if (messageId in next) {
        next.remove(messageId)
    } else {
        next.add(messageId)
    }
    return next
}

fun cleanChatScreenshotSelection(
    currentIds: Set<String>,
    messages: List<ChatMessage>
): Set<String> {
    val selectableIds = messages
        .filter(ChatMessage::isSelectableForChatScreenshot)
        .mapTo(LinkedHashSet()) { it.id }
    return currentIds.filterTo(LinkedHashSet()) { it in selectableIds }
}

fun orderedChatScreenshotMessages(
    messages: List<ChatMessage>,
    selectedIds: Set<String>
): List<ChatMessage> =
    messages.filter { it.isSelectableForChatScreenshot() && it.id in selectedIds }

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
