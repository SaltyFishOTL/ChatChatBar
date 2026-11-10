package com.example.chatbar.ui.components

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.toPixelMap
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.chatbar.ui.kit.ChatBarTheme
import java.io.File
import java.util.UUID
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class ChatBackgroundGalleryTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val pictures = mutableListOf<File>()

    @Before fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        for (color in listOf(Color.RED, Color.BLUE)) {
            val file = File(context.cacheDir, "synthetic-gallery-ui-${UUID.randomUUID()}.png")
            val bitmap = Bitmap.createBitmap(40, 30, Bitmap.Config.ARGB_8888)
            try { bitmap.eraseColor(color); file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { bitmap.recycle() }
            pictures += file
        }
    }

    @After fun cleanup() { pictures.forEach { it.delete() } }

    @Test fun selectAndRemoveAreIndependentAtPhoneWidth() {
        var observed = pictures[0].absolutePath
        var removed: String? = null
        rule.setContent {
            var selected by remember { mutableStateOf(observed) }
            ChatBarTheme { Box(Modifier.width(320.dp)) {
                ChatBackgroundGallery(pictures.map { it.absolutePath }, selected,
                    { selected = it; observed = it }, { removed = it })
            } }
        }
        rule.waitUntil(timeoutMillis = 10_000) {
            val red = rule.onNodeWithContentDescription("聊天背景图 1").captureToImage().toPixelMap()
            val blue = rule.onNodeWithContentDescription("聊天背景图 2").captureToImage().toPixelMap()
            val first = red[red.width / 2, red.height / 2]
            val second = blue[blue.width / 2, blue.height / 2]
            first.red > 0.5f && first.green < 0.4f && first.blue < 0.4f &&
                second.blue > 0.5f && second.red < 0.4f && second.green < 0.4f
        }
        rule.onNodeWithText("选择背景 2").performClick()
        rule.onNodeWithText("已选背景 2").assertIsDisplayed()
        rule.runOnIdle { assertEquals(pictures[1].absolutePath, observed); assertNull(removed) }
        rule.onNodeWithText("移除背景 1").performClick()
        rule.runOnIdle { assertEquals(pictures[0].absolutePath, removed); assertEquals(pictures[1].absolutePath, observed) }
        val context = ApplicationProvider.getApplicationContext<Context>()
        val dir = File(context.getExternalFilesDir(null), "alignment-evidence").apply { mkdirs() }
        File(dir, "background-gallery-320dp.png").outputStream().use {
            rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }

    @Test fun savingDisablesGalleryActions() {
        var actions = 0
        rule.setContent { ChatBarTheme { Box(Modifier.width(320.dp)) {
            ChatBackgroundGallery(pictures.map { it.absolutePath }, null, { actions++ }, { actions++ }, enabled = false)
        } } }
        rule.onNodeWithText("选择背景 1").assertIsNotEnabled().performTouchInput { click() }
        rule.onNodeWithText("移除背景 1").assertIsNotEnabled()
        rule.runOnIdle { assertEquals(0, actions) }
    }
}
