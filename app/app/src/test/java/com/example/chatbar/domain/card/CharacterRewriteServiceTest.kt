package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterEditMode
import com.example.chatbar.data.local.entity.CharacterInfo
import com.example.chatbar.data.local.entity.DocumentInfo
import com.example.chatbar.domain.prompt.PromptTemplates
import com.example.chatbar.domain.search.ResearchBrief
import com.example.chatbar.domain.search.ResearchSource
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterRewriteServiceTest {
    @Test
    fun `parser accepts fenced json with surrounding text and empty string patches`() {
        val raw = """
            patch:
            ```json
            {
              "name": "",
              "characters": [
                {"id": "c1", "background": ""}
              ]
            }
            ```
        """.trimIndent()

        val draft = CharacterRewriteService.parseDraft(raw)

        assertEquals("", draft?.name)
        assertEquals("c1", draft?.characters?.single()?.id)
        assertEquals("", draft?.characters?.single()?.background)
    }

    @Test
    fun `parser extracts final rewrite json after thinking text`() {
        val raw = """
            thinking:
            {"name":"Wrong draft"}
            This part has loose braces: {not json}
            final:
            {
              "name": "Right draft",
              "characters": [
                {"id": "c1", "profile": "Colder"}
              ]
            }
        """.trimIndent()

        val draft = CharacterRewriteService.parseDraft(raw)

        assertEquals("Right draft", draft?.name)
        assertEquals("c1", draft?.characters?.single()?.id)
        assertEquals("Colder", draft?.characters?.single()?.profile)
    }

    @Test
    fun `structured payload does not expose freeform or excluded fields`() {
        val current = card(
            editMode = CharacterEditMode.STRUCTURED,
            freeformCharacterText = "自由模式秘密",
            characters = listOf(CharacterInfo(id = "c1", name = "林雾", profile = "调查员")),
            documents = listOf(DocumentInfo.create("notes.txt", "/tmp/notes.txt", "txt")),
            worldBookIds = listOf("world-1"),
            systemPrompt = "system override",
            postHistoryInstructions = "post override",
            mesExample = "example"
        )

        val payload = CharacterRewriteService.buildPromptPayload("改冷淡", current)
        val root = Json.parseToJsonElement(payload).jsonObject
        val currentJson = root.getValue("current").jsonObject
        val schemaJson = root.getValue("outputSchema").jsonObject
        val allowedKeys = schemaJson.getValue("allowedTopLevelKeys").jsonArray.map { it.jsonPrimitive.content }

        assertTrue(currentJson.containsKey("characters"))
        assertTrue(root.containsKey("characterImageGuide"))
        assertTrue(allowedKeys.contains("characters"))
        assertTrue(allowedKeys.contains("deleteCharacterIds"))
        assertFalse(allowedKeys.contains("freeformCharacterText"))
        assertFalse(currentJson.containsKey("freeformCharacterText"))
        assertFalse(payload.contains("自由模式秘密"))
        assertFalse(payload.contains("customDocuments"))
        assertFalse(payload.contains("worldBook"))
        assertFalse(payload.contains("systemPrompt"))
        assertFalse(payload.contains("postHistory"))
        assertFalse(payload.contains("mesExample"))
        assertFalse(payload.contains("notes.txt"))
    }

    @Test
    fun `freeform payload does not expose structured characters`() {
        val current = card(
            editMode = CharacterEditMode.FREEFORM,
            freeformCharacterText = "自由人物原文",
            characters = listOf(CharacterInfo(id = "c1", name = "结构化人物", profile = "不能暴露"))
        )

        val payload = CharacterRewriteService.buildPromptPayload("改轻松", current)
        val root = Json.parseToJsonElement(payload).jsonObject
        val currentJson = root.getValue("current").jsonObject
        val schemaJson = root.getValue("outputSchema").jsonObject
        val allowedKeys = schemaJson.getValue("allowedTopLevelKeys").jsonArray.map { it.jsonPrimitive.content }

        assertEquals("自由人物原文", currentJson.getValue("freeformCharacterText").jsonPrimitive.content)
        assertFalse(root.containsKey("characterImageGuide"))
        assertTrue(allowedKeys.contains("freeformCharacterText"))
        assertFalse(allowedKeys.contains("characters"))
        assertFalse(allowedKeys.contains("deleteCharacterIds"))
        assertFalse(currentJson.containsKey("characters"))
        assertFalse(payload.contains("结构化人物"))
        assertFalse(payload.contains("不能暴露"))
    }

    @Test
    fun `payload current only includes nonblank current fields`() {
        val current = card(
            name = "",
            greeting = "hello",
            basicSetting = "",
            defaultImagePrompt = "",
            characters = listOf(
                CharacterInfo(id = "blank", name = ""),
                CharacterInfo(id = "c1", name = "林雾", appearance = "银发")
            )
        )

        val payload = CharacterRewriteService.buildPromptPayload("让她更冷", current)
        val currentJson = Json.parseToJsonElement(payload).jsonObject.getValue("current").jsonObject
        val characterJson = currentJson.getValue("characters").jsonArray.single().jsonObject

        assertFalse(currentJson.containsKey("name"))
        assertEquals("hello", currentJson.getValue("greeting").jsonPrimitive.content)
        assertFalse(currentJson.containsKey("basicSetting"))
        assertFalse(currentJson.containsKey("defaultImagePrompt"))
        assertEquals("c1", characterJson.getValue("id").jsonPrimitive.content)
        assertEquals("林雾", characterJson.getValue("name").jsonPrimitive.content)
        assertEquals("银发", characterJson.getValue("appearance").jsonPrimitive.content)
        assertFalse(characterJson.containsKey("profile"))
        assertFalse(characterJson.containsKey("clothing"))
    }

    @Test
    fun `rewrite payload includes externalResearch only when brief has content`() {
        val current = card()
        val brief = researchBrief()

        val payload = CharacterRewriteService.buildPromptPayload("make canon accurate", current, brief)
        val root = Json.parseToJsonElement(payload).jsonObject
        val research = root.getValue("externalResearch").jsonObject

        assertTrue(root.containsKey("externalResearchUsage"))
        assertEquals("Need canon", research.getValue("reason").jsonPrimitive.content)
        assertEquals("source-backed fact", research.getValue("facts").jsonArray.single().jsonPrimitive.content)
        assertTrue(payload.contains(PromptTemplates.CHARACTER_EXTERNAL_RESEARCH_USAGE_PROMPT))
        assertFalse(
            CharacterRewriteService.buildPromptPayload("rewrite", current, ResearchBrief())
                .contains("externalResearch")
        )
    }

    @Test
    fun `rewrite prompt does not embed dual mode schemas`() {
        val prompt = PromptTemplates.CHARACTER_REWRITE_SYSTEM_PROMPT

        assertFalse(prompt.contains("不要同时处理两种编辑模式"))
        assertFalse(prompt.contains("STRUCTURED 模式输出结构"))
        assertFalse(prompt.contains("FREEFORM 模式输出结构"))
        assertFalse(prompt.contains("只返回完成 request 必须变化的字段"))
        assertFalse(prompt.contains("JSON patch"))
        assertTrue(prompt.contains("outputSchema"))
    }

    @Test
    fun `materialize structured rewrite fills omitted existing fields from current`() {
        val current = card(
            name = "旧卡名",
            greeting = "旧开场",
            basicSetting = "旧设定",
            defaultImagePrompt = "旧风格",
            characters = listOf(
                CharacterInfo(
                    id = "c1",
                    name = "林雾",
                    profile = "旧简介",
                    appearance = "银发",
                    clothing = "白大褂",
                    background = "旧背景"
                ),
                CharacterInfo(id = "c2", name = "沈澜", profile = "助手")
            )
        )
        val patch = CharacterRewriteDraft(
            basicSetting = "新设定",
            characters = listOf(CharacterRewriteCharacterDraft(id = "c1", profile = "新简介"))
        )

        val materialized = CharacterRewriteService.materializeDraft(current, patch)

        assertEquals("旧卡名", materialized.name)
        assertEquals("旧开场", materialized.greeting)
        assertEquals("新设定", materialized.basicSetting)
        assertEquals("旧风格", materialized.defaultImagePrompt)
        assertEquals(2, materialized.characters.size)
        val first = materialized.characters.first { it.id == "c1" }
        assertEquals("林雾", first.name)
        assertEquals("新简介", first.profile)
        assertEquals("银发", first.appearance)
        assertEquals("白大褂", first.clothing)
        assertEquals("旧背景", first.background)
        val second = materialized.characters.first { it.id == "c2" }
        assertEquals("沈澜", second.name)
        assertEquals("助手", second.profile)
    }

    @Test
    fun `materialize freeform rewrite fills omitted existing fields from current`() {
        val current = card(
            editMode = CharacterEditMode.FREEFORM,
            name = "旧名",
            greeting = "旧开场",
            basicSetting = "旧设定",
            defaultImagePrompt = "旧风格",
            freeformCharacterText = "旧自由文本"
        )
        val patch = CharacterRewriteDraft(freeformCharacterText = "新自由文本")

        val materialized = CharacterRewriteService.materializeDraft(current, patch)

        assertEquals("旧名", materialized.name)
        assertEquals("旧开场", materialized.greeting)
        assertEquals("旧设定", materialized.basicSetting)
        assertEquals("旧风格", materialized.defaultImagePrompt)
        assertEquals("新自由文本", materialized.freeformCharacterText)
        assertTrue(materialized.characters.isEmpty())
        assertTrue(materialized.deleteCharacterIds.isEmpty())
    }

    @Test
    fun `nullable patch keeps missing fields clears empty strings and replaces nonblank values`() {
        val current = card(
            name = "旧卡名",
            greeting = "旧开场",
            basicSetting = "旧设定",
            characters = listOf(
                CharacterInfo(
                    id = "c1",
                    name = "林雾",
                    profile = "旧简介",
                    background = "旧背景",
                    appearanceImage = "/tmp/lin.png"
                )
            )
        )
        val draft = CharacterRewriteDraft(
            greeting = "",
            basicSetting = "新设定",
            characters = listOf(
                CharacterRewriteCharacterDraft(
                    id = "c1",
                    profile = "新简介",
                    background = ""
                )
            )
        )

        val merged = CharacterRewriteService.mergeInto(current, draft)

        assertEquals("旧卡名", merged.name)
        assertEquals("", merged.greeting)
        assertEquals("新设定", merged.basicSetting)
        assertEquals("新简介", merged.characters.single().profile)
        assertEquals("", merged.characters.single().background)
        assertEquals("/tmp/lin.png", merged.characters.single().appearanceImage)
    }

    @Test
    fun `structured rewrite can delete explicit ids and omitted characters remain`() {
        val current = card(
            characters = listOf(
                CharacterInfo(id = "c1", name = "林雾"),
                CharacterInfo(id = "c2", name = "沈澜"),
                CharacterInfo(id = "c3", name = "白芷")
            )
        )
        val draft = CharacterRewriteDraft(
            deleteCharacterIds = listOf("c2"),
            characters = listOf(CharacterRewriteCharacterDraft(id = "c1", profile = "更冷淡"))
        )

        val merged = CharacterRewriteService.mergeInto(current, draft)

        assertEquals(listOf("c1", "c3"), merged.characters.map { it.id })
        assertEquals("更冷淡", merged.characters.first { it.id == "c1" }.profile)
        assertEquals("白芷", merged.characters.single { it.id == "c3" }.name)
    }

    @Test
    fun `structured rewrite adds characters without hard total limit`() {
        val current = card(
            characters = (1..5).map { CharacterInfo(id = "c$it", name = "角色$it") }
        )
        val draft = CharacterRewriteDraft(
            characters = listOf(
                CharacterRewriteCharacterDraft(name = "新增一", profile = "助手"),
                CharacterRewriteCharacterDraft(name = "新增二", profile = "顾问")
            )
        )
        var nextId = 0

        val merged = CharacterRewriteService.mergeInto(current, draft) { "new-${++nextId}" }

        assertEquals(7, merged.characters.size)
        assertEquals(listOf("new-1", "new-2"), merged.characters.takeLast(2).map { it.id })
        assertEquals(listOf("新增一", "新增二"), merged.characters.takeLast(2).map { it.name })
    }

    @Test
    fun `structured rewrite preserves existing ten character cards`() {
        val current = card(
            characters = (1..10).map { CharacterInfo(id = "c$it", name = "角色$it") }
        )
        val draft = CharacterRewriteDraft(name = "新卡名")

        val merged = CharacterRewriteService.mergeInto(current, draft)

        assertEquals(10, merged.characters.size)
        assertEquals((1..10).map { "c$it" }, merged.characters.map { it.id })
    }

    @Test
    fun `freeform rewrite only changes freeform and card fields`() {
        val originalCharacters = listOf(CharacterInfo(id = "c1", name = "结构化人物", profile = "保留"))
        val current = card(
            editMode = CharacterEditMode.FREEFORM,
            name = "旧名",
            freeformCharacterText = "旧自由文本",
            characters = originalCharacters
        )
        val draft = CharacterRewriteDraft(
            name = "新名",
            freeformCharacterText = "新自由文本",
            deleteCharacterIds = listOf("c1"),
            characters = listOf(CharacterRewriteCharacterDraft(id = "c1", name = "不应改"))
        )

        val merged = CharacterRewriteService.mergeInto(current, draft)

        assertEquals("新名", merged.name)
        assertEquals("新自由文本", merged.freeformCharacterText)
        assertEquals(originalCharacters, merged.characters)
    }

    @Test
    fun `rewrite keeps excluded card fields unchanged`() {
        val document = DocumentInfo.create("notes.txt", "/tmp/notes.txt", "txt")
        val current = card(
            documents = listOf(document),
            worldBookIds = listOf("world-1"),
            systemPrompt = "system override",
            postHistoryInstructions = "post override",
            mesExample = "example"
        )
        val draft = CharacterRewriteDraft(name = "新名")

        val merged = CharacterRewriteService.mergeInto(current, draft)

        assertEquals(listOf(document), merged.customDocuments)
        assertEquals(listOf("world-1"), merged.worldBookIds)
        assertEquals("system override", merged.systemPrompt)
        assertEquals("post override", merged.postHistoryInstructions)
        assertEquals("example", merged.mesExample)
    }

    private fun card(
        editMode: CharacterEditMode = CharacterEditMode.STRUCTURED,
        name: String = "",
        greeting: String = "hello",
        basicSetting: String = "",
        defaultImagePrompt: String = "",
        freeformCharacterText: String = "",
        characters: List<CharacterInfo> = emptyList(),
        documents: List<DocumentInfo> = emptyList(),
        worldBookIds: List<String> = emptyList(),
        systemPrompt: String = "",
        postHistoryInstructions: String = "",
        mesExample: String = ""
    ) = CharacterCard(
        id = "card",
        name = name,
        greeting = greeting,
        basicSetting = basicSetting,
        defaultImagePrompt = defaultImagePrompt,
        freeformCharacterText = freeformCharacterText,
        characters = characters,
        customDocuments = documents,
        worldBookIds = worldBookIds,
        systemPrompt = systemPrompt,
        postHistoryInstructions = postHistoryInstructions,
        mesExample = mesExample,
        editMode = editMode,
        createdAt = 1L,
        updatedAt = 1L
    )

    private fun researchBrief() = ResearchBrief(
        reason = "Need canon",
        queries = listOf("canon query"),
        facts = listOf("source-backed fact"),
        notes = listOf("wiki usage note"),
        sources = listOf(
            ResearchSource(
                sourceId = "S1",
                title = "Source",
                url = "https://example.com/source",
                sourceType = "web",
                query = "canon query",
                excerpt = "excerpt",
                score = 0.5
            )
        )
    )
}
