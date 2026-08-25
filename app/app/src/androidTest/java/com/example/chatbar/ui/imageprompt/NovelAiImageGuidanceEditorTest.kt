package com.example.chatbar.ui.imageprompt

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithContentDescription
import com.example.chatbar.domain.image.NovelAiGenerationAction
import com.example.chatbar.domain.image.NovelAiImageGuidanceDraft
import com.example.chatbar.domain.image.NovelAiImageModel
import com.example.chatbar.domain.image.NovelAiStudioAssetRef
import com.example.chatbar.ui.kit.ChatBarTheme
import org.junit.Rule
import org.junit.Test

class NovelAiImageGuidanceEditorTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun v5HidesUnavailableReferenceTabs() {
        composeTestRule.setContent {
            ChatBarTheme {
                NovelAiImageGuidanceEditor(
                    initial = NovelAiImageGuidanceDraft(),
                    model = NovelAiImageModel.V5_FULL,
                    onDismiss = {},
                    onPickImage = {},
                    stagedAsset = null,
                    onConsumeStagedAsset = {},
                    onSaveBitmap = { _, _, _ -> },
                    onCheckpoint = {},
                    onSave = {}
                )
            }
        }

        composeTestRule.onNodeWithText("图生图").assertIsDisplayed()
        composeTestRule.onNodeWithText("局部重绘").assertIsDisplayed()
        composeTestRule.onAllNodesWithText("精确").assertCountEquals(0)
        composeTestRule.onAllNodesWithText("氛围").assertCountEquals(0)
    }

    @Test
    fun v45ShowsAllCompactGuidanceTabs() {
        composeTestRule.setContent {
            ChatBarTheme {
                NovelAiImageGuidanceEditor(
                    initial = NovelAiImageGuidanceDraft(),
                    model = NovelAiImageModel.V4_5_FULL,
                    onDismiss = {},
                    onPickImage = {},
                    stagedAsset = null,
                    onConsumeStagedAsset = {},
                    onSaveBitmap = { _, _, _ -> },
                    onCheckpoint = {},
                    onSave = {}
                )
            }
        }

        listOf("图生图", "局部重绘", "精确", "氛围").forEach { label ->
            composeTestRule.onNodeWithText(label).assertIsDisplayed()
        }
    }

    @Test
    fun activeImageToImageShowsExplicitStateAndClearAction() {
        composeTestRule.setContent {
            ChatBarTheme {
                NovelAiImageGuidanceEditor(
                    initial = NovelAiImageGuidanceDraft(
                        action = NovelAiGenerationAction.IMAGE_TO_IMAGE,
                        baseImage = NovelAiStudioAssetRef(path = "/missing/source.png", width = 832, height = 1216)
                    ),
                    model = NovelAiImageModel.V4_5_FULL,
                    onDismiss = {},
                    onPickImage = {},
                    stagedAsset = null,
                    onConsumeStagedAsset = {},
                    onSaveBitmap = { _, _, _ -> },
                    onCheckpoint = {},
                    onSave = {}
                )
            }
        }

        composeTestRule.onNodeWithText("已启用 · 832×1216").assertIsDisplayed()
        composeTestRule.onNodeWithText("清空").assertIsDisplayed()
        composeTestRule.onNodeWithText("完成").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("撤销").assertExists()
        composeTestRule.onNodeWithContentDescription("重做").assertExists()
    }
}
