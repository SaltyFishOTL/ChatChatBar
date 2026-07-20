package com.example.chatbar.domain.chat

import com.example.chatbar.domain.prompt.PromptTemplates
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive

object ChatRequestMemoryPolicy {
    private val archiveMarker = "【${PromptTemplates.SECTION_MEMORY_ARCHIVE}】"

    fun archiveMessage(archive: String?): ChatApiMessage? = archive
        ?.takeIf(String::isNotBlank)
        ?.let { ChatApiMessage.text("system", it) }

    fun orderedDynamicMessages(
        worldBookAndRag: String?,
        archive: String?,
        headAndTimeline: String?,
        playerName: String?,
        botName: String
    ): List<ChatApiMessage> = listOfNotNull(
        systemMessage(worldBookAndRag),
        archiveMessage(renderMemoryText(archive, playerName, botName)),
        systemMessage(renderMemoryText(headAndTimeline, playerName, botName))
    )

    fun containsArchive(messages: List<ChatApiMessage>): Boolean = messages.any { message ->
        message.role == "system" && runCatching {
            message.content.jsonPrimitive.contentOrNull?.contains(archiveMarker) == true
        }.getOrDefault(false)
    }

    fun requireArchiveIncluded(messages: List<ChatApiMessage>, archive: String?) {
        if (archive.isNullOrBlank()) return
        check(containsArchive(messages)) { "长期记忆Archive未写入最终请求，已阻止发送" }
    }

    private fun systemMessage(content: String?): ChatApiMessage? = content
        ?.takeIf(String::isNotBlank)
        ?.let { ChatApiMessage.text("system", it) }

    private fun renderMemoryText(content: String?, playerName: String?, botName: String): String? =
        content?.let { PlaceholderRenderer.render(it, playerName, botName) }
}
