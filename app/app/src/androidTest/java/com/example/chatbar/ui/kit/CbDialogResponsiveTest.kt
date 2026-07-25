package com.example.chatbar.ui.kit

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.hasScrollAction
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeUp
import androidx.compose.ui.unit.dp
import org.junit.Rule
import org.junit.Test

class CbDialogResponsiveTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun tallContent_keepsActionsVisibleAndBodyScrollable() {
        composeTestRule.setContent {
            ChatBarTheme {
                CbDialog(
                    onDismissRequest = {},
                    title = "短屏弹窗",
                    dismiss = {
                        CbButton("取消", {}, variant = ButtonVariant.Ghost)
                    },
                    confirm = {
                        CbButton("确认", {})
                    }
                ) {
                    Column(
                        Modifier
                            .fillMaxWidth()
                            .verticalScroll(rememberScrollState())
                    ) {
                        CbText("正文顶部")
                        Spacer(Modifier.height(2_000.dp))
                        CbText("正文底部")
                    }
                }
            }
        }

        composeTestRule.onNodeWithText("短屏弹窗").assertIsDisplayed()
        composeTestRule.onNodeWithText("取消").assertIsDisplayed()
        composeTestRule.onNodeWithText("确认").assertIsDisplayed()

        repeat(6) {
            composeTestRule.onNode(hasScrollAction()).performTouchInput { swipeUp() }
        }
        composeTestRule.onNodeWithText("正文底部").assertIsDisplayed()
        composeTestRule.onNodeWithText("确认").assertIsDisplayed()
    }
}
