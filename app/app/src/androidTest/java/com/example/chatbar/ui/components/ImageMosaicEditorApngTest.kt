package com.example.chatbar.ui.components

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.bumptech.glide.gifencoder.AnimatedGifEncoder
import com.example.chatbar.domain.image.ImageProcessingService
import com.example.chatbar.ui.kit.ChatBarTheme
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test

class ImageMosaicEditorApngTest {
    @get:Rule
    val composeRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun staticImageShowsDisguiseAndCompletedResultUsesDualPreview() {
        val source = writePng("editor-static.png", 0xff304050.toInt())
        showEditor(source)

        composeRule.waitForText("APNG伪装")
        composeRule.onNodeWithText("APNG伪装").performClick()
        composeRule.waitForText("APNG伪装已生成", timeoutMillis = 15_000)
        composeRule.onNodeWithText("聊天默认画面").assertIsDisplayed()
        composeRule.onNodeWithText("点开后内容").assertIsDisplayed()
        composeRule.onNodeWithText("保存到相册").assertIsDisplayed()
        composeRule.onNodeWithText("直接分享").assertIsDisplayed()
    }

    @Test
    fun chatBarDisguiseAutomaticallySwitchesPrimaryActionToRestore() = runBlocking {
        val source = writePng("editor-restore-source.png", 0xff506070.toInt())
        val disguise = ImageProcessingService(composeRule.activity).createApngDisguise(source.absolutePath)

        showEditor(File(disguise.path))

        composeRule.waitForText("逆向还原")
        composeRule.onNodeWithText("逆向还原").assertIsDisplayed()
    }

    @Test
    fun gifDisablesFlatteningEdits() {
        val gif = writeGif()
        showEditor(gif)
        composeRule.waitForText("GIF 会完整保留帧、时序和循环；为避免丢失动画，涂抹、旋转和去元数据已禁用。")
        composeRule.onNodeWithText("完成").assertIsNotEnabled()
        composeRule.onNodeWithText("旋转 90°").performScrollTo().assertIsNotEnabled()
    }

    @Test
    fun otherApngDisablesDisguiseAndFlatteningEdits() = runBlocking {
        val gif = writeGif()
        val service = ImageProcessingService(composeRule.activity)
        val disguise = service.createApngDisguise(gif.absolutePath)
        val restored = service.restoreApngDisguise(disguise.path)
        showEditor(File(restored.path))
        composeRule.waitForText("此 APNG 缺少有效 ChatBar 标记，不能伪装或逆向还原。")
        composeRule.onNodeWithText("APNG伪装").assertIsNotEnabled()
        composeRule.onNodeWithText("完成").assertIsNotEnabled()
    }

    private fun showEditor(file: File) {
        composeRule.setContent {
            ChatBarTheme {
                ImageMosaicEditor(file.absolutePath, onDismiss = {}, onComplete = {})
            }
        }
    }

    private fun writePng(name: String, color: Int): File =
        File(composeRule.activity.cacheDir, name).also { file ->
            val bitmap = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
            try {
                file.outputStream().use { output -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, output) }
            } finally {
                bitmap.recycle()
            }
        }

    private fun writeGif(): File = File(composeRule.activity.cacheDir, "editor-animated.gif").also { file ->
        val encoder = AnimatedGifEncoder().apply {
            setSize(24, 24)
            setRepeat(0)
        }
        file.outputStream().buffered().use { output ->
            check(encoder.start(output))
            listOf(0xffff0000.toInt(), 0xff0000ff.toInt()).forEach { color ->
                val frame = Bitmap.createBitmap(24, 24, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
                try {
                    encoder.setDelay(50)
                    check(encoder.addFrame(frame))
                } finally {
                    frame.recycle()
                }
            }
            check(encoder.finish())
        }
    }

    private fun androidx.compose.ui.test.junit4.AndroidComposeTestRule<*, *>.waitForText(
        text: String,
        timeoutMillis: Long = 5_000
    ) {
        waitUntil(timeoutMillis) { onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty() }
    }
}
