package com.example.chatbar.ui.kit

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import org.junit.Rule
import org.junit.Test

class FullscreenTextEditorStateTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun enhancedEditor_replacesOnlyTransientDraftUntilConfirm() {
        composeTestRule.setContent {
            var committed by remember { mutableStateOf("1girl, blue ha") }
            var visible by remember { mutableStateOf(true) }
            ChatBarTheme {
                Column {
                    CbText("已提交：$committed")
                    CbButton("打开编辑", onClick = { visible = true })
                    if (visible) {
                        val editorState = rememberFullscreenTextEditorState(
                            TextFieldValue(committed, TextRange(committed.length))
                        )
                        FullscreenTextEditor(
                            state = editorState,
                            title = "编辑 Prompt",
                            visible = true,
                            onDismiss = { visible = false },
                            onConfirm = { value ->
                                committed = value.text
                                visible = false
                            },
                            topContent = {
                                CbButton("插入候选", onClick = {
                                    editorState.replace(
                                        TextFieldValue(
                                            "1girl, blue_hair",
                                            TextRange("1girl, blue_hair".length)
                                        )
                                    )
                                })
                            }
                        )
                    }
                }
            }
        }

        composeTestRule.onNodeWithText("插入候选").assertIsDisplayed().performClick()
        composeTestRule.onNodeWithContentDescription("退出").performClick()
        composeTestRule.onNodeWithText("已提交：1girl, blue ha").assertIsDisplayed()

        composeTestRule.onNodeWithText("打开编辑").performClick()
        composeTestRule.onNodeWithText("插入候选").performClick()
        composeTestRule.onNodeWithContentDescription("确认").performClick()
        composeTestRule.onNodeWithText("已提交：1girl, blue_hair").assertIsDisplayed()
    }
}
