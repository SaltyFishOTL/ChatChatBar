package com.example.chatbar.ui.character

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertHasClickAction
import androidx.compose.ui.test.assertHeightIsEqualTo
import androidx.compose.ui.test.assertDoesNotExist
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
import com.example.chatbar.domain.image.NovelAiStyleModelSupport
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

        composeTestRule.onNodeWithContentDescription("填充画风：画风 1；支持 V4.5 / V5")
            .assertHasClickAction()
            .assertIsSelected()
        composeTestRule.onNodeWithContentDescription("填充画风：画风 2；支持 V4.5 / V5")
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

        composeTestRule.onNodeWithContentDescription("填充画风：画风 6；支持 V4.5 / V5")
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
    fun modelFilterShowsOnlyCompatibleStyles() {
        val v45 = style(1).copy(modelSupport = NovelAiStyleModelSupport.V4_5)
        val v5 = style(2).copy(modelSupport = NovelAiStyleModelSupport.V5)
        val both = style(3).copy(modelSupport = NovelAiStyleModelSupport.BOTH)
        composeTestRule.setContent {
            ChatBarTheme {
                NovelAiStylePresetGallery(
                    catalog = NovelAiStyleCatalogLoadResult(styles = listOf(v45, v5, both)),
                    currentPrompt = "",
                    onApply = {}
                )
            }
        }

        composeTestRule.onNodeWithTag("novel-ai-style-filter").assertDoesNotExist()
        composeTestRule.onNodeWithText("查看全部（3）").performClick()
        composeTestRule.onNodeWithTag("novel-ai-style-filter").performClick()
        composeTestRule.onNodeWithTag("novel-ai-style-filter-option:V5").performClick()
        composeTestRule.onNodeWithContentDescription("填充画风：画风 1；支持 V4.5")
            .assertDoesNotExist()
        composeTestRule.onNodeWithContentDescription("填充画风：画风 2；支持 V5")
            .assertIsDisplayed()
        composeTestRule.onNodeWithContentDescription("填充画风：画风 3；支持 V4.5 / V5")
            .performScrollTo()
            .assertIsDisplayed()
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
