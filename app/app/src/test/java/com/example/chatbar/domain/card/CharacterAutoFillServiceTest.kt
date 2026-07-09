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

class CharacterAutoFillServiceTest {
    @Test
    fun `parser accepts fenced json with surrounding text`() {
        val raw = """
            draft:
            ```json
            {
              "name": "雨巷事务所",
              "greeting": "门铃响了。",
              "characters": [
                {"name": "林雾", "imagePrompt": "1girl, black hair, gray eyes"}
              ]
            }
            ```
            done
        """.trimIndent()

        val draft = CharacterAutoFillService.parseDraft(raw)

        assertEquals("雨巷事务所", draft?.name)
        assertEquals("林雾", draft?.characters?.single()?.name)
    }

    @Test
    fun `parser extracts final json after thinking text`() {
        val raw = """
            thinking:
            {"name":"Wrong draft"}
            Some prose with loose braces: {not json}
            final:
            {
              "name": "Right draft",
              "greeting": "Hello",
              "characters": [
                {"name": "Alice", "profile": "Detective"}
              ]
            }
        """.trimIndent()

        val draft = CharacterAutoFillService.parseDraft(raw)

        assertEquals("Right draft", draft?.name)
        assertEquals("Hello", draft?.greeting)
        assertEquals("Alice", draft?.characters?.single()?.name)
        assertEquals("Detective", draft?.characters?.single()?.profile)
    }

    @Test
    fun `system prompt does not mention fields outside auto fill surface`() {
        val prompt = PromptTemplates.CHARACTER_AUTO_FILL_SYSTEM_PROMPT

        assertTrue(prompt.contains("角色卡可以包含多个 characters"))
        assertTrue(prompt.contains("fillTargets.createCharacters"))
        assertTrue(prompt.contains("每个 fillTargets.characters 项只对应一个已有角色槽位"))
        assertTrue(prompt.contains("sourceImageInstructions/sourceImageDescription"))
        listOf(
            "document",
            "world book",
            "system prompt",
            "post-history",
            "creator notes",
            "metadata",
            "mesExample",
            "replaceDefaultEmptyCharacter",
            "maxCharacters",
            "createCharacters.limit",
            "major characters",
            "1-6"
        ).forEach { forbidden ->
            assertFalse(prompt.contains(forbidden, ignoreCase = true))
        }
    }

    @Test
    fun `prompt payload exposes targets and locked context only`() {
        val current = card(
            name = "Existing card",
            greeting = "Existing greeting",
            basicSetting = "",
            defaultImagePrompt = "existing style",
            characters = listOf(
                CharacterInfo(
                    id = "c1",
                    name = "Alice",
                    profile = "",
                    appearance = "silver short hair"
                )
            ),
            documents = listOf(DocumentInfo.create("notes.txt", "/tmp/notes.txt", "txt")),
            worldBookIds = listOf("world-1"),
            systemPrompt = "system override",
            postHistoryInstructions = "post override",
            mesExample = "example"
        )

        val payload = CharacterAutoFillService.buildPromptPayload("fill her", current)
        val root = Json.parseToJsonElement(payload).jsonObject
        val cardTargets = root.getValue("fillTargets").jsonObject
            .getValue("card").jsonArray
            .map { it.jsonPrimitive.content }
        val characterTarget = root.getValue("fillTargets").jsonObject
            .getValue("characters").jsonArray.single().jsonObject
        val createCharacters = root.getValue("fillTargets").jsonObject
            .getValue("createCharacters").jsonObject
        val characterFields = characterTarget.getValue("fields").jsonArray
            .map { it.jsonPrimitive.content }
        val locked = root.getValue("lockedContext").jsonObject

        assertEquals(listOf("basicSetting"), cardTargets)
        assertEquals("fillCharacterSlot", characterTarget.getValue("mode").jsonPrimitive.content)
        assertEquals("0", characterTarget.getValue("index").jsonPrimitive.content)
        assertEquals("Alice", characterTarget.getValue("matchName").jsonPrimitive.content)
        assertEquals("true", createCharacters.getValue("enabled").jsonPrimitive.content)
        assertFalse(createCharacters.containsKey("limit"))
        assertEquals(
            listOf("profile", "clothing", "abilities", "habits", "background", "relationships", "speakingStyle", "imagePrompt"),
            characterFields
        )
        assertEquals("Existing card", locked.getValue("name").jsonPrimitive.content)
        assertFalse(payload.contains("customDocuments"))
        assertFalse(payload.contains("worldBook"))
        assertFalse(payload.contains("systemPrompt"))
        assertFalse(payload.contains("postHistory"))
        assertFalse(payload.contains("mesExample"))
        assertFalse(payload.contains("notes.txt"))
    }

