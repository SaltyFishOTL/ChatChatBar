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
        renderedSegmentedBubbleFormat: String?,
    ) {
        val formatCard = renderedFormatCard?.trim()?.takeIf(String::isNotEmpty)
        private val segmentedBubbleFormat = renderedSegmentedBubbleFormat?.trim()?.takeIf(String::isNotEmpty)
        val userPrompt = PromptTemplates.messageFormatRepairUserPrompt(formatCard, segmentedBubbleFormat, originalContent)

        fun historyCleanup(): NarrativeHistoryEcho.RequestCleanup =
            NarrativeHistoryEcho.forFormatRepair(formatCard, originalContent, dispatchedBoundaries())

        private fun dispatchedBoundaries(): Set<String> {
            val authoredSections = listOfNotNull(formatCard, segmentedBubbleFormat, originalContent)
            var start = 0
            return buildSet {
                for ((index, authoredContent) in authoredSections.withIndex()) {
                    val boundaryEnd = userPrompt.indexOf('\n', start)
                    if (boundaryEnd <= start) return emptySet()
                    val contentStart = boundaryEnd + 1
                    val contentEnd = contentStart + authoredContent.length
                    if (!userPrompt.regionMatches(contentStart, authoredContent, 0, authoredContent.length)) return emptySet()
                    if (index == authoredSections.lastIndex) {
                        if (contentEnd != userPrompt.length) return emptySet()
                    } else if (!userPrompt.startsWith("\n\n", contentEnd)) return emptySet()
                    val boundary = userPrompt.substring(start, boundaryEnd)
                    if (authoredSections.none { boundary in it }) add(boundary)
                    start = contentEnd + 2
                }
            }
        }
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
                content = request.userPrompt,
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
