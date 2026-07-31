package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.prompt.PromptTemplates
import kotlinx.coroutines.flow.Flow

class MessageFormatRepairService(
    private val streamingChatService: StreamingChatService
) {
    fun streamRepair(
        originalContent: String,
        formatCard: String?,
        segmentedBubbleFormat: String?,
        modelConfig: ModelConfig
    ): Flow<StreamEvent> = streamingChatService.streamText(
        messages = listOf(
            ChatApiMessage.text(
                role = "system",
                content = PromptTemplates.MESSAGE_FORMAT_REPAIR_SYSTEM_PROMPT.trim()
            ),
            ChatApiMessage.text(
                role = "user",
                content = PromptTemplates.messageFormatRepairUserPrompt(
                    formatCard = formatCard,
                    segmentedBubbleFormat = segmentedBubbleFormat,
                    message = originalContent
                )
            )
        ),
        modelConfig = modelConfig,
        maxTokens = outputTokenLimit(originalContent, modelConfig.maxOutputTokens),
        disableThinking = true,
        readTimeoutSeconds = FORMAT_REPAIR_READ_TIMEOUT_SECONDS
    )

    companion object {
        internal const val FORMAT_REPAIR_READ_TIMEOUT_SECONDS = 10L * 60L

        internal fun outputTokenLimit(content: String, modelLimit: Int?): Int {
            val contentCodePoints = content.codePointCount(0, content.length)
            val requested = maxOf(1_024, contentCodePoints * 4)
            return minOf(modelLimit ?: 8_192, requested)
        }
    }
}
