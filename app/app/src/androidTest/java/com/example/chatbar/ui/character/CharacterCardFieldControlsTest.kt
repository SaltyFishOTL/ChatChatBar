package com.example.chatbar.ui.character

import android.os.Build
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.domain.card.CharacterGreetingChoice
import com.example.chatbar.ui.components.CharacterGreetingPickerDialog
import com.example.chatbar.ui.kit.ChatBarTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CharacterCardFieldControlsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun metadataEditsDoNotNormalizeUntouchedTagsOrOpaqueExtensions() {
        var tags by mutableStateOf(listOf(" imported ", "duplicate", "duplicate"))
        var creator by mutableStateOf("original creator")
        var version by mutableStateOf("original version")
        val raw = " {\"future\": [1,{\"unknown\":null}]} \n"
        var copied: (() -> String?)? = null
        rule.setContent { ChatBarTheme {
            val clipboard = LocalClipboardManager.current
            SideEffect { copied = { clipboard.getText()?.text } }
            Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
                CharacterCardMetadataEditor(tags, { tags = it }, creator, { creator = it }, version, { version = it }, raw)
            }
        } }
        rule.onNodeWithText(" imported ").assertIsDisplayed()
        rule.onAllNodesWithText("duplicate").assertCountEquals(2)
        rule.onAllNodes(hasSetTextAction())[1].performScrollTo().performTextReplacement("edited creator")
        rule.onAllNodes(hasSetTextAction())[2].performScrollTo().performTextReplacement("2.0")
        rule.onAllNodes(hasSetTextAction())[0].performScrollTo().performTextReplacement(" imported ")
        rule.onNodeWithText("添加").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(listOf(" imported ", "duplicate", "duplicate"), tags) }
        rule.onAllNodesWithContentDescription("移除标签 duplicate")[0].performScrollTo().performClick()
        rule.onNodeWithText("复制扩展").performScrollTo().performClick()
        rule.runOnIdle {
            assertEquals(listOf(" imported ", "duplicate"), tags)
            assertEquals("edited creator", creator); assertEquals("2.0", version)
            assertEquals(raw, copied!!.invoke())
        }
    }

    @Test fun alternateEditorKeepsEmptyEntriesAndSupportsReorderEditAndDelete() {
        var greetings by mutableStateOf(listOf("first", "", "second"))
        rule.setContent { ChatBarTheme { Column(Modifier.width(320.dp).verticalScroll(rememberScrollState())) {
            CharacterAlternateGreetingsEditor(greetings, { greetings = it }, { _, _ -> })
        } } }
        rule.onNodeWithContentDescription("下移备用开场 1").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(listOf("", "first", "second"), greetings) }
        rule.onAllNodes(hasSetTextAction())[1].performScrollTo().performTextReplacement("changed")
        rule.onNodeWithContentDescription("删除备用开场 1").performScrollTo().performClick()
        rule.onNodeWithText("添加备用开场").performScrollTo().performClick()
        rule.runOnIdle { assertEquals(listOf("changed", "second", ""), greetings) }
    }

    @Test fun greetingPreviewAndConfirmUseOriginalNonblankPositionAndExactText() {
        val card = CharacterCard.create("合成角色", greeting = "main preview").copy(alternateGreetings = listOf("", " alternative preview \n"))
        var selected: CharacterGreetingChoice? = null
        rule.setContent { ChatBarTheme { CharacterGreetingPickerDialog(card, false, {}, { selected = it }) } }
        rule.onNodeWithText("main preview").assertIsDisplayed()
        rule.onNodeWithText("备用开场 1").assertDoesNotExist()
        rule.onNodeWithText("备用开场 2").performClick()
        rule.onNodeWithText(" alternative preview \n").assertIsDisplayed()
        screenshot("greeting-preview")
        rule.onNodeWithText("创建会话").performClick()
        rule.runOnIdle { assertEquals(CharacterGreetingChoice(card.id, 2, " alternative preview \n"), selected) }
    }

    @Test fun cancelMakesNoSelectionAndChangedCardResetsToMainWhileBusyBlocksActions() {
        var card by mutableStateOf(CharacterCard.create("synthetic", greeting = "main").copy(alternateGreetings = listOf("old")))
        var busy by mutableStateOf(false)
        var confirms = 0
        var dismisses = 0
        rule.setContent { ChatBarTheme { CharacterGreetingPickerDialog(card, busy, { dismisses++ }, { confirms++ }) } }
        rule.onNodeWithText("取消").performClick()
        rule.runOnIdle { assertEquals(1, dismisses); assertEquals(0, confirms) }
        rule.onNodeWithText("备用开场 1").performClick()
        rule.runOnIdle { card = card.copy(alternateGreetings = listOf("new")) }
        rule.onNodeWithText("main").assertIsDisplayed()
        rule.onNodeWithText("old").assertDoesNotExist()
        rule.runOnIdle { busy = true }
        rule.onNodeWithText("正在创建…").assertIsNotEnabled()
        rule.onNodeWithText("取消").assertIsNotEnabled()
        rule.runOnIdle { assertEquals(0, confirms) }
    }

    private fun screenshot(name: String) {
        if (Build.VERSION.SDK_INT < 28) return // Old Compose dialog capture unsupported; behavior checks still run.
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "card-fields-review/$name.png")
        file.parentFile!!.mkdirs()
        file.outputStream().use { assertTrue(rule.onNode(isDialog()).captureToImage().asAndroidBitmap()
            .compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it)) }
    }
}
