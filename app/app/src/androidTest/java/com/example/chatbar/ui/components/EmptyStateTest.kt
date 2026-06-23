package com.example.chatbar.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import com.example.chatbar.ui.kit.ChatBarTheme
import org.junit.Rule
import org.junit.Test

class EmptyStateTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun emptyState_displaysTitleAndDescription() {
        composeTestRule.setContent {
            ChatBarTheme {
                EmptyState(
                    title = "No sessions",
                    description = "Create a session to begin."
                )
            }
        }

        composeTestRule.onNodeWithText("No sessions").assertIsDisplayed()
        composeTestRule.onNodeWithText("Create a session to begin.").assertIsDisplayed()
    }
}
