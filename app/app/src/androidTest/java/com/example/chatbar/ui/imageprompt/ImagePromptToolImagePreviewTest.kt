package com.example.chatbar.ui.imageprompt

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.example.chatbar.ui.components.ImagePreviewDialog
import com.example.chatbar.ui.kit.ChatBarTheme
import java.io.File
import org.junit.Rule
import org.junit.Test

class ImagePromptToolImagePreviewTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun savedImage_opensSharedPreviewOnClick() {
        val file = File(composeTestRule.activity.cacheDir, "prompt-tool-result.png")
        val bitmap = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()

        composeTestRule.setContent {
            ChatBarTheme {
                var openedPath by remember { mutableStateOf<String?>(null) }
                Box {
                    ImagePreviewPanel(
                        state = ImagePromptToolUiState(
                            phase = ImagePromptToolPhase.FINISHED,
                            imagePath = file.absolutePath
                        ),
                        onOpenImage = { openedPath = it }
                    )
                    openedPath?.let { path ->
                        ImagePreviewDialog(
                            path = path,
                            onDismiss = { openedPath = null }
                        )
                    }
                }
            }
        }

        composeTestRule.onNodeWithContentDescription("NovelAI 生图结果").performClick()
        composeTestRule.onNodeWithContentDescription("关闭大图").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("查看大图")
            .performTouchInput { longClick() }

        composeTestRule.onNodeWithText("打码").assertIsDisplayed()
        composeTestRule.onNodeWithText("保存图片").assertIsDisplayed()
        composeTestRule.onNodeWithText("分享图片").assertIsDisplayed()
    }
}
