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
    fun legacyActionFencesRecognizeTheirExplicitOpeningOrBodyHeading() {
        for ((opening, title) in listOf(
            "```行动选项" to "",
            "```action choices" to "",
            "```" to "**行动选项：**\n"
        )) {
            val action = "$opening\n${title}1) 查地图\n2) 找向导\n```"
            val source = "先回到广场。\n$action\n门口亮着灯。"
            val blocks = RoleplayActionOptionsPolicy.blocks(source)
            assertEquals(opening, 1, blocks.size)
            assertEquals(action, source.substring(blocks.single().range))
            assertEquals(listOf("查地图", "找向导"), blocks.single().options.map { it.text })
            assertEquals("先回到广场。\n\n门口亮着灯。", RoleplayActionOptionsPolicy.withoutExplicitOptions(source))
        }
    }

    @Test
    fun declaredMarkdownFencesAgreeWithValidationAndTheRepairPreview() {
        val bare = ActionOptionsFormat("```", "```", "下一步", ActionOptionLabels.LETTERS)
        val named = ActionOptionsFormat("~~~choices", "~~~~", "", ActionOptionLabels.LETTERS)
        for ((format, title) in listOf(bare to "下一步\n", named to "")) {
            val partial = "${format.opening}\n${title}A) 查地图\nB) 找向导"
            val complete = "$partial\n${format.closing}"
            assertNull(RoleplayActionOptionsPolicy.validationError(format))
            assertEquals(1, RoleplayActionOptionsPolicy.blocks(complete, format).size)
            assertTrue(RoleplayActionOptionsPolicy.blocks(partial, format).isEmpty())
            assertEquals(complete, RoleplayActionOptionsPolicy.completeTrailingBlock(partial, format))
            assertTrue(RoleplayActionOptionsPolicy.blocks(complete).isEmpty())
        }
    }

    @Test
    fun impossibleOrAmbiguousFenceConfigurationsAreRejectedAtImportAndPreview() {
        val incompatible = listOf(
            ActionOptionsFormat("```choices", "~~~", ""),
            ActionOptionsFormat("````choices", "```", ""),
            ActionOptionsFormat("<cyoa>", "```", "下一步"),
            ActionOptionsFormat("```", "```", ""),
            ActionOptionsFormat("> <cyoa>", "> </cyoa>", "下一步"),
            ActionOptionsFormat("<cyoa>", "</cyoa>", "</cyoa>"),
            ActionOptionsFormat("<cyoa> ```", "</cyoa>", "下一步"),
            ActionOptionsFormat("<cyoa>", "</cyoa>", "下一步 ```"),
            ActionOptionsFormat("~~~choices", "~~~", "下一步 ```"),
            ActionOptionsFormat("~~~choices ```", "~~~", "")
        )
        for (format in incompatible) {
            assertNotNull(format.toString(), RoleplayActionOptionsPolicy.validationError(format))
            assertTrue(RoleplayActionOptionsPolicy.blocks(menu, format).isEmpty())
            assertThrows(IllegalArgumentException::class.java) {
                FormatCardPackage(name = "无效格式", content = "样本", actionOptionsFormat = format).validateForImport()
            }
        }
    }

    @Test
    fun inertMarkdownFenceExamplesRemainReadableAndUnchanged() {
        val active = "```action choices\n1. 查地图\n2. 找向导\n```"
        val declared = ActionOptionsFormat("```action choices", "```", "")
        val literals = listOf(
            "````text\n$active\n````",
            "~~~markdown\n$active\n~~~",
            "~~~markdown\n$active",
            "```text\n行动选项\n1. 查地图\n2. 找向导\n```",
            "```\n1. 查地图\n2. 找向导\n```",
            active.lineSequence().joinToString("\n") { "> $it" },
            active.lineSequence().joinToString("\n") { "    $it" },
            active.removeSuffix("\n```"),
            "---\n1. 地图\n2. 水\n---"
        )
        for (literal in literals) {
            assertTrue(literal, RoleplayActionOptionsPolicy.blocks(literal).isEmpty())
            assertEquals(literal, RoleplayActionOptionsPolicy.withoutExplicitOptions(literal, declared))
        }
        val ambiguous = "~~~choices\n1. 样例 ``` 继续\n2. 找向导\n~~~\n门口亮着灯。"
        val tildeRule = ActionOptionsFormat("~~~choices", "~~~", "")
        assertEquals(ambiguous, RoleplayActionOptionsPolicy.withoutExplicitOptions(ambiguous, tildeRule))
    }

    @Test
    fun defaultFenceKindsUseTheSameTrustRulesWithoutRestrictingCustomCards() {
        val valid = "1. 查地图\n2. 找向导"
        val rejected = listOf(
            "1. 查看地图\n2. 查看地图",
            "1. [查看地图](https://example.invalid)\n2. 找向导",
            "1. https://example.invalid\n2. 找向导"
        )
        for ((opening, closing) in listOf(
            "---\n【行动选项】" to "---",
            "```行动选项" to "```"
        )) {
            val good = "$opening\n$valid\n$closing"
            assertEquals(listOf("查地图", "找向导"), RoleplayActionOptionsPolicy.blocks(good).single().options.map { it.text })
            for (body in rejected) {
                val literal = "$opening\n$body\n$closing"
                assertTrue(RoleplayActionOptionsPolicy.blocks(literal).isEmpty())
                assertEquals(literal, RoleplayActionOptionsPolicy.withoutExplicitOptions(literal))
            }
        }
        val configured = ActionOptionsFormat("<actions>", "</actions>", "")
        for (body in rejected) {
            assertEquals(2, RoleplayActionOptionsPolicy.blocks("<actions>\n$body\n</actions>", configured)
                .single().options.size)
        }
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
        val declared = ActionOptionsFormat("~~~choice menu", "~~~", "", ActionOptionLabels.LETTERS)
        val packageData = FormatCardPackage(name = "航海卡", content = "示例", actionOptionsFormat = declared)
        val format = Json.decodeFromString(FormatCardPackage.serializer(),
            Json.encodeToString(FormatCardPackage.serializer(), packageData)).actionOptionsFormat
        val fenced = "~~~choice menu\nA) 查地图\nB) 找向导\n~~~"
        for ((options, rule) in listOf(menu to custom, fenced to format)) {
            val story = "已经到达广场。\n$options\n门口亮着灯。\n```text\n衣着：蓝外套\n```"
            val original = ChatMessage.create("sample", MessageRole.ASSISTANT, story).copy(actionOptionsFormat = rule)
            val later = MessageAlternativeVersionPolicy.append(original, "其他版本", "later", actionOptionsFormat = null)
            val message = MessageAlternativeVersionPolicy.select(later, 0)
            val chat = ChatHistoryActionProjection.prepare(
                listOf(message), setOf(message.id), ChatHistoryActionRequestSource.UI_CONTINUATION
            ).project(message, message.displayContent)
            val image = PromptTemplates.novelAiImagePromptAssistantScene(message)
            val context = VoiceAnchorPolicy.promptContextText(story, message.displayActionOptionsFormat)
            for (projection in listOf(chat, image, context)) {
                assertTrue(projection.contains("蓝外套"))
                assertTrue(projection.contains("已经到达广场"))
                assertFalse(projection.contains("找向导"))
            }
            for (segmented in listOf(false, true)) {
                val spoken = VoiceGenerationPolicy.generationSegments(story, true, segmented, message.displayActionOptionsFormat)
                    .joinToString { it.spokenText }
                assertTrue(spoken.contains("门口亮着灯"))
                assertFalse(spoken.contains("查地图"))
            }
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

    @Test
    fun repairDistinguishesAClosingLineFromAnOptionEndingInTheSameMarker() {
        val format = ActionOptionsFormat("[ACTIONS]", "[END-ACTIONS]", "下一步")
        val partial = "营地很安静。\n[ACTIONS]\n下一步\n1. 查地图\n2. 核对日志标记 [END-ACTIONS]"
        val complete = "$partial\n[END-ACTIONS]"
        assertTrue(RoleplayActionOptionsPolicy.blocks(partial, format).isEmpty())
        val repaired = RoleplayActionOptionsPolicy.completeTrailingBlock(partial, format)
        assertEquals(complete, repaired)
        assertEquals(
            listOf("查地图", "核对日志标记 [END-ACTIONS]"),
            RoleplayActionOptionsPolicy.blocks(repaired, format).single().options.map { it.text }
        )
        for (unchanged in listOf("$complete\r\n ", "$partial\n已经开始行动。", "纯旁白 [END-ACTIONS]")) {
            assertEquals(unchanged, RoleplayActionOptionsPolicy.completeTrailingBlock(unchanged, format))
        }
        val continued = "营地很安静。\n[ACTIONS]\n下一步\n1. 查地图\n2. 核对日志标记\n    [END-ACTIONS]"
        val repairedContinuation = RoleplayActionOptionsPolicy.completeTrailingBlock(continued, format)
        assertEquals("$continued\n[END-ACTIONS]", repairedContinuation)
        assertEquals(
            "核对日志标记\n[END-ACTIONS]",
            RoleplayActionOptionsPolicy.blocks(repairedContinuation, format).single().options.last().text
        )
        val listCode = "- ~~~text\n  [ACTIONS]\n  下一步\n  1. 查地图\n  2. 核对日志标记 [END-ACTIONS]"
        assertEquals(listCode, RoleplayActionOptionsPolicy.completeTrailingBlock(listCode, format))
        assertTrue(RoleplayActionOptionsPolicy.blocks("$listCode\n  [END-ACTIONS]\n  ~~~", format).isEmpty())
    }
}
