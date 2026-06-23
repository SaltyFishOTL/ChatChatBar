package com.example.chatbar.ui.components

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.ui.kit.ChatBarTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ChatBubbleImageTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun image_preservesRatio_andSupportsClickAndLongPress() {
        val file = File(composeTestRule.activity.cacheDir, "chat-bubble-ratio.png")
        val bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        var clicked = false
        var longPressed = false
        val message = ChatMessage(
            id = "image",
            sessionId = "session",
            role = MessageRole.ASSISTANT,
            content = "",
            images = listOf(file.absolutePath),
            createdAt = 1,
            updatedAt = 1
        )

        assertEquals(2f, imageAspectRatio(file.absolutePath), 0.001f)

        composeTestRule.setContent {
            ChatBarTheme {
                ChatBubble(
                    message = message,
                    onImageClick = { clicked = true },
                    onImageLongPress = { longPressed = true }
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("消息图片").performClick()
        assertTrue(clicked)
        composeTestRule.onNodeWithContentDescription("消息图片")
            .performTouchInput { longClick() }
        assertTrue(longPressed)
    }

    @Test
    fun roleplayMarkdown_removesLinkDestinations() {
        val result = sanitizeRoleplayMarkdown(
            "[\"我叫卢帕\"]() 与 [普通链接](https://example.com)"
        )

        assertEquals("[\"我叫卢帕\"] 与 [普通链接]", result)
    }

    @Test
    fun roleplayContent_extractsFencedStatusBlocks() {
        val marker = "\u0060\u0060\u0060"
        val content = "正文\n${marker}\n姓名:卢帕\n状态:疲惫\n${marker}\n后续"

        val result = parseRoleplayContent(content)

        assertEquals(3, result.size)
        assertTrue(result[0] is RoleplayContentSegment.Markdown)
        assertEquals(
            "姓名:卢帕\n状态:疲惫",
            (result[1] as RoleplayContentSegment.Status).text
        )
        assertTrue(result[2] is RoleplayContentSegment.Markdown)
    }

    @Test
    fun messageBubble_longPressOpensMessageActions() {
        var longPressed = false
        val message = ChatMessage(
            id = "text",
            sessionId = "session",
            role = MessageRole.ASSISTANT,
            content = "长按测试",
            createdAt = 1,
            updatedAt = 1
        )
        composeTestRule.setContent {
            ChatBarTheme {
                ChatBubble(message = message, onLongPress = { longPressed = true })
            }
        }

        composeTestRule.onNodeWithContentDescription("助手消息")
            .performTouchInput { longClick() }

        assertTrue(longPressed)
    }
}
