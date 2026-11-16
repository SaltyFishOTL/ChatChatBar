package com.example.chatbar.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.chat.MessageFormatRepairPolicy
import com.example.chatbar.domain.moment.MomentImageVersions
import com.example.chatbar.ui.kit.ChatBarTheme
import com.example.chatbar.ui.moments.MomentImageVersionControls
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class ReplyMediaVersionControlsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun undoThenRedoUsesRetainedResultWithoutGeneration() {
        val original = ChatMessage("m", "s", MessageRole.ASSISTANT, "before", createdAt = 1, updatedAt = 1)
        val state = mutableStateOf(MessageFormatRepairPolicy.replaceCurrentDisplayContent(original, "after",
            MessageFormatRepairPolicy.completedRepairNotice("before", "after")))
        rule.setContent { ChatBarTheme {
            MessageFormatRepairNoticeRow(state.value, false,
                { state.value = requireNotNull(MessageFormatRepairPolicy.restoreOriginal(state.value)) },
                { state.value = requireNotNull(MessageFormatRepairPolicy.redoRepair(state.value)) })
        } }
        rule.onNodeWithText("撤销修复").performClick()
        rule.runOnIdle { assertEquals("before", state.value.displayContent) }
        rule.onNodeWithText("重做修复").assertIsDisplayed()
        rule.onNodeWithText("重做修复").performClick()
        rule.runOnIdle { assertEquals("after", state.value.displayContent) }
        rule.onNodeWithText("撤销修复").assertIsDisplayed()
    }

    @Test fun previousNextSwitchesImagePromptWithoutLosingEitherCandidate() {
        val first = MomentPost("p", "card", "s", senderName = "name", text = "post",
            imagePath = "first.png", imagePrompt = "first prompt", scheduledAt = 1, generatedAt = 2)
        val state = mutableStateOf(MomentImageVersions.append(first, "second.png", "second prompt", null))
        rule.setContent { ChatBarTheme {
            MomentImageVersionControls(state.value) { id -> state.value = MomentImageVersions.select(state.value, id) }
        } }
        rule.onNodeWithText("2/2").assertIsDisplayed()
        rule.onNodeWithText("›").assertIsNotEnabled()
        rule.onNodeWithText("‹").performClick()
        rule.onNodeWithText("1/2").assertIsDisplayed()
        rule.runOnIdle { assertEquals("first prompt", state.value.imagePrompt); assertEquals(2, state.value.imageVersions.size) }
        rule.onNodeWithText("›").performClick()
        rule.runOnIdle { assertEquals("second.png", state.value.imagePath) }
    }
}
