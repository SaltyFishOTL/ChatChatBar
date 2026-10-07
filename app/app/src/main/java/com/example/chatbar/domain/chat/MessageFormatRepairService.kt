package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.prompt.AiTaskContext
import com.example.chatbar.domain.prompt.AiTaskKind
import com.example.chatbar.domain.prompt.AiTaskStage
import com.example.chatbar.domain.prompt.aiTaskRunContext
import com.example.chatbar.domain.prompt.rethrowIfAiTaskTerminalFailure
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
        taskContext = AiTaskContext(AiTaskKind.FORMAT_REPAIR, AiTaskStage.REPAIR),
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
        readTimeoutSeconds = FORMAT_REPAIR_READ_TIMEOUT_SECONDS
    )

    companion object {
        internal const val FORMAT_REPAIR_READ_TIMEOUT_SECONDS = 10L * 60L
    }
}
