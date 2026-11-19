package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ActionOptionLabels
import com.example.chatbar.data.local.entity.ActionOptionsFormat
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.domain.card.FormatCardPackage
import com.example.chatbar.domain.card.validateForImport
import com.example.chatbar.domain.prompt.PromptTemplates
import com.example.chatbar.domain.voice.VoiceAnchorPolicy
import com.example.chatbar.domain.voice.VoiceGenerationPolicy
import kotlinx.serialization.json.Json
import org.junit.Assert.*
import org.junit.Test

class ActionOptionsFormatTest {
    private val custom = ActionOptionsFormat("<cyoa>", "</cyoa>", "下一步", ActionOptionLabels.LETTERS)
    private val menu = "<cyoa>\n下一步\nA) 查地图\nB) 找向导\n</cyoa>"

    @Test
    fun standardShortFenceWorksAfterAnOrdinaryMarkdownDivider() {
        val story = "已经到达广场。\n---\n背包：\n1. 地图\n2. 水"
        val source = "$story\n---\n【行动选项】\n1. 查地图\n2. 找向导\n---"
        val block = RoleplayActionOptionsPolicy.blocks(source).single()
        assertEquals(listOf("查地图", "找向导"), block.options.map { it.text })
        assertEquals(story, RoleplayActionOptionsPolicy.textOutsideBlocks(source, listOf(block)).trimEnd())
        assertTrue(RoleplayActionOptionsPolicy.blocks(story).isEmpty())
        assertTrue(RoleplayActionOptionsPolicy.blocks("```text\n$source\n```").isEmpty())
    }

    @Test
    fun markdownCodeExamplesRemainLiteralText() {
        val plain = "---\n【行动选项】\n1. 查地图\n2. 找向导\n---"
        val indented = plain.lineSequence().joinToString("\n") { "    $it" }
        for (literal in listOf("~~~text\n$plain\n~~~", "~~~text\n$plain", indented)) {
            assertTrue(RoleplayActionOptionsPolicy.blocks(literal).isEmpty())
            assertEquals(literal, RoleplayActionOptionsPolicy.withoutExplicitOptions(literal))
        }
        val afterExample = "~~~text\n$plain\n~~~\n$plain"
        assertEquals(1, RoleplayActionOptionsPolicy.blocks(afterExample).size)
    }

    @Test
    fun customRuleRoundTripsAndRemainsBoundToItsReplyVersion() {
        val formatCard = FormatCardPackage(name = "冒险样本", content = "按场景接续", actionOptionsFormat = custom)
        val codec = Json { ignoreUnknownKeys = true }
        val serializer = FormatCardPackage.serializer()
        val restored = codec.decodeFromString(serializer, codec.encodeToString(serializer, formatCard))
        restored.validateForImport()
        assertEquals(custom, restored.actionOptionsFormat)
        assertTrue(RoleplayActionOptionsPolicy.blocks(menu).isEmpty())

        val first = ChatMessage.create("sample", MessageRole.ASSISTANT, menu).copy(actionOptionsFormat = custom)
        val second = MessageAlternativeVersionPolicy.append(first, "普通叙述", "second", actionOptionsFormat = null)
        assertNull(second.displayActionOptionsFormat)
        val reselected = MessageAlternativeVersionPolicy.select(second, 0)
        assertEquals(custom, reselected.displayActionOptionsFormat)
        assertEquals(listOf("A", "B"), RoleplayActionOptionsPolicy.blocks(
            reselected.displayContent, reselected.displayActionOptionsFormat
        ).single().options.map { it.label })
    }

    @Test
    fun imageAndVoiceUseTheCustomRuleButKeepActualStoryAndStatus() {
        val story = "已经到达广场。\n$menu\n门口亮着灯。\n```text\n衣着：蓝外套\n```"
        val message = ChatMessage.create("sample", MessageRole.ASSISTANT, story).copy(actionOptionsFormat = custom)
        val image = PromptTemplates.novelAiImagePromptAssistantScene(message)
        val context = VoiceAnchorPolicy.promptContextText(story, custom)
        for (projection in listOf(image, context)) {
            assertTrue(projection.contains("蓝外套"))
            assertTrue(projection.contains("已经到达广场"))
            assertFalse(projection.contains("找向导"))
        }
        for (segmented in listOf(false, true)) {
            val spoken = VoiceGenerationPolicy.generationSegments(story, true, segmented, custom)
                .joinToString { it.spokenText }
            assertTrue(spoken.contains("门口亮着灯"))
            assertFalse(spoken.contains("查地图"))
        }
    }

    @Test
    fun repairOnlyClosesUnambiguousBlocksAndComposerKeepsExistingText() {
        val draft = menu.removeSuffix("\n</cyoa>")
        assertEquals(menu, RoleplayActionOptionsPolicy.completeTrailingBlock(draft, custom))
        val mixed = "$draft\n这是已经发生的正文"
        assertEquals(mixed, RoleplayActionOptionsPolicy.completeTrailingBlock(mixed, custom))
        val first = insertActionOption("我准备出门", 3, "找向导")
        val repeated = insertActionOption(first.text, first.cursor, "找向导")
        assertEquals("我准备\n找向导\n找向导\n出门", repeated.text)
        assertEquals("等一等\n再出门", insertActionOption("等一等", null, "再出门").text)
    }
}