    @Test
    fun `prompt payload lets default empty card create character list`() {
        val current = card(characters = listOf(CharacterInfo.create("")))

        val payload = CharacterAutoFillService.buildPromptPayload("fill one role", current)
        val root = Json.parseToJsonElement(payload).jsonObject
        val fillTargets = root.getValue("fillTargets").jsonObject
        val characterTargets = fillTargets.getValue("characters").jsonArray
        val createCharacters = fillTargets.getValue("createCharacters").jsonObject
        val characterFields = createCharacters.getValue("fields").jsonArray
            .map { it.jsonPrimitive.content }

        assertTrue(characterTargets.isEmpty())
        assertEquals("true", createCharacters.getValue("enabled").jsonPrimitive.content)
        assertFalse(createCharacters.containsKey("limit"))
        assertEquals(
            listOf(
                "name",
                "profile",
                "appearance",
                "clothing",
                "abilities",
                "habits",
                "background",
                "relationships",
                "speakingStyle",
                "imagePrompt"
            ),
            characterFields
        )
        assertFalse(payload.contains("replaceDefaultEmptyCharacter"))
        assertFalse(payload.contains("maxCharacters"))
    }

    @Test
    fun `prompt payload includes externalResearch only when brief has content`() {
        val current = card()
        val brief = researchBrief()

        val payload = CharacterAutoFillService.buildPromptPayload("fill with canon", current, brief)
        val root = Json.parseToJsonElement(payload).jsonObject
        val research = root.getValue("externalResearch").jsonObject

        assertTrue(root.containsKey("externalResearchUsage"))
        assertEquals("Need canon", research.getValue("reason").jsonPrimitive.content)
        assertEquals("source-backed fact", research.getValue("facts").jsonArray.single().jsonPrimitive.content)
        assertTrue(payload.contains(PromptTemplates.CHARACTER_EXTERNAL_RESEARCH_USAGE_PROMPT))
        assertFalse(
            CharacterAutoFillService.buildPromptPayload("fill", current, ResearchBrief())
                .contains("externalResearch")
        )
    }

    @Test
    fun `prompt payload includes source image context only when image exists`() {
        val current = card()
        val payload = CharacterAutoFillService.buildPromptPayload(
            userInput = "",
            currentCard = current,
            imageContext = CharacterAutoFillImageContext(
                hasSourceImages = true,
                descriptions = listOf("银发少女，黑色制服，站在雨夜街道。")
            )
        )
        val root = Json.parseToJsonElement(payload).jsonObject

        assertEquals("", root.getValue("request").jsonPrimitive.content)
        assertTrue(root.containsKey("sourceImageInstructions"))
        assertEquals(
            PromptTemplates.CHARACTER_AUTO_FILL_SOURCE_IMAGE_INSTRUCTIONS,
            root.getValue("sourceImageInstructions").jsonPrimitive.content
        )
        assertEquals(
            "银发少女，黑色制服，站在雨夜街道。",
            root.getValue("sourceImageDescription").jsonPrimitive.content
        )
        assertFalse(
            CharacterAutoFillService.buildPromptPayload("fill", current)
                .contains("sourceImageInstructions")
        )
    }

