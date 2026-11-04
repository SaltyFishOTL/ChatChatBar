package com.example.chatbar.ui.imageprompt

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.example.chatbar.domain.image.*
import com.example.chatbar.ui.components.NovelAiSamplerSelect
import com.example.chatbar.ui.kit.CbButton
import com.example.chatbar.ui.kit.ChatBarTheme
import kotlinx.serialization.json.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class NovelAiReviewRegressionTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun studioSamplerRealClickCannotOverwriteHighWhileMedium() = samplerClickFlow(card = false)
    @Test fun characterSamplerRealClickCannotOverwriteHighWhileMedium() = samplerClickFlow(card = true)

    private fun samplerClickFlow(card: Boolean) {
        val high = NovelAiGenerationSettings(model = NovelAiImageModel.V5_FULL,
            sampler = NovelAiSampler.DPM_PLUS_PLUS_2M, steps = 41, cfgRescale = 0.6f)
        val studio = mutableStateOf(high)
        val profile = mutableStateOf(NovelAiCharacterImageSettings(sampler = high.sampler, steps = 41, cfgRescale = 0.6f))
        fun effective() = if (card) profile.value.applyTo(high) else studio.value
        compose.setContent {
            ChatBarTheme {
                Column {
                    NovelAiSamplerSelect(effective()) { sampler ->
                        // The two actual consumer write-back shapes.
                        if (card) profile.value = profile.value.copy(sampler = sampler)
                        else studio.value = studio.value.copy(sampler = sampler)
                    }
                    CbButton("Medium", {
                        if (card) profile.value = profile.value.copy(effort = NovelAiEffort.MEDIUM)
                        else studio.value = studio.value.copy(effort = NovelAiEffort.MEDIUM)
                    })
                    CbButton("High", {
                        if (card) profile.value = profile.value.copy(effort = NovelAiEffort.HIGH)
                        else studio.value = studio.value.copy(effort = NovelAiEffort.HIGH)
                    })
                }
            }
        }
        // Open the real CbSelect while High, then change effort before choosing.
        compose.onNodeWithText(high.sampler.displayName).performClick()
        compose.runOnIdle {
            if (card) profile.value = profile.value.copy(effort = NovelAiEffort.MEDIUM)
            else studio.value = studio.value.copy(effort = NovelAiEffort.MEDIUM)
        }
        compose.onAllNodesWithText(NovelAiSampler.EULER_ANCESTRAL.displayName).onLast().performClick()
        compose.runOnIdle { assertEquals(high.sampler, effective().sampler) }
        // A fresh Medium interaction cannot open the select at all.
        compose.onNodeWithText(NovelAiSampler.EULER_ANCESTRAL.displayName)
            .assertIsNotEnabled().performTouchInput { click() }
        compose.onAllNodesWithText("取消").assertCountEquals(0)
        capture(if (card) "medium-character.png" else "medium-studio.png")
        compose.onNodeWithText("High").performClick()
        compose.onNodeWithText(high.sampler.displayName).assertIsEnabled()
        compose.runOnIdle {
            val restored = Json.decodeFromString(NovelAiGenerationSettings.serializer(),
                Json.encodeToString(NovelAiGenerationSettings.serializer(), effective()))
            assertEquals(high.sampler, restored.sampler)
            assertEquals(41, restored.steps)
            assertEquals(0.6f, restored.cfgRescale)
            val params = Json.parseToJsonElement(NovelAiImageService().buildRequestBody(
                NovelAiPromptPlan("synthetic scene", emptyList()), restored.imageSize(), restored))
                .jsonObject.getValue("parameters").jsonObject
            assertEquals("k_dpmpp_2m", params.getValue("sampler").jsonPrimitive.content)
        }
    }

    @Test fun ambiguousPngImportDefaultsToKeepingHandwrittenText() = importChoiceFlow(manage = false)
    @Test fun explicitPngImportChoiceRestoresReversibleSwitch() = importChoiceFlow(manage = true)

    private fun importChoiceFlow(manage: Boolean) {
        val metadata = NovelAiStudioPngMetadata("/synthetic.png", "scene, transparent background",
            settings = NovelAiImportedGenerationSettings(model = NovelAiImageModel.V5_FULL, transparentBackground = true),
            width = 1024, height = 1024)
        var imported: NovelAiStudioDraft? = null
        compose.setContent {
            ChatBarTheme {
                ImportedMetadataSelectionDialog(metadata, false, {}, { selection ->
                    imported = NovelAiStudioDraft().applyImportedMetadata(metadata, selection)
                })
            }
        }
        compose.onNodeWithText(NovelAiTransparentSuffixImport.KEEP_AS_PROMPT.displayName).performScrollTo().assertIsDisplayed()
        if (manage) {
            compose.onNodeWithText(NovelAiTransparentSuffixImport.KEEP_AS_PROMPT.displayName).performClick()
            compose.onNodeWithText(NovelAiTransparentSuffixImport.MANAGE_WITH_SWITCH.displayName).performClick()
        }
        capture(if (manage) "import-manage.png" else "import-retain.png")
        compose.onNodeWithText("确认解析").performClick()
        compose.runOnIdle {
            val draft = requireNotNull(imported)
            val off = draft.withActiveSettings(draft.activeSettings.copy(transparentBackground = false))
            val body = Json.parseToJsonElement(NovelAiImageService().buildRequestBody(off.toPromptPlan(),
                off.activeSettings.imageSize(), off.activeSettings)).jsonObject
            assertEquals(if (manage) "scene" else "scene, transparent background", body.getValue("input").jsonPrimitive.content)
        }
    }

    private fun capture(name: String) {
        compose.waitForIdle()
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val bitmap = checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val folder = java.io.File(instrumentation.targetContext.getExternalFilesDir(null), "nai-review-evidence")
            check(folder.isDirectory || folder.mkdirs())
            java.io.File(folder, name).outputStream().use {
                check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it))
            }
        } finally { bitmap.recycle() }
    }
}
