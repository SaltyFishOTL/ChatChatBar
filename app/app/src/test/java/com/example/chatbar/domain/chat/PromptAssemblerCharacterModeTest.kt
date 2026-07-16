package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterEditMode
import com.example.chatbar.data.local.entity.CharacterInfo
import com.example.chatbar.data.local.entity.FormatCard
import com.example.chatbar.data.local.entity.ChunkSourceType
import com.example.chatbar.domain.rag.RetrievedKnowledgeCard
import kotlinx.serialization.json.Json
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptAssemblerCharacterModeTest {
    private val assembler = PromptAssembler()

    @Test fun legacyJsonDefaultsToStructuredMode() {
        val card = Json { ignoreUnknownKeys = true }.decodeFromString(
            CharacterCard.serializer(),
            """{"id":"1","name":"Legacy","greeting":"Hi","createdAt":1,"updatedAt":1}"""
        )
        assertTrue(card.editMode == CharacterEditMode.STRUCTURED)
    }

    @Test fun structuredModeInjectsBasicSettingAndSkipsBlankFields() {
        val prompt = assembler.assembleSystemPrompt(
            card(
                basicSetting = "共同世界观",
                characters = listOf(CharacterInfo("p1", "林澈", profile = "记录者"), CharacterInfo("p2", ""))
            )
        )
        assertTrue(prompt.contains("【基本设定】\n共同世界观"))
        assertTrue(prompt.contains("角色名称: 林澈"))
        assertTrue(prompt.contains("简介: 记录者"))
        assertFalse(prompt.contains("角色名称: \n"))
        assertFalse(prompt.contains("外貌:"))
    }

    @Test fun freeformModeReplacesStructuredPeopleOnly() {
        val prompt = assembler.assembleSystemPrompt(
            card(
                editMode = CharacterEditMode.FREEFORM,
                basicSetting = "共同规则",
                freeformCharacterText = "自由人物原文",
                characters = listOf(CharacterInfo("p1", "不应出现"))
            )
        )
        assertTrue(prompt.contains("共同规则"))
        assertTrue(prompt.contains("【人物设定】\n自由人物原文"))
        assertFalse(prompt.contains("不应出现"))
    }

    @Test fun emptyCharacterSectionsAreOmitted() {
        val prompt = assembler.assembleSystemPrompt(card())
        assertFalse(prompt.contains("【角色设定】"))
        assertFalse(prompt.contains("【人物设定】"))
    }

    @Test fun blankLongTermMemoryIsOmitted() {
        val prompt = assembler.assembleSystemPrompt(card(), longTermMemory = "   ")
        assertFalse(prompt.contains("【长期记忆】"))
    }

    @Test fun longTermMemoryIsInjectedWhenPresent() {
        val prompt = assembler.assembleSystemPrompt(card(), longTermMemory = "User prefers concise replies.")
        assertTrue(prompt.contains("【长期记忆】"))
        assertTrue(prompt.contains("User prefers concise replies."))
    }

    @Test fun generatedSectionsUseShortTitlesWithoutNumberedSeparators() {
        val prompt = assembler.assembleSystemPrompt(
            characterCard = card(basicSetting = "共同世界观"),
            playerName = "玩家",
            playerSetting = "玩家资料",
            supplementarySetting = "临时规则",
            formatCard = FormatCard(
                id = "format",
                name = "格式",
                content = "格式正文",
                createdAt = 1
            ),
            longTermMemory = "长期内容",
            worldBookPrompt = "世界内容"
        )

        listOf(
            "【角色设定】",
            "【世界书】",
            "【格式要求】",
            "【回复要求】",
            "【长期记忆】",
            "【补充设定】",
            "【玩家设定】",
            "【核心指令】",
            "【后置指令】"
        ).forEach { assertTrue(prompt.contains(it)) }
        assertFalse(prompt.contains("=================================================="))
        assertFalse(Regex("(?m)^\\d+(?:\\.\\d+)?\\. ").containsMatchIn(prompt))
        assertFalse(prompt.contains("(World Book)"))
    }

    @Test fun replacesPlayerAndCharacterNamePlaceholdersGlobally() {
        val prompt = assembler.assembleSystemPrompt(
            characterCard = card(
                name = "林澈",
                basicSetting = "${'$'}botname 正在等待 ${'$'}username。"
            ),
            playerName = "旅人",
            playerSetting = "${'$'}username 信任 ${'$'}botname。"
        )

        assertTrue(prompt.contains("林澈 正在等待 旅人。"))
        assertTrue(prompt.contains("旅人 信任 林澈。"))
        assertFalse(prompt.contains("${'$'}username"))
        assertFalse(prompt.contains("${'$'}botname"))
    }

    @Test fun replacesCharacterNameWithoutPlayerName() {
        val prompt = assembler.assembleSystemPrompt(
            card(name = "林澈", basicSetting = "你好，${'$'}botname。")
        )

        assertTrue(prompt.contains("你好，林澈。"))
        assertFalse(prompt.contains("${'$'}botname"))
    }

    @Test fun replacesPlaceholdersInsideWorldBookOutlets() {
        val prompt = assembler.assembleSystemPrompt(
            characterCard = card(
                name = "Bot",
                basicSetting = "{{outlet::scene}}"
            ),
            playerName = "Alice",
            worldBookOutlets = mapOf("scene" to "${'$'}username meets ${'$'}botname.")
        )

        assertTrue(prompt.contains("Alice meets Bot."))
        assertFalse(prompt.contains("${'$'}username"))
        assertFalse(prompt.contains("${'$'}botname"))
    }

    @Test fun cacheLayersKeepMemoryDynamicAndPostHistoryAtTail() {
        val layers = assembler.assembleCachePromptLayers(
            characterCard = card(basicSetting = "稳定角色设定").copy(postHistoryInstructions = "尾部规则"),
            longTermMemory = "冻结前记忆",
            hasHistoryMessages = true,
            hasPreviousTurn = true
        )

        assertTrue(layers.stablePrefixCacheable)
        assertTrue(layers.stableSystemPrompt.contains("稳定角色设定"))
        assertFalse(layers.stableSystemPrompt.contains("【长期记忆】"))
        assertTrue(layers.stableSystemPrompt.endsWith("【聊天记录】"))
        assertTrue(layers.dynamicSystemPrompt.contains("冻结前记忆"))
        assertTrue(layers.tailSystemPrompt.contains("尾部规则"))
        assertTrue(layers.tailSystemPrompt.endsWith("【上一轮】"))
    }

    @Test fun cacheLayersOmitHistoryHeadingsWhenGroupsAreEmpty() {
        val layers = assembler.assembleCachePromptLayers(characterCard = card())

        assertFalse(layers.stableSystemPrompt.contains("【聊天记录】"))
        assertFalse(layers.tailSystemPrompt.contains("【上一轮】"))
    }

    @Test fun cacheLayersFallBackWhenStaticPromptDependsOnDynamicOutlet() {
        val layers = assembler.assembleCachePromptLayers(
            characterCard = card(basicSetting = "{{outlet::scene}}"),
            worldBookOutlets = mapOf("scene" to "动态场景")
        )

        assertFalse(layers.stablePrefixCacheable)
        assertTrue(layers.stableSystemPrompt.contains("动态场景"))
        assertTrue(layers.dynamicSystemPrompt.isBlank())
    }

    @Test fun dynamicLayersKeepArchiveWorldBookRagHeadTimelineOrder() {
        val layers = assembler.assembleCachePromptLayers(
            characterCard = card(basicSetting = "stable"),
            memoryArchive = "【ARCHIVE｜历史档案】\n[Era T0-T20] archive",
            worldBookPrompt = "world-book",
            ragResults = listOf(
                RetrievedKnowledgeCard(
                    id = "rag",
                    type = ChunkSourceType.CHAT_MEMORY,
                    sourceId = "session",
                    sourceLabel = "memory",
                    content = "recalled",
                    metadata = mapOf("timelineStart" to "8", "timelineEnd" to "8")
                )
            ),
            memoryHeadAndTimeline = "【HEAD｜当前状态｜截至 T30】\nhead\n【时间线约束】\nconstraint",
            hasHistoryMessages = true,
            hasPreviousTurn = true
        )
        val dynamic = layers.dynamicSystemPrompt

        val archive = dynamic.indexOf("【ARCHIVE｜历史档案】")
        val worldBook = dynamic.indexOf("【世界书】")
        val rag = dynamic.indexOf("【RAG｜召回资料】")
        val head = dynamic.indexOf("【HEAD｜当前状态｜截至 T30】")

        assertTrue(archive >= 0)
        assertTrue(archive < worldBook)
        assertTrue(worldBook < rag)
        assertTrue(rag < head)
        assertTrue(dynamic.contains("[卡片 1]"))
        assertFalse(dynamic.contains("[召回自 T8]"))
        assertTrue(layers.stableSystemPrompt.endsWith("【聊天记录】"))
        assertTrue(layers.tailSystemPrompt.endsWith("【上一轮】"))
    }

    private fun card(
        name: String = "Card",
        editMode: CharacterEditMode = CharacterEditMode.STRUCTURED,
        basicSetting: String = "",
        freeformCharacterText: String = "",
        characters: List<CharacterInfo> = emptyList()
    ) = CharacterCard(
        id = "c1",
        name = name,
        greeting = "Hello",
        editMode = editMode,
        basicSetting = basicSetting,
        freeformCharacterText = freeformCharacterText,
        characters = characters,
        createdAt = 1,
        updatedAt = 1
    )
}
