package com.example.chatbar.ui.chat

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ChatLongScreenshotRenderTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun longScreenshot_rendersPngWithExpectedWidth() = runBlocking {
        val activity = composeTestRule.activity
        val imageFile = createBitmapFile("message-image.png", 160, 80)
        val backgroundFile = createBitmapFile("chat-background.png", 120, 180)
        val request = ChatLongScreenshotRequest(
            title = "测试会话",
            messages = listOf(
                ChatMessage(
                    id = "user",
                    sessionId = "session",
                    role = MessageRole.USER,
                    content = "**你好**",
                    createdAt = 1,
                    updatedAt = 1
                ),
                ChatMessage(
                    id = "assistant",
                    sessionId = "session",
                    role = MessageRole.ASSISTANT,
                    content = "收到",
                    images = listOf(imageFile.absolutePath),
                    reasoningContent = "隐藏思考",
                    createdAt = 2,
                    updatedAt = 2
                )
            ),
            backgroundPath = backgroundFile.absolutePath,
            widthPx = 720,
            fontScale = 1f,
            fileName = "ChatBar_test_long_screenshot.png"
        )

        val output = renderChatLongScreenshot(activity, request)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(output.absolutePath, options)

        assertTrue(output.exists())
        assertEquals(720, options.outWidth)
        assertTrue(options.outHeight > 200)
    }

    private fun createBitmapFile(name: String, width: Int, height: Int): File {
        val file = File(composeTestRule.activity.cacheDir, name)
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        return file
    }
}
