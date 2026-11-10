package com.example.chatbar.ui.worldbook

import android.content.Context
import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.example.chatbar.data.local.entity.WorldBookEntry
import com.example.chatbar.ui.kit.ChatBarTheme
import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class WorldBookEntryListItemTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val original = WorldBookEntry("synthetic-entry", "合成条目", listOf("alpha"), "合成正文", enabled = true)
    private val switchLabel = "启用世界书条目：合成条目"

    @Test fun quickToggleChangesOnlyEnabledAndDoesNotOpenEditor() {
        var observed = original
        var edits = 0
        rule.setContent {
            var entry by remember { mutableStateOf(original) }
            ChatBarTheme {
                Box(Modifier.width(320.dp)) {
                    WorldBookEntryListItem(entry, { edits++ }, { checked ->
                        entry = entry.copy(enabled = checked)
                        observed = entry
                    }, {})
                }
            }
        }
        rule.onNodeWithContentDescription(switchLabel).assertIsOn()
        capture("enabled.png")
        rule.onNodeWithContentDescription(switchLabel).performClick().assertIsOff()
        rule.onNodeWithText("已停用").assertIsDisplayed()
        rule.runOnIdle {
            assertEquals(original.copy(enabled = false), observed)
            assertEquals(0, edits)
        }
        capture("disabled.png")
        rule.onNodeWithContentDescription(switchLabel).performClick().assertIsOn()
        rule.runOnIdle { assertEquals(original, observed) }
    }

    @Test fun rowAndEditDeleteActionsRemainIndependent() {
        var edits = 0
        var deletes = 0
        var toggles = 0
        rule.setContent {
            ChatBarTheme { Box(Modifier.width(320.dp)) {
                WorldBookEntryListItem(original, { edits++ }, { toggles++ }, { deletes++ })
            } }
        }
        rule.onNodeWithText(original.name).performClick()
        rule.onNodeWithContentDescription("编辑世界书条目：合成条目").performClick()
        rule.onNodeWithContentDescription("删除世界书条目：合成条目").performClick()
        rule.runOnIdle {
            assertEquals(2, edits)
            assertEquals(1, deletes)
            assertEquals(0, toggles)
        }
    }

    @Test fun longTitleKeepsSwitchAccessibleAt320Dp() {
        val entry = original.copy(name = "这是用于窄屏布局验证的合成长名称世界书条目")
        var density = 1f
        rule.setContent {
            density = LocalDensity.current.density
            ChatBarTheme { Box(Modifier.width(320.dp)) {
                WorldBookEntryListItem(entry, {}, {}, {})
            } }
        }
        val node = rule.onNodeWithContentDescription("启用世界书条目：${entry.name}").assertIsDisplayed()
        val bounds = node.fetchSemanticsNode().boundsInRoot
        rule.runOnIdle {
            assertEquals(48f * density, bounds.width, 1f)
            assertEquals(48f * density, bounds.height, 1f)
        }
        rule.onNodeWithContentDescription("编辑世界书条目：${entry.name}").assertIsDisplayed()
        rule.onNodeWithContentDescription("删除世界书条目：${entry.name}").assertIsDisplayed()
        capture("narrow-long-title.png")
    }

    @Test fun savingDisablesRowActionsAndToggle() {
        var actions = 0
        rule.setContent {
            ChatBarTheme { Box(Modifier.width(320.dp)) {
                WorldBookEntryListItem(original, { actions++ }, { actions++ }, { actions++ }, enabled = false)
            } }
        }
        rule.onNodeWithContentDescription(switchLabel).assertIsNotEnabled()
            .performTouchInput { click() }
        rule.onNodeWithContentDescription("编辑世界书条目：合成条目").assertIsNotEnabled()
        rule.onNodeWithContentDescription("删除世界书条目：合成条目").assertIsNotEnabled()
        rule.runOnIdle { assertEquals(0, actions) }
    }

    private fun capture(name: String) {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val output = File(context.getExternalFilesDir(null), "worldbook-toggle-evidence").apply { mkdirs() }
        File(output, name).outputStream().use {
            rule.onRoot().captureToImage().asAndroidBitmap().compress(Bitmap.CompressFormat.PNG, 100, it)
        }
    }
}
