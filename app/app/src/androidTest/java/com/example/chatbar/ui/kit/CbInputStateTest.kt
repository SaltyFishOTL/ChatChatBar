package com.example.chatbar.ui.kit

import androidx.activity.ComponentActivity
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.byValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextInputSelection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class CbInputStateTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun textSelectionAndExternalClearRoundTrip() {
        var observed = TextFieldValue()
        lateinit var setExternal: (TextFieldValue) -> Unit
        composeTestRule.setContent {
            var value by remember { mutableStateOf(TextFieldValue()) }
            setExternal = {
                value = it
                observed = it
            }
            ChatBarTheme {
                CbInput(
                    value = value,
                    onValueChange = {
                        value = it
                        observed = it
                    },
                    modifier = Modifier.testTag("state-input")
                )
            }
        }

        composeTestRule.onNodeWithTag("state-input").performTextInput("（）")
        composeTestRule.onNodeWithTag("state-input").performTextInputSelection(TextRange(1))
        composeTestRule.runOnIdle {
            assertEquals("（）", observed.text)
            assertEquals(TextRange(1), observed.selection)
            setExternal(TextFieldValue())
        }
        composeTestRule.runOnIdle { assertEquals("", observed.text) }
    }

    @Test
    fun inputTransformationFiltersBeforeCallback() {
        var observed = ""
        composeTestRule.setContent {
            var value by remember { mutableStateOf("") }
            ChatBarTheme {
                CbInput(
                    value = value,
                    onValueChange = {
                        value = it
                        observed = it
                    },
                    modifier = Modifier.testTag("digits-input"),
                    inputTransformation = InputTransformation.byValue { _, proposed ->
                        proposed.filter(Char::isDigit)
                    }
                )
            }
        }

        composeTestRule.onNodeWithTag("digits-input").performTextInput("a1b2")
        composeTestRule.runOnIdle { assertEquals("12", observed) }
    }

    @Test
    fun secureInputExposesPasswordSemantics() {
        composeTestRule.setContent {
            ChatBarTheme {
                CbInput(
                    value = "secret",
                    onValueChange = {},
                    modifier = Modifier.testTag("secure-input"),
                    secure = true
                )
            }
        }

        composeTestRule.onNodeWithTag("secure-input").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.Password, Unit)
        )
    }
}
