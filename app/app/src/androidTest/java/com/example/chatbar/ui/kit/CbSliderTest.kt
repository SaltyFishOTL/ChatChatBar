package com.example.chatbar.ui.kit

import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class CbSliderTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun horizontalDragChangesDiscreteValue() {
        var observed = 2f
        composeTestRule.setContent {
            var value by remember { mutableFloatStateOf(2f) }
            ChatBarTheme {
                CbSlider(
                    value = value,
                    onValueChange = {
                        value = it
                        observed = it
                    },
                    valueRange = 1f..6f,
                    steps = 4,
                    contentDescription = "长期记忆分组"
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("长期记忆分组")
            .performTouchInput { swipeRight() }

        composeTestRule.runOnIdle { assertTrue(observed > 2f) }
    }
}
