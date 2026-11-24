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
        val systemPrompt = PromptTemplates.MESSAGE_FORMAT_REPAIR_SYSTEM_PROMPT.trim()
        val userPrompt = PromptTemplates.messageFormatRepairUserPrompt(formatCard, segmentedBubbleFormat, originalContent)

        fun historyCleanup(): NarrativeHistoryEcho.RequestCleanup =
            NarrativeHistoryEcho.forFormatRepair(formatCard, originalContent, dispatchEvidence())

        private fun dispatchEvidence(): NarrativeHistoryEcho.RepairDispatch {
            val authoredSections = listOfNotNull(formatCard, segmentedBubbleFormat, originalContent)
            val boundaries = mutableListOf<String>()
            var messageBoundary: String? = null
            var start = 0
            for ((index, authoredContent) in authoredSections.withIndex()) {
                val boundaryEnd = userPrompt.indexOf('\n', start)
                if (boundaryEnd <= start) return NarrativeHistoryEcho.RepairDispatch()
                val contentStart = boundaryEnd + 1
                val contentEnd = contentStart + authoredContent.length
                if (!userPrompt.regionMatches(contentStart, authoredContent, 0, authoredContent.length)) {
                    return NarrativeHistoryEcho.RepairDispatch()
                }
                val isMessageBoundary = index == authoredSections.lastIndex
                if (isMessageBoundary && contentEnd != userPrompt.length ||
                    !isMessageBoundary && !userPrompt.startsWith("\n\n", contentEnd)
                ) return NarrativeHistoryEcho.RepairDispatch()
                val boundary = userPrompt.substring(start, boundaryEnd)
                if (authoredSections.none { boundary in it }) {
                    boundaries += boundary
                    if (isMessageBoundary) messageBoundary = boundary
                }
                start = contentEnd + 2
            }
            val preamble = systemPrompt.lineSequence().first()
            val sentenceEnd = preamble.indexOfFirst { it in ".。!?！？" }
            val firstSentence = if (sentenceEnd < 0) preamble else preamble.take(sentenceEnd + 1)
            val hasAuthoredIntroduction = authoredSections.any { firstSentence in it }
            val hasAuthoredSystemBlock = hasAuthoredIntroduction &&
                NarrativeHistoryEcho.isAuthoredRepairSystemText(authoredSections, systemPrompt)
            val continuations = if (messageBoundary != null && !hasAuthoredIntroduction) {
                mapOf(messageBoundary to setOf(listOf(firstSentence), listOf(preamble)))
            } else emptyMap()
            val fullUnlabelledIntroduction = preamble.takeIf { it != firstSentence && !hasAuthoredIntroduction }
            return NarrativeHistoryEcho.RepairDispatch(
                boundaries, authoredSections, continuations, fullUnlabelledIntroduction,
                unlabelledSystemBlock = systemPrompt.takeUnless { hasAuthoredSystemBlock },
            )
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
                content = request.systemPrompt,
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
