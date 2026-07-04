package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterEditMode
import com.example.chatbar.data.local.entity.CharacterInfo
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
        assertFalse(prompt.contains("角色卡基本描述与设定"))
        assertFalse(prompt.contains("【人物设定】"))
    }

    @Test fun blankLongTermMemoryIsOmitted() {
        val prompt = assembler.assembleSystemPrompt(card(), longTermMemory = "   ")
        assertFalse(prompt.contains("Long-Term Memory"))
    }

    @Test fun longTermMemoryIsInjectedWhenPresent() {
        val prompt = assembler.assembleSystemPrompt(card(), longTermMemory = "User prefers concise replies.")
        assertTrue(prompt.contains("Long-Term Memory"))
        assertTrue(prompt.contains("User prefers concise replies."))
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
