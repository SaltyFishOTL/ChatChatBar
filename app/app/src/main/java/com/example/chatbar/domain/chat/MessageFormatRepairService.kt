package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.prompt.AiTaskContext
import com.example.chatbar.domain.prompt.AiTaskKind
import com.example.chatbar.domain.prompt.AiTaskStage
import com.example.chatbar.domain.prompt.PromptTemplates
import kotlinx.coroutines.flow.Flow

class MessageFormatRepairService(
    private val streamingChatService: StreamingChatService
) {
    internal class PreparedRequest(
        val originalContent: String,
        renderedFormatCard: String?,
        val segmentedBubbleFormat: String?,
    ) {
        val formatCard = renderedFormatCard?.trim()?.takeIf(String::isNotEmpty)

        fun historyCleanup(): NarrativeHistoryEcho.RequestCleanup =
            NarrativeHistoryEcho.forFormatRepair(formatCard, originalContent)
    }

    fun streamRepair(
        originalContent: String,
        formatCard: String?,
        segmentedBubbleFormat: String?,
        modelConfig: ModelConfig,
        onToolReplyPrefixReader: ((() -> List<String>) -> Unit)? = null,
    ): Flow<StreamEvent> = streamRepair(
        PreparedRequest(originalContent, formatCard, segmentedBubbleFormat),
        modelConfig,
        onToolReplyPrefixReader,
    )

    internal fun streamRepair(
        request: PreparedRequest,
        modelConfig: ModelConfig,
        onToolReplyPrefixReader: ((() -> List<String>) -> Unit)? = null,
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
                    formatCard = request.formatCard,
                    segmentedBubbleFormat = request.segmentedBubbleFormat,
                    message = request.originalContent
                )
            )
        ),
        modelConfig = modelConfig,
        readTimeoutSeconds = FORMAT_REPAIR_READ_TIMEOUT_SECONDS,
        emitOrdinaryPreview = true,
        onToolReplyPrefixReader = onToolReplyPrefixReader,
    )

    companion object {
        internal const val FORMAT_REPAIR_READ_TIMEOUT_SECONDS = 10L * 60L
    }
}
