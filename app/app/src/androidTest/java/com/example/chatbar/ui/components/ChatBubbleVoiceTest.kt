package com.example.chatbar.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.FishAudioVoiceBinding
import com.example.chatbar.data.local.entity.GeneratedVoiceMessage
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.repository.VoiceMessagePlacement
import com.example.chatbar.domain.voice.VoicePlaybackState
import com.example.chatbar.ui.kit.ChatBarTheme
import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ChatBubbleVoiceTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun playingVoice_showsStopActionAndInvokesCallback() {
        val audioFile = File(composeTestRule.activity.cacheDir, "chat-bubble-voice.mp3")
        audioFile.writeBytes(byteArrayOf(0))
        val message = ChatMessage(
            id = "message",
            sessionId = "session",
            role = MessageRole.ASSISTANT,
            content = "",
            createdAt = 1,
            updatedAt = 1
        )
        val voice = GeneratedVoiceMessage(
            id = "voice",
            sessionId = message.sessionId,
            messageId = message.id,
            sourceOrder = 0,
            sourceSegmentKind = "dialogue",
            sourceSpeakerName = "角色",
            sourceText = "你好",
            taggedText = "你好",
            characterId = "character",
            characterName = "角色",
            voice = FishAudioVoiceBinding(
                referenceId = "reference",
                title = "测试音色"
            ),
            fishModelId = "model",
            audioPath = audioFile.absolutePath,
            durationMs = 1_000,
            byteLength = audioFile.length(),
            createdAt = 1,
            updatedAt = 1
        )
        var stopped = false

        composeTestRule.setContent {
            ChatBarTheme {
                ChatBubble(
                    message = message,
                    voicePlacements = listOf(VoiceMessagePlacement(voice, null)),
                    voicePlaybackState = VoicePlaybackState(
                        currentVoiceId = voice.id,
                        isPlaying = true,
                        queueVoiceIds = listOf(voice.id)
                    ),
                    onVoiceStop = { stopped = true },
                    showMessageMeta = false
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("停止语音播放").performClick()

        assertTrue(stopped)
    }
}
