package com.example.chatbar.ui.imageprompt

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.longClick
import androidx.compose.ui.test.swipeLeft
import com.example.chatbar.domain.image.NovelAiGenerationHistoryEntry
import com.example.chatbar.domain.image.NovelAiGenerationHistoryImage
import com.example.chatbar.domain.image.NovelAiGenerationRecipe
import com.example.chatbar.domain.image.NovelAiHistoryApplyMode
import com.example.chatbar.ui.kit.ChatBarTheme
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Rule
import org.junit.Test

class NovelAiHistoryGalleryTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun galleryOpensSelectedSquare() {
        val items = historyItems()
        val selected = mutableStateOf<NovelAiHistoryImageItem?>(null)
        composeTestRule.setContent {
            ChatBarTheme {
                NovelAiHistoryGallery(items, onSelect = { selected.value = it })
            }
        }

        composeTestRule.onNodeWithContentDescription("历史图片 2").performClick()
        composeTestRule.runOnIdle { assertEquals(items[1].key, selected.value?.key) }
    }

    @Test
    fun galleryLongPressSelectsAndRenumbersInSelectionOrder() {
        val items = historyItems()
        val selectedKeys = mutableStateOf<List<String>>(emptyList())
        composeTestRule.setContent {
            ChatBarTheme {
                NovelAiHistoryGallery(
                    items = items,
                    onSelect = { item ->
                        selectedKeys.value = NovelAiHistorySelectionPolicy.toggle(
                            selectedKeys.value,
                            item.key
                        )
                    },
                    onLongSelect = { item ->
                        selectedKeys.value = NovelAiHistorySelectionPolicy.add(
                            selectedKeys.value,
                            item.key
                        )
                    },
                    selectionMode = selectedKeys.value.isNotEmpty(),
                    selectedKeys = selectedKeys.value
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("历史图片 1")
            .performTouchInput { longClick() }
        composeTestRule.onNodeWithContentDescription("选中序号 1").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("历史图片 2").performClick()
        composeTestRule.onNodeWithContentDescription("选中序号 2").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("历史图片 1").performClick()
        composeTestRule.onNodeWithContentDescription("选中序号 1").assertIsDisplayed()
    }

    @Test
    fun detailSwipesThroughFilteredOrderAndExposesIconActions() {
        val items = historyItems()
        var appliedMode: NovelAiHistoryApplyMode? = null
        var openedIndex: Int? = null
        var usedAsKey: String? = null
        composeTestRule.setContent {
            ChatBarTheme {
                HistoryDetailDialog(
                    items = items,
                    initialIndex = 0,
                    busy = false,
                    onDismiss = {},
                    onCurrentChanged = {},
                    onOpenImage = { openedIndex = it },
                    onApply = { _, _, mode -> appliedMode = mode },
                    onUseAs = { usedAsKey = it.key },
                    onDeleteBatch = {}
                )
            }
        }

        composeTestRule.onNodeWithText("第 1 张 / 共 2 张").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("完整复现").performClick()
        composeTestRule.runOnIdle { assertEquals(NovelAiHistoryApplyMode.FULL, appliedMode) }
        composeTestRule.onNodeWithContentDescription("用作图像引导").performClick()
        composeTestRule.runOnIdle { assertEquals(items.first().key, usedAsKey) }

        composeTestRule.onNodeWithContentDescription("历史详情图片 1")
            .performTouchInput { swipeLeft() }
        composeTestRule.waitForIdle()
        composeTestRule.onNodeWithText("第 2 张 / 共 2 张").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("历史详情图片 2").performClick()
        composeTestRule.runOnIdle { assertNotNull(openedIndex) }
    }

    private fun historyItems(): List<NovelAiHistoryImageItem> {
        val paths = List(2) { index ->
            File(composeTestRule.activity.cacheDir, "history-gallery-$index.png").also { file ->
                val bitmap = Bitmap.createBitmap(64, 64, Bitmap.Config.ARGB_8888)
                file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                bitmap.recycle()
            }.absolutePath
        }
        val entry = NovelAiGenerationHistoryEntry(
            id = "batch",
            images = paths.mapIndexed { index, path -> NovelAiGenerationHistoryImage(path, index.toLong()) },
            recipe = NovelAiGenerationRecipe(basePrompt = "blue sky"),
            createdAt = 1L
        )
        return entry.images.mapIndexed { index, image -> NovelAiHistoryImageItem(entry, image, index) }
    }
}
