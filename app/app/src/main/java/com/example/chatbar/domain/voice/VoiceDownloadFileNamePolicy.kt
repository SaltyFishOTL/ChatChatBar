package com.example.chatbar.domain.voice

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

private val voiceDownloadTimeFormatter =
    DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss_SSS", Locale.US)

fun buildVoiceDownloadFileName(
    chatName: String,
    characterName: String,
    downloadedAtMillis: Long = System.currentTimeMillis(),
    zoneId: ZoneId = ZoneId.systemDefault()
): String {
    val safeChatName = sanitizeVoiceDownloadFileSegment(chatName).ifBlank { "聊天" }
    val safeCharacterName = sanitizeVoiceDownloadFileSegment(characterName).ifBlank { "角色" }
    val downloadedAt = voiceDownloadTimeFormatter
        .withZone(zoneId)
        .format(Instant.ofEpochMilli(downloadedAtMillis))
    return "${safeChatName}_${safeCharacterName}_$downloadedAt.mp3"
}

internal fun sanitizeVoiceDownloadFileSegment(value: String): String =
    value
        .trim()
        .replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_")
        .replace(Regex("\\s+"), "_")
        .take(60)
        .trim('_', '.', ' ')