    @Test
    fun `merge fills blanks without overwriting existing fields`() {
        val current = card(
            name = "已有卡名",
            greeting = "",
            basicSetting = "已有世界观",
            defaultImagePrompt = "existing style",
            characters = listOf(
                CharacterInfo(
                    id = "c1",
                    name = "林雾",
                    profile = "已有简介",
                    appearance = "",
                    imagePrompt = "existing prompt"
                )
            )
        )
        val draft = CharacterAutoFillDraft(
            name = "新卡名",
            greeting = "新开场",
            basicSetting = "新世界观",
            defaultImagePrompt = "new style",
            characters = listOf(
                CharacterAutoFillCharacterDraft(
                    name = "林雾",
                    profile = "新简介",
                    appearance = "银灰短发",
                    imagePrompt = "1girl, silver hair"
                )
            )
        )

        val merged = CharacterAutoFillService.mergeInto(current, draft)

        assertEquals("已有卡名", merged.name)
        assertEquals("新开场", merged.greeting)
        assertEquals("已有世界观", merged.basicSetting)
        assertEquals("existing style", merged.defaultImagePrompt)
        assertEquals("已有简介", merged.characters.single().profile)
        assertEquals("银灰短发", merged.characters.single().appearance)
        assertEquals("existing prompt", merged.characters.single().imagePrompt)
    }

    @Test
    fun `merge replaces default empty character with generated character list`() {
        val current = card(
            characters = listOf(CharacterInfo.create(""))
        )
        val draft = CharacterAutoFillDraft(
            characters = listOf(
                CharacterAutoFillCharacterDraft(name = "林雾", profile = "调查员"),
                CharacterAutoFillCharacterDraft(name = "沈澜", profile = "委托人")
            )
        )
        var nextId = 0

        val merged = CharacterAutoFillService.mergeInto(current, draft) { "new-${++nextId}" }

        assertEquals(listOf("new-1", "new-2"), merged.characters.map { it.id })
        assertEquals(listOf("林雾", "沈澜"), merged.characters.map { it.name })
        assertEquals(listOf("调查员", "委托人"), merged.characters.map { it.profile })
    }

    @Test
    fun `merge creates characters when current list is empty`() {
        val current = card(characters = emptyList())
        val draft = CharacterAutoFillDraft(
            characters = listOf(
                CharacterAutoFillCharacterDraft(name = "林雾", profile = "调查员"),
                CharacterAutoFillCharacterDraft(name = "沈澜", profile = "委托人")
            )
        )
        var nextId = 0

        val merged = CharacterAutoFillService.mergeInto(current, draft) { "new-${++nextId}" }

        assertEquals(listOf("new-1", "new-2"), merged.characters.map { it.id })
        assertEquals(listOf("林雾", "沈澜"), merged.characters.map { it.name })
    }

    @Test
    fun `merge keeps all generated characters when default empty card has more than six`() {
        val current = card(characters = listOf(CharacterInfo.create("")))
        val draft = CharacterAutoFillDraft(
            characters = (1..10).map { index ->
                CharacterAutoFillCharacterDraft(name = "Character $index", profile = "Profile $index")
            }
        )
        var nextId = 0

        val merged = CharacterAutoFillService.mergeInto(current, draft) { "new-${++nextId}" }

        assertEquals(10, merged.characters.size)
        assertEquals("new-10", merged.characters.last().id)
        assertEquals("Character 10", merged.characters.last().name)
    }

