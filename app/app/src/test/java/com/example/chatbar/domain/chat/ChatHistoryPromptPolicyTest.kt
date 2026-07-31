package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHistoryPromptPolicyTest {
    @Test
    fun statusExclusionAppliesOnlyToEarlierAssistantHistory() {
        val formattedContent = """
            正文
            ```status
            状态栏
            ```
            ------
            [选项]()
            ------
        """.trimIndent()
        val assistant = message(MessageRole.ASSISTANT, formattedContent)

        assertEquals(
            "正文",
            ChatHistoryPromptPolicy.sourceText(
                message = assistant,
                excludeAssistantStatusFromHistory = true,
                zone = ChatHistoryPromptZone.EARLIER_HISTORY
            )
        )
        assertEquals(
            formattedContent,
            ChatHistoryPromptPolicy.sourceText(
                message = assistant,
                excludeAssistantStatusFromHistory = true,
                zone = ChatHistoryPromptZone.PREVIOUS_TURN
            )
        )
        assertEquals(
            formattedContent,
            ChatHistoryPromptPolicy.sourceText(
                message = assistant,
                excludeAssistantStatusFromHistory = false,
                zone = ChatHistoryPromptZone.EARLIER_HISTORY
            )
        )
    }

    @Test
    fun statusOnlyPreviousAssistantRemainsAvailableWhileEarlierHistoryCanBeOmitted() {
        val statusOnly = """
            ```status
            状态栏
            ```
        """.trimIndent()
        val assistant = message(MessageRole.ASSISTANT, statusOnly)

        assertEquals(
            "",
            ChatHistoryPromptPolicy.sourceText(
                message = assistant,
                excludeAssistantStatusFromHistory = true,
                zone = ChatHistoryPromptZone.EARLIER_HISTORY
            )
        )
        assertEquals(
            statusOnly,
            ChatHistoryPromptPolicy.sourceText(
                message = assistant,
                excludeAssistantStatusFromHistory = true,
                zone = ChatHistoryPromptZone.PREVIOUS_TURN
            )
        )
    }

    @Test
    fun formatContinuityNoticeRequiresExclusionFormatCardAndEarlierAssistant() {
        val assistantHistory = listOf(message(MessageRole.ASSISTANT, "回复"))
        val userHistory = listOf(message(MessageRole.USER, "输入"))

        assertTrue(
            ChatHistoryPromptPolicy.shouldIncludeFormatContinuityNotice(
                excludeAssistantStatusFromHistory = true,
                formatCardContent = "格式卡",
                earlierHistoryMessages = assistantHistory
            )
        )
        assertFalse(
            ChatHistoryPromptPolicy.shouldIncludeFormatContinuityNotice(
                excludeAssistantStatusFromHistory = false,
                formatCardContent = "格式卡",
                earlierHistoryMessages = assistantHistory
            )
        )
        assertFalse(
            ChatHistoryPromptPolicy.shouldIncludeFormatContinuityNotice(
                excludeAssistantStatusFromHistory = true,
                formatCardContent = null,
                earlierHistoryMessages = assistantHistory
            )
        )
        assertFalse(
            ChatHistoryPromptPolicy.shouldIncludeFormatContinuityNotice(
                excludeAssistantStatusFromHistory = true,
                formatCardContent = "格式卡",
                earlierHistoryMessages = userHistory
            )
        )
    }

    @Test
    fun blankAssistantBodyIsOmitted() {
        assertNull(
            ChatHistoryPromptPolicy.payloadText(
                renderedBody = "",
                hasSupportedImage = false
            )
        )
    }

    @Test
    fun statusOnlyAssistantBodyDoesNotProduceTimelineOnlyPayloadAfterExclusion() {
        val renderedBody = stripRoleplayStatusSegments(
            """
            ```status
            状态栏
            ```
            """.trimIndent()
        )

        assertNull(
            ChatHistoryPromptPolicy.payloadText(
                renderedBody = renderedBody,
                hasSupportedImage = false
            )
        )
    }

    @Test
    fun repeatedBlankAssistantImageRecordsAreAllOmitted() {
        val payloads = List(5) {
            ChatHistoryPromptPolicy.payloadText(
                renderedBody = "",
                hasSupportedImage = false
            )
        }

        assertEquals(emptyList<String>(), payloads.filterNotNull())
    }

    @Test
    fun nonBlankBodyContainsNoTimelinePrefix() {
        assertEquals(
            "正文",
            ChatHistoryPromptPolicy.payloadText(
                renderedBody = "正文",
                hasSupportedImage = false
            )
        )
    }

    @Test
    fun blankUserBodyIsAllowedOnlyWhenSupportedImageExists() {
        assertEquals(
            "",
            ChatHistoryPromptPolicy.payloadText(
                renderedBody = "",
                hasSupportedImage = true
            )
        )
    }

    @Test
    fun blankCompletedAssistantResponseCannotBePersisted() {
        assertThrows(IllegalStateException::class.java) {
            ChatHistoryPromptPolicy.requirePersistableAssistantBody("   ")
        }
    }

    private fun message(role: MessageRole, content: String): ChatMessage =
        ChatMessage(
            id = "$role-$content",
            sessionId = "session",
            role = role,
            content = content,
            createdAt = 1,
            updatedAt = 1
        )
}
