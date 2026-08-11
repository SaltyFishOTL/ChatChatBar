package com.example.chatbar.ui.imageprompt

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.example.chatbar.domain.image.FullImagePatchOperation
import com.example.chatbar.domain.image.ImportedProcessImage
import com.example.chatbar.domain.image.ProcessedImage
import com.example.chatbar.ui.kit.ChatBarTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ImageProcessingPageTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun applyClickShowsImmediateStatusThenAutomaticallyRevealsResult() {
        val file = File(composeTestRule.activity.cacheDir, "image-processing-page-source.png")
        val bitmap = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xff808080.toInt())
        }
        file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        bitmap.recycle()
        val source = ImportedProcessImage(
            path = file.absolutePath,
            displayName = file.name,
            mimeType = "image/png",
            width = 32,
            height = 32,
            frameCount = 1
        )
        var operation: FullImagePatchOperation? = null
        var updateState: ((ImageProcessingUiState) -> Unit)? = null

        composeTestRule.setContent {
            ChatBarTheme {
                var state by remember {
                    mutableStateOf(
                        ImageProcessingUiState(
                            source = source,
                            phase = ImageProcessingPhase.READY
                        )
                    )
                }
                updateState = { state = it }
                ImageProcessingPage(
                    state = state,
                    onSelectImage = {},
                    onProcess = {
                        operation = it
                        state = state.copy(
                            lastOperation = it,
                            phase = ImageProcessingPhase.PROCESSING
                        )
                    },
                    onDismissError = {}
                )
            }
        }

        composeTestRule.onNodeWithText("应用全图贴片").performScrollTo().performClick()
        assertEquals(FullImagePatchOperation.Apply, operation)
        composeTestRule.onNodeWithText("正在处理图片").assertIsDisplayed()

        composeTestRule.runOnUiThread {
            updateState?.invoke(
                ImageProcessingUiState(
                    source = source,
                    result = ProcessedImage(
                        path = file.absolutePath,
                        mimeType = "image/png",
                        width = 32,
                        height = 32,
                        frameCount = 1
                    ),
                    lastOperation = FullImagePatchOperation.Apply,
                    phase = ImageProcessingPhase.FINISHED,
                    progress = 1f
                )
            )
        }

        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("贴片结果").assertIsDisplayed()
        composeTestRule.onNodeWithText("直接分享").assertIsDisplayed()
        composeTestRule.onNodeWithText("保存到相册").assertIsDisplayed()
    }
}
