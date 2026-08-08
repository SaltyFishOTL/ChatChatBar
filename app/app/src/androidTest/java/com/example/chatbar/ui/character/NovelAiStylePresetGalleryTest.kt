package com.example.chatbar.ui.character

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertWidthIsEqualTo
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.unit.dp
import com.example.chatbar.domain.image.NovelAiStyleCatalogLoadResult
import com.example.chatbar.domain.image.NovelAiStylePreset
import com.example.chatbar.ui.kit.ChatBarTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class NovelAiStylePresetGalleryTest {
    @get:Rule
    val composeTestRule = createAndroidComposeRule<ComponentActivity>()

    @Test
    fun cardsExposeFillActionSelectionAndImmediateClick() {
        val first = style(1)
        val second = style(2)
        var applied: NovelAiStylePreset? = null
        composeTestRule.setContent {
            ChatBarTheme {
                NovelAiStylePresetGallery(
                    catalog = NovelAiStyleCatalogLoadResult(styles = listOf(first, second)),
                    currentPrompt = first.prompt,
                    onApply = { applied = it }
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("填充画风：画风 1")
            .assertHasClickAction()
            .assertIsSelected()
        composeTestRule.onNodeWithContentDescription("填充画风：画风 2")
            .performClick()
        composeTestRule.runOnIdle { assertEquals(second, applied) }
    }

    @Test
    fun galleryScrollsToLaterCardAndShowsMissingPreview() {
        val styles = (1..6).map { index ->
            style(index).copy(previewAvailable = index != 1)
        }
        composeTestRule.setContent {
            ChatBarTheme {
                NovelAiStylePresetGallery(
                    catalog = NovelAiStyleCatalogLoadResult(styles = styles),
                    currentPrompt = "",
                    onApply = {}
                )
            }
        }

        composeTestRule.onNodeWithContentDescription("填充画风：画风 6")
            .performScrollTo()
            .assertIsDisplayed()
        composeTestRule.onNodeWithText("例图缺失", useUnmergedTree = true)
            .assertIsDisplayed()
        composeTestRule.onNodeWithTag(
            "novel-ai-style-preview:style-1",
            useUnmergedTree = true
        )
            .assertWidthIsEqualTo(132.dp)
            .assertHeightIsEqualTo(132.dp)
    }

    @Test
    fun viewAllOpensFullscreenGridForLargeCatalog() {
        val styles = (1..10).map(::style)
        composeTestRule.setContent {
            ChatBarTheme {
                NovelAiStylePresetGallery(
                    catalog = NovelAiStyleCatalogLoadResult(styles = styles),
                    currentPrompt = "",
                    onApply = {}
                )
            }
        }

        composeTestRule.onNodeWithText("查看全部（10）").performClick()
        composeTestRule.onNodeWithText("全部内置画风").assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("关闭全部画风").performClick()
    }

    @Test
    fun galleryShowsEmptyAndFatalStates() {
        composeTestRule.setContent {
            ChatBarTheme {
                NovelAiStylePresetGallery(
                    catalog = NovelAiStyleCatalogLoadResult(),
                    currentPrompt = "",
                    onApply = {}
                )
            }
        }
        composeTestRule.onNodeWithText("未配置内置画风").assertIsDisplayed()

        composeTestRule.setContent {
            ChatBarTheme {
                NovelAiStylePresetGallery(
                    catalog = NovelAiStyleCatalogLoadResult(fatalError = "画风配置加载失败"),
                    currentPrompt = "",
                    onApply = {}
                )
            }
        }
        composeTestRule.onNodeWithText("画风配置加载失败").assertIsDisplayed()
    }

    private fun style(index: Int) = NovelAiStylePreset(
        styleKey = "style-$index",
        displayName = "画风 $index",
        description = "简介 $index",
        prompt = "prompt $index",
        previewImage = "style-$index.webp",
        previewAvailable = false
    )
}
