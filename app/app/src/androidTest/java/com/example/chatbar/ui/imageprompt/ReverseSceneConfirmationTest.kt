package com.example.chatbar.ui.imageprompt

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.example.chatbar.domain.image.ImportedProcessImage
import com.example.chatbar.ui.kit.ChatBarTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class ReverseSceneConfirmationTest {
    @get:Rule val compose = createAndroidComposeRule<ComponentActivity>()

    @Test fun sceneRequiresExplicitNonblankConfirmation() {
        val scene = mutableStateOf("识别草稿")
        var confirmed: String? = null
        compose.setContent {
            ChatBarTheme {
                StudioImageToolsDialog(
                    source = ImportedProcessImage("/synthetic.png", "test", "image/png", 32, 32, 1),
                    metadata = null, loading = false, busy = false, designStatus = "等待确认",
                    resultStream = "", reasoningStream = "", reversePromptReply = "",
                    sceneDescription = scene.value, naturalLanguage = true,
                    onSceneDescriptionChange = { scene.value = it }, onConfirmScene = { confirmed = scene.value },
                    hasReversePromptCandidate = false, reversePromptStopping = false, isDesigning = false,
                    onDismiss = {}, onPickImage = {}, onRemoveImage = {}, onParseMetadata = {},
                    onMosaic = {}, onPostProcess = {}, onReversePrompt = {}, onCancelReversePrompt = {},
                    onRetryReversePrompt = {}, onApplyReversePrompt = {}
                )
            }
        }
        compose.runOnIdle { assertEquals(null, confirmed) }
        compose.onNode(hasSetTextAction()).performScrollTo().performTextReplacement("")
        compose.onNodeWithText("确认并继续").assertIsNotEnabled()
        compose.onNode(hasSetTextAction()).performTextReplacement("修正后的场景")
        compose.activityRule.scenario.onActivity { activity ->
            val manager = activity.getSystemService(android.content.Context.INPUT_METHOD_SERVICE) as android.view.inputmethod.InputMethodManager
            manager.hideSoftInputFromWindow(activity.window.decorView.windowToken, 0)
        }
        compose.onNodeWithText("确认并继续").assertIsEnabled().performClick()
        compose.runOnIdle { assertEquals("修正后的场景", confirmed) }
    }
}
