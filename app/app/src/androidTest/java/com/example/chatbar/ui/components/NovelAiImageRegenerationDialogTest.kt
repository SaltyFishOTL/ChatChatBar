package com.example.chatbar.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithContentDescription
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.example.chatbar.data.local.entity.GeneratedImageCharacterPrompt
import com.example.chatbar.domain.image.NovelAiImageRegenerationDraft
import com.example.chatbar.ui.kit.ChatBarTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class NovelAiImageRegenerationDialogTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun fullscreenEdit_hidesDialogWindowUntilEditorCloses() {
        val initialDraft = NovelAiImageRegenerationDraft(
            baseCaption = "masterpiece, 1girl",
            characterPrompts = listOf(
                GeneratedImageCharacterPrompt(
                    prompt = "black hair",
                    centerX = 0.5f,
                    centerY = 0.5f
                )
            ),
            negativePrompt = "lowres",
            sizePreset = "PORTRAIT",
            width = 832,
            height = 1216
        )

        composeTestRule.setContent {
            var draft by remember { mutableStateOf(initialDraft) }
            ChatBarTheme {
                NovelAiImageRegenerationDialog(
                    draft = draft,
                    loading = false,
                    onDraftChange = { draft = it },
                    onDismiss = {},
                    onRegenerate = {}
                )
            }
        }

        composeTestRule.onAllNodesWithContentDescription("全屏编辑")[0].performClick()

        assertTrue(
            composeTestRule.onAllNodesWithText("图片操作").fetchSemanticsNodes().isEmpty()
        )
        composeTestRule.onNodeWithText("编辑主提示词").assertIsDisplayed()

        composeTestRule.onNodeWithContentDescription("退出").performClick()

        composeTestRule.onNodeWithText("图片操作").assertIsDisplayed()
    }
}
