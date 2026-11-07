package com.example.chatbar.ui.components

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithContentDescription
import androidx.compose.ui.test.performSemanticsAction
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.swipeRight
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chatbar.domain.image.NovelAiCharacterImageSettings
import com.example.chatbar.domain.image.NovelAiEffort
import com.example.chatbar.domain.image.NovelAiGenerationSettings
import com.example.chatbar.domain.image.NovelAiImageModel
import com.example.chatbar.ui.kit.ChatBarTheme
import com.example.chatbar.ui.kit.CbField
import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class NovelAiSamplingSlidersTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun cardExposesAndWritesEveryOfficialStep() = everyStep(card = true)
    @Test fun studioExposesAndWritesEveryOfficialStep() = everyStep(card = false)
    @Test fun cardPreservesUneditedValuesAndMediumHighProfile() = preserveValues(card = true)
    @Test fun studioPreservesUneditedValuesAndMediumHighProfile() = preserveValues(card = false)

    private fun everyStep(card: Boolean) {
        val harness = Harness(card)
        compose.setContent { Sliders(harness) }
        val guidance = compose.onNodeWithContentDescription("CFG Scale")
        val rescale = compose.onNodeWithContentDescription("CFG Rescale")
        assertEquals(89, guidance.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].steps)
        assertEquals(49, rescale.fetchSemanticsNode().config[SemanticsProperties.ProgressBarRangeInfo].steps)
        // Includes all boundaries and floating-point trouble spots, not only integer values.
        for (tick in 10..100) {
            val expected = tick / 10f
            guidance.performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(expected)) }
            compose.runOnIdle { assertEquals(expected, harness.settings().guidance, 0f) }
        }
        for (tick in 0..50) {
            val expected = tick / 50f
            rescale.performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(expected)) }
            compose.runOnIdle { assertEquals(expected, harness.settings().cfgRescale, 0f) }
        }
        guidance.performSemanticsAction(SemanticsActions.SetProgress) { it(6.76f) }
        rescale.performSemanticsAction(SemanticsActions.SetProgress) { it(0.253f) }
        compose.runOnIdle {
            assertEquals(6.8f, harness.settings().guidance, 0f)
            assertEquals(0.26f, harness.settings().cfgRescale, 0f)
        }
    }

    private fun preserveValues(card: Boolean) {
        val harness = Harness(card)
        compose.setContent { Sliders(harness) }
        compose.runOnIdle {
            assertEquals(0, harness.writes)
            assertEquals(6.75321f, harness.settings().guidance, 0f)
            assertEquals(0.257891f, harness.settings().cfgRescale, 0f)
            harness.studio.value = harness.studio.value.copy(model = NovelAiImageModel.V4_5_FULL)
        }
        compose.runOnIdle {
            assertEquals(0, harness.writes)
            assertEquals(6.75321f, harness.settings().guidance, 0f)
            assertEquals(0.257891f, harness.settings().cfgRescale, 0f)
            harness.studio.value = harness.studio.value.copy(model = NovelAiImageModel.V5_FULL)
            harness.setEffort(NovelAiEffort.MEDIUM)
        }
        val rescale = compose.onNodeWithContentDescription("CFG Rescale")
        rescale.performSemanticsAction(SemanticsActions.SetProgress) { assertFalse(it(0.8f)) }
        rescale.performTouchInput { swipeRight() }
        compose.runOnIdle {
            assertEquals(0, harness.writes)
            assertEquals(0f, harness.settings().effectiveCfgRescale, 0f)
            assertEquals(0.257891f, harness.settings().cfgRescale, 0f)
        }
        capture(if (card) "card-medium.png" else "studio-medium.png")
        compose.onNodeWithContentDescription("CFG Scale")
            .performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(7.14f)) }
        compose.runOnIdle {
            assertEquals(7.1f, harness.settings().guidance, 0f)
            assertEquals(0.257891f, harness.settings().cfgRescale, 0f)
            harness.setEffort(NovelAiEffort.HIGH)
        }
        compose.runOnIdle {
            assertEquals(0.257891f, harness.settings().effectiveCfgRescale, 0f)
            assertEquals(1, harness.writes)
        }
        rescale.performSemanticsAction(SemanticsActions.SetProgress) { assertTrue(it(0.253f)) }
        compose.runOnIdle {
            assertEquals(7.1f, harness.settings().guidance, 0f)
            assertEquals(0.26f, harness.settings().cfgRescale, 0f)
            assertEquals(2, harness.writes)
        }
        capture(if (card) "card-high.png" else "studio-high.png")
    }

    @Test fun touchDragUsesTheSameGridAsAccessibility() {
        val harness = Harness(card = false)
        compose.setContent { Sliders(harness) }
        compose.onNodeWithContentDescription("CFG Scale").performTouchInput { swipeRight() }
        compose.onNodeWithContentDescription("CFG Rescale").performTouchInput { swipeRight() }
        compose.runOnIdle {
            assertTrue(harness.guidanceEdits.isNotEmpty())
            assertTrue(harness.rescaleEdits.isNotEmpty())
            harness.guidanceEdits.forEach { assertEquals((it * 10f).roundToInt() / 10f, it, 0f) }
            harness.rescaleEdits.forEach { assertEquals((it * 50f).roundToInt() / 50f, it, 0f) }
        }
    }

    @Composable
    private fun Sliders(harness: Harness) {
        val settings = harness.settings()
        ChatBarTheme {
            Column {
                CbField("CFG · ${"%.1f".format(settings.guidance)}") {
                    NovelAiGuidanceSlider(settings.guidance, harness::setGuidance)
                }
                CbField("CFG Rescale · ${"%.2f".format(settings.effectiveCfgRescale)}") {
                    NovelAiCfgRescaleSlider(settings.effectiveCfgRescale, harness::setRescale,
                        enabled = !settings.isMediumEffort)
                }
            }
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val folder = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "nai-slider-evidence")
            check(folder.isDirectory || folder.mkdirs())
            java.io.File(folder, name).outputStream().use {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }

    // Exercise the two production consumer write-back shapes without repositories or API calls.
    private class Harness(private val card: Boolean) {
        val studio = mutableStateOf(NovelAiGenerationSettings(model = NovelAiImageModel.V5_FULL,
            guidance = 6.75321f, cfgRescale = 0.257891f))
        private val profile = mutableStateOf(NovelAiCharacterImageSettings(
            guidance = 6.75321f, cfgRescale = 0.257891f))
        var writes = 0
        val guidanceEdits = mutableListOf<Float>()
        val rescaleEdits = mutableListOf<Float>()
        fun settings() = if (card) profile.value.applyTo(studio.value) else studio.value
        fun setGuidance(value: Float) {
            writes++
            guidanceEdits += value
            if (card) profile.value = profile.value.copy(guidance = value)
            else studio.value = studio.value.copy(guidance = value)
        }
        fun setRescale(value: Float) {
            writes++
            rescaleEdits += value
            if (card) profile.value = profile.value.copy(cfgRescale = value)
            else studio.value = studio.value.copy(cfgRescale = value)
        }
        fun setEffort(effort: NovelAiEffort) {
            if (card) profile.value = profile.value.copy(effort = effort)
            else studio.value = studio.value.copy(effort = effort)
        }
    }
}
