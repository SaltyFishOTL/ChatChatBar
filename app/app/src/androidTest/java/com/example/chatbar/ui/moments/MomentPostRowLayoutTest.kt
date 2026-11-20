package com.example.chatbar.ui.moments

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.example.chatbar.data.local.entity.MomentPost
import com.example.chatbar.domain.moment.MomentImageVersions
import com.example.chatbar.ui.kit.ChatBarTheme
import java.io.File
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** Exercises the real post row, including private/time/version labels and all actions. */
class MomentPostRowLayoutTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val files = mutableListOf<File>()
    @After fun cleanup() { files.forEach { it.delete() } }
    @Test fun privatePostActionsRemainVisibleAt320dp() = verify(320, 1f)
    @Test fun privatePostActionsRemainVisibleAt360dpLargeFont() = verify(360, 1.3f)

    private fun verify(width: Int, fontScale: Float) {
        fun image(): File = File.createTempFile("moment-layout-", ".png", rule.activity.cacheDir).also { file ->
            files.add(file)
            val bitmap = Bitmap.createBitmap(2, 2, Bitmap.Config.ARGB_8888)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            bitmap.recycle()
        }
        val first = image().path
        val second = image().path
        val post = mutableStateOf(MomentImageVersions.append(MomentPost("layout-post", "card", "session",
            senderName = "synthetic", text = "synthetic post", imagePath = first, imagePrompt = "first prompt",
            scheduledAt = 1, generatedAt = 2, isPrivate = true), second, "second prompt", null))
        var deleted = 0
        var regenerated = 0
        var liked = 0
        var opened = 0
        rule.setContent {
            val density = LocalDensity.current.density
            CompositionLocalProvider(LocalDensity provides Density(density, fontScale)) {
                ChatBarTheme { Box(Modifier.width(width.dp).testTag("post-host")) {
                    MomentPostRow(post.value, false, null, MomentOnDemandImageUiState(),
                        onToggleLike = { liked++ }, onLongPressText = {}, onEdit = {},
                        onDelete = { deleted++ }, onRetry = {}, onOpenImage = { _, _ -> opened++ },
                        onRegenerateImage = { _, _ -> regenerated++ }, onGenerateImage = {},
                        onSelectImageVersion = { _, id -> post.value = MomentImageVersions.select(post.value, id) })
                } }
            }
        }
        val host = rule.onNodeWithTag("post-host").fetchSemanticsNode().boundsInRoot
        val actions = listOf(rule.onNodeWithText("‹"), rule.onNodeWithText("›"),
            rule.onNodeWithContentDescription("重新生成图片"), rule.onNodeWithContentDescription("删除朋友圈"),
            rule.onNodeWithContentDescription("点赞"))
        actions.forEach { node ->
            node.assertIsDisplayed()
            val bounds = node.fetchSemanticsNode().boundsInRoot
            assertTrue("action clipped: $bounds in $host", bounds.left >= host.left - 1 && bounds.right <= host.right + 1)
            assertTrue("action collapsed: $bounds", bounds.width >= 24 * rule.activity.resources.displayMetrics.density)
        }
        rule.onNodeWithText("2/2").assertIsDisplayed()
        rule.onNodeWithText("‹").performClick()
        rule.onNodeWithText("1/2").assertIsDisplayed()
        rule.runOnIdle { assertEquals(first, post.value.imagePath); assertEquals("first prompt", post.value.imagePrompt) }
        rule.onNodeWithText("›").performClick()
        rule.runOnIdle { assertEquals(second, post.value.imagePath); assertEquals(2, post.value.imageVersions.size) }
        rule.onNodeWithContentDescription("重新生成图片").performClick()
        rule.onNodeWithContentDescription("删除朋友圈").performClick()
        rule.onNodeWithContentDescription("点赞").performClick()
        rule.onNodeWithContentDescription("synthetic post").performClick()
        rule.runOnIdle { assertEquals(1, regenerated); assertEquals(1, deleted); assertEquals(1, liked); assertEquals(1, opened) }
    }
}
