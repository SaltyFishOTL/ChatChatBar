package com.example.chatbar.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.domain.chat.MessageAlternativeVersionPolicy
import com.example.chatbar.ui.kit.ChatBarTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReplyDeletionConfirmationTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun deletingCandidatePreservesSiblingAndSharedImage() {
        val message = mutableStateOf(ChatMessage("message", "session", MessageRole.ASSISTANT, "second",
            createdAt = 1, updatedAt = 1, alternatives = listOf("first", "second"),
            alternativeVersionIds = listOf("first-id", "second-id"), currentAlternativeIndex = 1,
            currentAlternativeVersionId = "second-id", images = listOf("synthetic-shared.png")))
        var wholeDeletes = 0
        rule.setContent { ChatBarTheme {
            ReplyDeletionConfirmation("message", "second-id", true, {},
                { message.value = requireNotNull(MessageAlternativeVersionPolicy.remove(message.value, "second-id")) },
                { wholeDeletes++ })
        } }
        rule.onNodeWithText("删除备选").assertIsDisplayed()
        rule.onNodeWithText("删除整条回复").assertDoesNotExist()
        rule.onNodeWithText("删除此备选").performClick()
        rule.runOnIdle {
            assertEquals(listOf("first"), MessageAlternativeVersionPolicy.versions(message.value).map { it.content })
            assertEquals(listOf("synthetic-shared.png"), message.value.images)
            assertEquals(0, wholeDeletes)
        }
    }

    @Test fun wholeDeletionRequiresEnteringSecondConfirmationAndCancelReturns() {
        var candidateDeletes = 0
        var wholeDeletes = 0
        rule.setContent { ChatBarTheme {
            ReplyDeletionConfirmation("message", "version", true, {}, { candidateDeletes++ }, { wholeDeletes++ })
        } }
        rule.onNodeWithText("删除全部备选").performClick()
        rule.onNodeWithText("删除整条回复").assertIsDisplayed()
        rule.runOnIdle { assertEquals(0, wholeDeletes); assertEquals(0, candidateDeletes) }
        rule.onNodeWithText("取消").performClick()
        rule.onNodeWithText("删除备选").assertIsDisplayed()
        rule.onNodeWithText("删除全部备选").performClick()
        rule.onNodeWithText("全部删除", substring = false).performClick()
        rule.runOnIdle { assertEquals(1, wholeDeletes); assertEquals(0, candidateDeletes) }
    }

    @Test fun lastCandidateCanBeDeletedWithExistingConfirmation() {
        var candidateDeletes = 0
        var wholeDeletes = 0
        rule.setContent { ChatBarTheme {
            ReplyDeletionConfirmation("message", "only-version", false, {}, { candidateDeletes++ }, { wholeDeletes++ })
        } }
        rule.onNodeWithText("删除此备选").assertIsEnabled()
        rule.onNodeWithText("删除全部备选").assertIsDisplayed()
        rule.onNodeWithText("删除此备选").performClick()
        rule.runOnIdle { assertEquals(1, candidateDeletes); assertEquals(0, wholeDeletes) }
    }
}
