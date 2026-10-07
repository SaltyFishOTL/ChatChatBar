package com.example.chatbar.ui.kit

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.text.input.InputTransformation
import androidx.compose.foundation.text.input.byValue
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextReplacement
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

    private fun input(tag: String) = composeTestRule.onNode(hasSetTextAction() and (hasTestTag(tag) or hasAnyAncestor(hasTestTag(tag))), useUnmergedTree = true)

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

        input("state-input").performTextInput("（）")
        input("state-input").performTextInputSelection(TextRange(1))
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

        input("digits-input").performTextInput("a1b2")
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

        input("secure-input").assert(
            SemanticsMatcher.expectValue(SemanticsProperties.Password, Unit)
        )
    }

    @Test
    fun numericValueCanClearLastDigitThenRetypeAndRestoreUnfinishedDraftOnBlur() {
        var observed = 1
        composeTestRule.setContent {
            var value by remember { mutableStateOf(1) }
            ChatBarTheme {
                Column {
                    CbNumberValueInput(
                        value.toString(),
                        { value = it.toInt(); observed = value },
                        modifier = Modifier.testTag("number"),
                        isValid = { it.toIntOrNull()?.let { number -> number > 0 } == true }
                    )
                    CbInput("", {}, modifier = Modifier.testTag("other"))
                }
            }
        }
        val field = input("number")
        field.performClick()
        field.performTextClearance()
        field.assertTextEquals("")
        composeTestRule.runOnIdle { assertEquals(1, observed) }
        field.performTextInput("23")
        field.assertTextEquals("23")
        composeTestRule.runOnIdle { assertEquals(23, observed) }
        field.performTextClearance()
        input("other").performClick()
        field.assertTextEquals("23")
    }

    @Test
    fun decimalEchoPreservesTrailingPointAndAllowsNegativeNumber() {
        var observed = 0.5
        composeTestRule.setContent {
            var value by remember { mutableStateOf(0.5) }
            ChatBarTheme {
                CbNumberValueInput(
                    value.toString(),
                    { value = it.toDouble(); observed = value },
                    modifier = Modifier.testTag("decimal"),
                    decimal = true,
                    signed = true,
                    isValid = { it.toDoubleOrNull()?.isFinite() == true }
                )
            }
        }
        val field = input("decimal")
        field.performClick()
        field.performTextReplacement("1.")
        field.assertTextEquals("1.")
        field.performTextInput("25")
        field.assertTextEquals("1.25")
        composeTestRule.runOnIdle { assertEquals(1.25, observed, 0.0) }
        field.performTextClearance()
        field.performTextInput("-")
        field.assertTextEquals("-")
        field.performTextInput("2.5")
        field.assertTextEquals("-2.5")
        composeTestRule.runOnIdle { assertEquals(-2.5, observed, 0.0) }
    }
}