    @Test
    fun `merge fills multiple blank character slots by target index`() {
        val current = card(
            characters = listOf(
                CharacterInfo(id = "c1", name = ""),
                CharacterInfo(id = "c2", name = "")
            )
        )
        val draft = CharacterAutoFillDraft(
            characters = listOf(
                CharacterAutoFillCharacterDraft(targetIndex = 1, name = "沈澜", profile = "委托人"),
                CharacterAutoFillCharacterDraft(targetIndex = 0, name = "林雾", profile = "调查员")
            )
        )

        val merged = CharacterAutoFillService.mergeInto(current, draft)

        assertEquals(listOf("c1", "c2"), merged.characters.map { it.id })
        assertEquals(listOf("林雾", "沈澜"), merged.characters.map { it.name })
        assertEquals(listOf("调查员", "委托人"), merged.characters.map { it.profile })
    }

    @Test
    fun `merge appends unmatched created characters when card already has named characters`() {
        val current = card(
            characters = listOf(CharacterInfo(id = "c1", name = "林雾"))
        )
        val draft = CharacterAutoFillDraft(
            characters = listOf(
                CharacterAutoFillCharacterDraft(name = "林雾", profile = "调查员"),
                CharacterAutoFillCharacterDraft(name = "沈澜", profile = "委托人")
            )
        )

        val merged = CharacterAutoFillService.mergeInto(current, draft)

        assertEquals(2, merged.characters.size)
        assertEquals(listOf("林雾", "沈澜"), merged.characters.map { it.name })
        assertEquals(listOf("调查员", "委托人"), merged.characters.map { it.profile })
    }

    @Test
    fun `merge does not append unmatched targeted characters`() {
        val current = card(
            characters = listOf(CharacterInfo(id = "c1", name = "林雾"))
        )
        val draft = CharacterAutoFillDraft(
            characters = listOf(
                CharacterAutoFillCharacterDraft(name = "林雾", profile = "调查员"),
                CharacterAutoFillCharacterDraft(targetIndex = 3, name = "沈澜", profile = "委托人")
            )
        )

        val merged = CharacterAutoFillService.mergeInto(current, draft)

        assertEquals(1, merged.characters.size)
        assertEquals("林雾", merged.characters.single().name)
        assertEquals("调查员", merged.characters.single().profile)
    }

    @Test
    fun `merge keeps documents world books and advanced prompts unchanged`() {
        val document = DocumentInfo.create("notes.txt", "/tmp/notes.txt", "txt")
        val current = card(
            greeting = "",
            documents = listOf(document),
            worldBookIds = listOf("world-1"),
            systemPrompt = "system override",
            postHistoryInstructions = "post override",
            mesExample = "example"
        )
        val draft = CharacterAutoFillDraft(greeting = "新开场")

        val merged = CharacterAutoFillService.mergeInto(current, draft)

        assertEquals(listOf(document), merged.customDocuments)
        assertEquals(listOf("world-1"), merged.worldBookIds)
        assertEquals("system override", merged.systemPrompt)
        assertEquals("post override", merged.postHistoryInstructions)
        assertEquals("example", merged.mesExample)
    }

    @Test
    fun `blank default image prompt falls back to prompt template default`() {
        val current = card(defaultImagePrompt = "")
        val draft = CharacterAutoFillDraft(defaultImagePrompt = "")

        val merged = CharacterAutoFillService.mergeInto(current, draft)

        assertEquals(PromptTemplates.DEFAULT_CHARACTER_NAI_STYLE_PROMPT.trim(), merged.defaultImagePrompt)
        assertTrue(merged.defaultImagePrompt.isNotBlank())
        assertEquals(
            PromptTemplates.defaultCharacterNaiNegativePrompt(),
            merged.defaultImageNegativePrompt
        )
    }

    private fun card(
        name: String = "",
        greeting: String = "hello",
        basicSetting: String = "",
        defaultImagePrompt: String = "",
        defaultImageNegativePrompt: String = "",
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
        defaultImageNegativePrompt = defaultImageNegativePrompt,
        characters = characters,
        customDocuments = documents,
        worldBookIds = worldBookIds,
        systemPrompt = systemPrompt,
        postHistoryInstructions = postHistoryInstructions,
        mesExample = mesExample,
        editMode = CharacterEditMode.STRUCTURED,
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
