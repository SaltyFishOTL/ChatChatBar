package com.example.chatbar.ui.chat

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.chat.SessionCharacterParticipationPolicy
import com.example.chatbar.ui.kit.*
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ChatPeopleControlsTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private lateinit var avatar: File

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        avatar = File(context.cacheDir, "synthetic-people-avatar.png")
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
        try { bitmap.eraseColor(Color.CYAN); avatar.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
        finally { bitmap.recycle() }
    }

    @After fun cleanup() { avatar.delete() }

    @Test fun countOpensDedicatedListAndUpdatesAfterToggleAtPhoneWidth() {
        val card = card()
        val original = ChatSession.create(card.id, "synthetic")
        rule.setContent {
            var session by remember { mutableStateOf(original) }
            var open by remember { mutableStateOf(false) }
            ChatBarTheme {
                Box(Modifier.width(320.dp)) {
                    CbTopBar("合成很长的对话标题用于确认人物计数和调试入口仍可用", actions = {
                        ChatPeopleButton(session, card) { open = true }
                        CbIconButton(AppIcons.BugReport, "调试日志", {})
                        CbIconButton(AppIcons.Tune, "会话设置", {})
                    })
                }
                if (open) ChatPeopleDialog(session, card, true, null,
                    { id, enabled -> session = SessionCharacterParticipationPolicy.change(session, card, id, enabled) },
                    {}, { open = false })
            }
        }
        val button = rule.onNodeWithContentDescription("会话人物，已启用 1/2")
        assertTrue(button.fetchSemanticsNode().boundsInRoot.right <=
            rule.onNodeWithContentDescription("调试日志").fetchSemanticsNode().boundsInRoot.left)
        button.performClick()
        rule.onNodeWithText("会话人物 · 1/2").assertIsDisplayed()
        rule.onNodeWithContentDescription("合成人物甲").assertIsOn().performClick()
        rule.onNodeWithText("会话人物 · 0/2").assertIsDisplayed()
        rule.onNodeWithText("完成").performClick()
        rule.onNodeWithContentDescription("会话人物，已启用 0/2").assertIsDisplayed()
        screenshot("people-topbar-320dp.png")
    }

    @Test fun cardDisabledSwitchNavigatesToEditorWithoutEnablingPerson() {
        val card = card()
        val session = ChatSession.create(card.id, "synthetic")
        var edits = 0
        var mutations = 0
        rule.setContent { ChatBarTheme {
            ChatPeopleDialog(session, card, true, null, { _, _ -> mutations++ }, { edits++ }, {})
        } }
        rule.onNodeWithText("合成人物甲").assertIsDisplayed()
        rule.onNodeWithText("合成人物乙").assertIsDisplayed()
        rule.onNodeWithText("卡内已关闭 · 点此编辑角色卡").assertIsDisplayed()
        rule.onAllNodesWithContentDescription("头像").assertCountEquals(2)
        screenshot("people-list-phone.png")
        rule.onNodeWithContentDescription("合成人物乙已在角色卡中关闭，点击编辑角色卡").performClick()
        rule.runOnIdle { assertEquals(1, edits); assertEquals(0, mutations) }
    }

    @Test fun busyListBlocksMutationsAndCardNavigationAndShowsError() {
        val card = card()
        var actions = 0
        rule.setContent { ChatBarTheme {
            ChatPeopleDialog(ChatSession.create(card.id, "synthetic"), card, false, "synthetic visible save error",
                { _, _ -> actions++ }, { actions++ }, {})
        } }
        rule.onNodeWithContentDescription("合成人物甲").assertIsNotEnabled()
        rule.onNodeWithContentDescription("合成人物乙已在角色卡中关闭，点击编辑角色卡").assertIsNotEnabled()
        rule.onNodeWithText("synthetic visible save error").assertIsDisplayed()
        rule.runOnIdle { assertEquals(0, actions) }
    }

    @Test fun returningFromCardEditRefreshesCountAndEnablesItsSwitch() {
        val original = card()
        var editRequested = false
        var updateCard: (() -> Unit)? = null
        rule.setContent {
            var card by remember { mutableStateOf(original) }
            var open by remember { mutableStateOf(false) }
            val session = remember { ChatSession.create(original.id, "synthetic") }
            updateCard = { card = card.copy(characters = card.characters.map { it.copy(enabled = true) }) }
            ChatBarTheme {
                ChatPeopleButton(session, card) { open = true }
                if (open) ChatPeopleDialog(session, card, true, null, { _, _ -> },
                    { editRequested = true; open = false }, { open = false })
            }
        }
        rule.onNodeWithContentDescription("会话人物，已启用 1/2").performClick()
        rule.onNodeWithContentDescription("合成人物乙已在角色卡中关闭，点击编辑角色卡").performClick()
        rule.runOnIdle { assertTrue(editRequested); updateCard!!() }
        rule.onNodeWithContentDescription("会话人物，已启用 2/2").performClick()
        rule.onNodeWithContentDescription("合成人物乙").assertIsOn().assertIsEnabled()
    }

    private fun card() = CharacterCard.create("synthetic people card").copy(characters = listOf(
        CharacterInfo("synthetic-person-a", "合成人物甲", appearanceImage = avatar.absolutePath),
        CharacterInfo("synthetic-person-b", "合成人物乙", appearanceImage = avatar.absolutePath, enabled = false)
    ))

    private fun screenshot(name: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val directory = File(context.getExternalFilesDir(null), "alignment-people-evidence").apply { mkdirs() }
        val target = if (name == "people-list-phone.png") rule.onNode(isDialog()) else rule.onRoot()
        File(directory, name).outputStream().use {
            target.captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
