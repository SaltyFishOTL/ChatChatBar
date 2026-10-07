package com.example.chatbar.ui.tutorial

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.chatbar.ui.kit.ChatBarTheme
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class TutorialScreenTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun tutorial_canAdvanceAndSkip() {
        var exited = false
        composeTestRule.setContent {
            ChatBarTheme {
                TutorialScreen(onExit = { exited = true })
            }
        }

        composeTestRule.onNodeWithText("欢迎使用 ChatBar").assertIsDisplayed()
        composeTestRule.onNodeWithText("下一步").performClick()
        composeTestRule.onNodeWithText("开始对话前的设置").assertIsDisplayed()
        composeTestRule.onNodeWithText("下一步").performClick()
        composeTestRule.onNodeWithText("配置硅基流动 API").assertIsDisplayed()
        composeTestRule.onNodeWithText("下一步").performClick()
        composeTestRule.onNodeWithText("进阶单元：其他 API 与新模型").assertIsDisplayed()
        composeTestRule.onNodeWithText("使用非硅基流动 API").assertIsDisplayed()
        composeTestRule.onNodeWithText("跳过").performClick()

        composeTestRule.runOnIdle { assertTrue(exited) }
    }

    @Test
    fun advancedTutorial_showsHiddenActionsAndCanAdvance() {
        composeTestRule.setContent {
            ChatBarTheme {
                TutorialScreen(onExit = {}, advanced = true)
            }
        }

        composeTestRule.onNodeWithText("进阶教程").assertIsDisplayed()
        composeTestRule.onNodeWithText("欢迎来到进阶教程").assertIsDisplayed()
        composeTestRule.onNodeWithText("下一步").performClick()
        composeTestRule.onNodeWithText("会话列表的长按操作").assertIsDisplayed()
        composeTestRule.onNodeWithText("改名、置顶与删除").performScrollTo().assertIsDisplayed()
    }

    @Test
    fun advancedTutorial_documentsMomentTextLongPressActions() {
        composeTestRule.setContent {
            ChatBarTheme {
                TutorialScreen(onExit = {}, advanced = true)
            }
        }

        repeat(5) {
            composeTestRule.onNodeWithText("下一步").performClick()
        }

        composeTestRule.onNodeWithText("朋友圈与社区").assertIsDisplayed()
        composeTestRule.onNodeWithText(
            "点击动态右上角编辑按钮，或长按文字选择“编辑朋友圈”，可修改文案、切换所属角色卡的人物与头像；选择“复制文字”可复制完整正文。"
        ).performScrollTo().assertIsDisplayed()
    }
}
