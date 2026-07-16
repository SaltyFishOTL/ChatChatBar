package com.example.chatbar.domain.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ChatHistoryPromptPolicyTest {
    @Test
    fun blankAssistantBodyDoesNotProduceTimelineOnlyPayload() {
        assertNull(
            ChatHistoryPromptPolicy.payloadText(
                renderedBody = "",
                timelinePrefix = "[T12]\n",
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
                timelinePrefix = "[T12]\n",
                hasSupportedImage = false
            )
        )
    }

    @Test
    fun repeatedBlankAssistantImageRecordsAreAllOmitted() {
        val payloads = List(5) {
            ChatHistoryPromptPolicy.payloadText(
                renderedBody = "",
                timelinePrefix = "[T12]\n",
                hasSupportedImage = false
            )
        }

        assertEquals(emptyList<String>(), payloads.filterNotNull())
    }

    @Test
    fun nonBlankBodyKeepsTimelinePrefix() {
        assertEquals(
            "[T7]\n正文",
            ChatHistoryPromptPolicy.payloadText(
                renderedBody = "正文",
                timelinePrefix = "[T7]\n",
                hasSupportedImage = false
            )
        )
    }

    @Test
    fun blankUserBodyIsAllowedOnlyWhenSupportedImageExists() {
        assertEquals(
            "[T3]\n",
            ChatHistoryPromptPolicy.payloadText(
                renderedBody = "",
                timelinePrefix = "[T3]\n",
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
}
