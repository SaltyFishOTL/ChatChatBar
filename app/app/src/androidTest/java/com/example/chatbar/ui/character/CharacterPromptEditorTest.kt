package com.example.chatbar.ui.character

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.chatbar.data.local.entity.CharacterInfo
import com.example.chatbar.domain.chat.AiStreamProgress
import com.example.chatbar.ui.kit.ChatBarTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class CharacterPromptEditorTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Test fun promptAssistanceAndFullscreenPreserveCharacterDraft() {
        val initial = CharacterInfo("character", "人物", imagePrompt = "blue eyes")
        var saved: CharacterInfo? = null
        rule.setContent {
            var value by remember { mutableStateOf(initial) }
            ChatBarTheme {
                CharacterDialog(
                    original = initial, value = value, nameConflict = false, structured = true,
                    avatarImageState = CharacterAvatarImageUiState(),
                    appearanceImageState = CharacterAppearanceImageUiState(), appearanceProgress = AiStreamProgress(),
                    fishAudioConfigured = false, voicePickerState = CharacterVoicePickerUiState(), freeformAvatarPrompt = "",
                    onValueChange = { value = it }, onDismiss = {}, onPickImage = {}, onPickAppearanceImage = {},
                    onCancelAppearanceImage = {}, onDiscardAppearanceImage = {}, onApplyAppearanceImage = {},
                    onClearAvatar = {}, onFreeformAvatarPromptChange = {}, onGenerateAvatar = {},
                    onCancelAvatar = {}, onDiscardAvatar = {}, onApplyAvatar = {}, onSaveAvatarToGallery = {},
                    onSearchVoices = {}, onPreviewVoice = { _, _ -> }, onStopVoicePreview = {},
                    onSave = { saved = it }, onFullscreen = { _, _, _ -> error("Prompt must use Tag editor") }
                )
            }
        }
        rule.onNodeWithText("blue eyes").performScrollTo().performClick()
        val toggle = hasContentDescription("开启 Prompt 中文翻译") or hasContentDescription("关闭 Prompt 中文翻译")
        rule.onNode(toggle).assertIsDisplayed()
        // The last fullscreen action belongs to the character prompt, after ordinary biography fields.
        val actions = rule.onAllNodesWithContentDescription("全屏编辑")
        actions[actions.fetchSemanticsNodes().lastIndex].performScrollTo().performClick()
        rule.onNodeWithText("编辑人物设定").assertDoesNotExist()
        rule.onNode(toggle).assertIsDisplayed()
        rule.onNodeWithText("blue eyes").performTextReplacement("discarded")
        rule.onNodeWithContentDescription("退出").performClick()
        rule.onNodeWithText("blue eyes").performScrollTo().assertIsDisplayed()
        val reopened = rule.onAllNodesWithContentDescription("全屏编辑")
        reopened[reopened.fetchSemanticsNodes().lastIndex].performScrollTo().performClick()
        rule.onNodeWithText("blue eyes").performTextReplacement("green eyes, long hair")
        rule.onNodeWithContentDescription("确认").performClick()
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        rule.onNodeWithText("保存").assertIsEnabled().performClick()
        rule.runOnIdle { assertEquals(initial.copy(imagePrompt = "green eyes, long hair"), saved) }
    }
}
