package com.example.chatbar.domain.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class ChatHistoryPromptPolicyTest {
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
}
