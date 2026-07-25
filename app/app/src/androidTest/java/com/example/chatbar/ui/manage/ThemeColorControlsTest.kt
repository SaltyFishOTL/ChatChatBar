package com.example.chatbar.ui.manage

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import com.example.chatbar.domain.appearance.DefaultThemeColorHsv
import com.example.chatbar.domain.appearance.ThemeColorHsv
import com.example.chatbar.ui.kit.CbHsvColorPicker
import com.example.chatbar.ui.kit.ChatBarTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Rule
import org.junit.Test

class ThemeColorControlsTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun hsvPicker_exposesThreeAdjustableSliders() {
        var observed = DefaultThemeColorHsv
        composeTestRule.setContent {
            var color by remember { mutableStateOf(DefaultThemeColorHsv) }
            ChatBarTheme {
                CbHsvColorPicker(
                    value = color,
                    onValueChange = {
                        color = it
                        observed = it
                    }
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("色相 168 度")
            .performTouchInput { swipeRight() }
        composeTestRule.onNodeWithContentDescription("饱和度 67%").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("亮度 56%").assertIsDisplayed()

        composeTestRule.runOnIdle {
            assertNotEquals(DefaultThemeColorHsv.hueDegrees, observed.hueDegrees)
        }
    }

    @Test
    fun dialog_resetThenApply_emitsDefaultOnce() {
        var applyCount = 0
        var applied: ThemeColorHsv? = null
        composeTestRule.setContent {
            ChatBarTheme {
                ThemeColorPickerDialog(
                    initialColor = ThemeColorHsv(220f, 0.8f, 0.7f),
                    onDismissRequest = {},
                    onApply = {
                        applyCount += 1
                        applied = it
                    }
                )
            }
        }

        composeTestRule.onNodeWithText("恢复默认").performClick()
        composeTestRule.onNodeWithText("应用").performClick()

        composeTestRule.runOnIdle {
            assertEquals(1, applyCount)
            assertEquals(DefaultThemeColorHsv.rgbKey(), applied?.rgbKey())
        }
    }

    @Test
    fun dialog_cancelDoesNotApply() {
        var applyCount = 0
        composeTestRule.setContent {
            ChatBarTheme {
                ThemeColorPickerDialog(
                    initialColor = ThemeColorHsv(220f, 0.8f, 0.7f),
                    onDismissRequest = {},
                    onApply = { applyCount += 1 }
                )
            }
        }

        composeTestRule.onNodeWithText("取消").performClick()

        composeTestRule.runOnIdle { assertEquals(0, applyCount) }
    }

    @Test
    fun historyAndDefaultSwatches_onlySelectPickerDraft() {
        val historyColor = ThemeColorHsv(30f, 0.6f, 0.5f)
        var selected: ThemeColorHsv? = null
        composeTestRule.setContent {
            ChatBarTheme {
                ThemeColorSettingControls(
                    current = ThemeColorHsv(200f, 0.7f, 0.7f),
                    history = listOf(historyColor),
                    onSelectColor = { selected = it }
                )
            }
        }

        composeTestRule.onNodeWithContentDescription(
            "历史主题色 1，H 30°  S 60%  V 50%"
        ).performClick()
        composeTestRule.runOnIdle {
            assertEquals(historyColor.rgbKey(), selected?.rgbKey())
        }

        composeTestRule.onNodeWithContentDescription("恢复 APP 默认主题色").performClick()
        composeTestRule.runOnIdle {
            assertEquals(DefaultThemeColorHsv.rgbKey(), selected?.rgbKey())
        }
    }
}
