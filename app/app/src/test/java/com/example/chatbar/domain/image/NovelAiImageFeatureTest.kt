package com.example.chatbar.domain.image

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterInfo
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.DocumentInfo
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.domain.chat.CleartextHttpChatTemplatePolicy
import com.example.chatbar.domain.chat.StreamEvent
import com.example.chatbar.domain.prompt.PromptTemplates
import java.nio.ByteBuffer
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.msgpack.core.MessagePack

class NovelAiImageFeatureTest {
    @Test
    fun `context returns only the anchor message`() {
        val messages = listOf(
            message("1", MessageRole.USER, "old"),
            message("2", MessageRole.SYSTEM, "hidden"),
            message("3", MessageRole.ASSISTANT, "first"),
            message("4", MessageRole.USER, "second"),
            message("5", MessageRole.ASSISTANT, "third", alternatives = listOf("third alt")),
            message("6", MessageRole.USER, "future")
        )

        val result = NovelAiPromptDesigner.contextForAnchor(messages, "5")

        assertEquals(listOf("third alt"), result.map { it.displayContent })
    }

    @Test
    fun `conversation image design messages end with user preference after system rules`() {
        val request = NovelAiPromptDesigner.conversationDesignMessages(
            messages = listOf(message("1", MessageRole.ASSISTANT, "林远站在雨夜窗边。")),
            playerName = "林远",
            imageContentHint = "低角度，强调窗光。",
            finalPromptRequirement = "保持 tags 简洁。",
            cardDefaultImagePrompt = "anime screencap",
            characterImagePrompts = listOf("林雾" to "1girl, silver hair"),
            structured = true
        )

        assertEquals(
            listOf("assistant", "user", "system", "system", "system", "user"),
            request.map { it.role }
        )
        assertEquals("\$username站在雨夜窗边。", request[0].content.jsonPrimitive.content)
        assertTrue(request[1].content.jsonPrimitive.content.contains("低角度，强调窗光。"))
        assertEquals(PromptTemplates.NOVELAI_IMAGE_PROMPT_SYSTEM.trim(), request[2].content.jsonPrimitive.content)
        assertTrue(request[3].content.jsonPrimitive.content.contains("anime screencap"))
        assertTrue(request[4].content.jsonPrimitive.content.contains("林雾: 1girl, silver hair"))
        assertTrue(request[5].content.jsonPrimitive.content.contains("保持 tags 简洁。"))
    }

    @Test
    fun `conversation image design avoids assistant tail after cleartext adaptation`() {
        val request = NovelAiPromptDesigner.conversationDesignMessages(
            messages = listOf(message("1", MessageRole.ASSISTANT, "林远站在雨夜窗边。")),
            playerName = "林远",
            imageContentHint = "",
            finalPromptRequirement = "",
            cardDefaultImagePrompt = "anime screencap",
            characterImagePrompts = emptyList(),
            structured = false
        )

        val adapted = CleartextHttpChatTemplatePolicy.adaptMessages(
            messages = request,
            allowCleartextHttp = true,
            baseUrl = "http://127.0.0.1:8080/v1"
        )

        assertEquals(
            listOf("assistant", "user", "system", "assistant", "assistant", "user"),
            adapted.map { it.role }
        )
    }

    @Test
    fun `convert accepts all characters directly and caps at six`() {
        val characters = (1..7).map { index ->
            CharacterInfo(id = "$index", name = "Character $index", imagePrompt = "fixed-$index")
        }
        val card = CharacterCard(
            id = "card", name = "Card", characters = characters, greeting = "hello",
            defaultImagePrompt = "default-style", createdAt = 1, updatedAt = 1
        )
        val designed = DesignedImagePrompt(
            baseCaption = "default-style, 2girls, night street",
            sizePreset = "HORIZONTAL",
            characters = (1..7).map { DesignedCharacterPrompt("fixed-$it, adjust-$it") } +
                DesignedCharacterPrompt("ignored")
        )

        val result = NovelAiPromptDesigner.convert(card, designed)

        assertEquals("default-style, 2girls, night street", result.baseCaption)
        assertEquals(6, result.characterCaptions.size)
        assertEquals(NovelAiImageSizePreset.HORIZONTAL, result.sizePreset)
        assertEquals("fixed-1, adjust-1", result.characterCaptions.first().prompt)
        assertFalse(result.characterCaptions.any { "ignored" in it.prompt })
        assertEquals(1f / 7f, result.characterCaptions.first().center.x, 0.001f)
        assertEquals(6f / 7f, result.characterCaptions.last().center.x, 0.001f)
    }

    @Test
    fun `designed prompt JSON does not require character names or centers`() {
        val designed = Json { ignoreUnknownKeys = true }.decodeFromString(
            DesignedImagePrompt.serializer(),
            """{"sizePreset":"SQUARE","baseCaption":"1girl, room","characters":[{"caption":"black hair"}]}"""
        )

        assertEquals("SQUARE", designed.sizePreset)
        assertEquals("black hair", designed.characters.single().caption)
        assertEquals(null, designed.characters.single().center)
        assertFalse(PromptTemplates.NOVELAI_IMAGE_PROMPT_SYSTEM.contains("\"name\":\"exact name\""))
        assertFalse(PromptTemplates.NOVELAI_IMAGE_PROMPT_REPAIR_SYSTEM.contains("\"name\""))
        assertFalse(PromptTemplates.NOVELAI_IMAGE_PROMPT_SYSTEM.contains("\"center\""))
        assertFalse(PromptTemplates.NOVELAI_IMAGE_PROMPT_REPAIR_SYSTEM.contains("\"center\""))
    }

    @Test
    fun `character card cover prompt uses final card content without external resources`() {
        val card = CharacterCard(
            id = "card",
            name = "夜雨诊所",
            greeting = "雨夜里，诊室门被推开。",
            basicSetting = "都市怪谈诊所，冷淡女医生与来访者建立危险信任。",
            characters = listOf(
                CharacterInfo(
                    id = "c1",
                    name = "林雾",
                    profile = "怪谈医生",
                    appearance = "银发，灰蓝眼，冷淡表情",
                    clothing = "白大褂，黑色高领",
                    imagePrompt = "1girl, silver hair, blue-gray eyes, lab coat"
                )
            ),
            customDocuments = listOf(DocumentInfo.create("secret-notes.txt", "/tmp/secret-notes.txt", "txt")),
            worldBookIds = listOf("hidden-world"),
            defaultImagePrompt = "anime screencap",
            createdAt = 1,
            updatedAt = 1
        )

        val prompt = PromptTemplates.novelAiImagePromptCharacterCard(card)

        assertTrue(prompt.contains("夜雨诊所"))
        assertTrue(prompt.contains("都市怪谈诊所"))
        assertTrue(prompt.contains("Character 1 image prompt"))
        assertTrue(prompt.contains("silver hair"))
        assertFalse(prompt.contains("林雾"))
        assertFalse(prompt.contains("怪谈医生"))
        assertFalse(prompt.contains("银发，灰蓝眼，冷淡表情"))
        assertFalse(prompt.contains("白大褂，黑色高领"))
        assertFalse(prompt.contains("secret-notes.txt"))
        assertFalse(prompt.contains("hidden-world"))
    }

    @Test
    fun `character card cover source rejects empty cards`() {
        val emptyCard = CharacterCard(
            id = "card",
            name = "",
            greeting = "",
            characters = listOf(CharacterInfo.create("")),
            createdAt = 1,
            updatedAt = 1
        )

        assertFalse(emptyCard.hasImageDesignSource())
        assertTrue(emptyCard.copy(name = "夜雨诊所").hasImageDesignSource())
        assertFalse(
            emptyCard.copy(
                characters = listOf(CharacterInfo.create("").copy(appearance = "银发，灰蓝眼"))
            ).hasImageDesignSource()
        )
        assertTrue(
            emptyCard.copy(
                characters = listOf(CharacterInfo.create("").copy(imagePrompt = "1girl, silver hair, blue-gray eyes"))
            ).hasImageDesignSource()
        )
    }

    @Test
    fun `conversation prompt restores player name placeholder`() {
        val prompt = PromptTemplates.novelAiImagePromptConversation(
            messages = listOf(
                message("1", MessageRole.USER, "我是林远。"),
                message("2", MessageRole.ASSISTANT, "林远靠近窗边。")
            ),
            playerName = "林远"
        )

        assertTrue(prompt.contains("User: 我是\$username。"))
        assertTrue(prompt.contains("Assistant: \$username靠近窗边。"))
        assertFalse(prompt.contains("林远"))
    }

    @Test
    fun `blank manual image requirements keep conversation prompt unchanged`() {
        val messages = listOf(message("1", MessageRole.ASSISTANT, "她站在窗边。"))
        val base = PromptTemplates.novelAiImagePromptConversation(messages)

        val withBlankRequirements = PromptTemplates.novelAiImagePromptConversation(
            messages = messages,
            imageContentHint = " ",
            finalPromptRequirement = "\n"
        )

        assertEquals(base, withBlankRequirements)
    }

    @Test
    fun `manual image requirements append after scene messages`() {
        val prompt = PromptTemplates.novelAiImagePromptConversation(
            messages = listOf(message("1", MessageRole.ASSISTANT, "她站在窗边。")),
            imageContentHint = "低角度，强调雨夜窗光。",
            finalPromptRequirement = "baseCaption 先写构图，不要堆砌无关 tags。"
        )

        assertTrue(prompt.contains("Assistant: 她站在窗边。"))
        assertTrue(prompt.contains("用户针对本次画面的额外要求"))
        assertTrue(prompt.contains("低角度，强调雨夜窗光。"))
        assertTrue(prompt.contains("用户针对最终 NovelAI Prompt 的要求"))
        assertTrue(prompt.contains("baseCaption 先写构图"))
        assertTrue(prompt.indexOf("Assistant: 她站在窗边。") < prompt.indexOf("用户针对本次画面的额外要求"))
    }

    @Test
    fun `character avatar design uses shared prompt system and avatar task`() {
        val prompt = NovelAiPromptDesigner.characterAvatarDesignDebugInput(
            characterName = "林雾",
            stylePrompt = "very aesthetic, anime screencap",
            characterPrompt = "1girl, silver hair, blue-gray eyes",
            finalPromptRequirement = "solo, portrait"
        )

        assertTrue(prompt.contains("[system]"))
        assertTrue(prompt.contains("Preset style prompt (include verbatim in baseCaption):"))
        assertTrue(prompt.contains("very aesthetic, anime screencap"))
        assertTrue(prompt.contains("Character preset prompts (include verbatim at start of each character's caption):"))
        assertTrue(prompt.contains("- 林雾: 1girl, silver hair, blue-gray eyes"))
        assertTrue(prompt.contains("1girl, silver hair, blue-gray eyes"))
        assertTrue(prompt.contains("[user]"))
        assertTrue(prompt.contains("角色专属头像"))
        assertTrue(prompt.contains("完整 Preset style prompt 到 `baseCaption`"))
        assertTrue(prompt.contains("完整 Character preset prompt 到 `characters[].caption`"))
        assertTrue(prompt.contains("明确冲突"))
        assertTrue(prompt.contains("solo, portrait"))
    }

    @Test
    fun `moment and cover prompts include final prompt requirement`() {
        val requirement = "最终 tags 必须保持简洁。"
        val momentPrompt = PromptTemplates.novelAiImagePromptMoment(
            momentImageBrief = "镜中自拍",
            finalPromptRequirement = requirement
        )
        val coverPrompt = PromptTemplates.novelAiImagePromptCharacterCard(
            card = CharacterCard(
                id = "card",
                name = "夜雨诊所",
                greeting = "雨夜里，诊室门被推开。",
                defaultImagePrompt = "anime screencap",
                createdAt = 1,
                updatedAt = 1
            ),
            finalPromptRequirement = requirement
        )

        assertTrue(momentPrompt.contains("用户针对最终 NovelAI Prompt 的要求"))
        assertTrue(momentPrompt.contains(requirement))
        assertTrue(coverPrompt.contains("用户针对最终 NovelAI Prompt 的要求"))
        assertTrue(coverPrompt.contains(requirement))
    }

    @Test
    fun `length prefixed decoder handles fragmented and combined frames`() {
        val first = byteArrayOf(1, 2, 3)
        val second = byteArrayOf(4, 5)
        val encoded = frame(first) + frame(second)
        val decoder = NovelAiStreamFrameDecoder()

        assertTrue(decoder.feed(encoded.copyOfRange(0, 5)).isEmpty())
        val result = decoder.feed(encoded.copyOfRange(5, encoded.size))

        assertEquals(2, result.size)
        assertArrayEquals(first, result[0])
        assertArrayEquals(second, result[1])
    }

    @Test
    fun `messagepack intermediate and final events decode`() {
        val service = NovelAiImageService()
        val preview = byteArrayOf(1, 3, 5)
        val intermediate = service.decodeFrame(eventFrame("intermediate", preview, step = 12))
        val final = service.decodeFrame(eventFrame("final", preview))

        assertTrue(intermediate is NovelAiImageEvent.Intermediate)
        assertEquals(12, (intermediate as NovelAiImageEvent.Intermediate).step)
        assertArrayEquals(preview, intermediate.image)
        assertTrue(final is NovelAiImageEvent.Final)
        assertArrayEquals(preview, (final as NovelAiImageEvent.Final).image)
    }

    @Test
    fun `request uses fixed v45 defaults and v4 captions`() {
        val body = NovelAiImageService().buildRequestBody(
            NovelAiPromptPlan(
                "scene",
                listOf(
                    NovelAiCharacterCaption(
                        "girl, black hair",
                        DesignedCharacterCenter(0.25f, 0.6f)
                    )
                )
            ),
            seed = 42
        )
        val root = Json.parseToJsonElement(body).jsonObject
        val parameters = root.getValue("parameters").jsonObject
        val caption = parameters.getValue("v4_prompt").jsonObject
            .getValue("caption").jsonObject

        assertEquals("nai-diffusion-4-5-full", root.getValue("model").jsonPrimitive.content)
        assertEquals("832", parameters.getValue("width").jsonPrimitive.content)
        assertEquals("1216", parameters.getValue("height").jsonPrimitive.content)
        assertEquals("28", parameters.getValue("steps").jsonPrimitive.content)
        assertEquals("8.0", parameters.getValue("scale").jsonPrimitive.content)
        assertEquals("msgpack", parameters.getValue("stream").jsonPrimitive.content)
        assertEquals("scene", caption.getValue("base_caption").jsonPrimitive.content)
        assertEquals("42", parameters.getValue("seed").jsonPrimitive.content)
        assertEquals("false", parameters.getValue("legacy").jsonPrimitive.content)
        assertEquals("false", parameters.getValue("legacy_v3_extend").jsonPrimitive.content)
        assertEquals("false", parameters.getValue("sm").jsonPrimitive.content)
        assertEquals("false", parameters.getValue("sm_dyn").jsonPrimitive.content)
        assertEquals("false", parameters.getValue("dynamic_thresholding").jsonPrimitive.content)
        val negativePrompt = parameters.getValue("negative_prompt").jsonPrimitive.content
        val v4NegativePrompt = parameters.getValue("v4_negative_prompt").jsonObject
            .getValue("caption").jsonObject
            .getValue("base_caption").jsonPrimitive.content
        assertTrue(negativePrompt.isNotBlank())
        assertEquals(negativePrompt, v4NegativePrompt)
        val character = caption.getValue("char_captions").jsonArray.first().jsonObject
        val center = character.getValue("centers").jsonArray.first().jsonObject
        assertEquals("0.25", center.getValue("x").jsonPrimitive.content)
        assertEquals("0.6", center.getValue("y").jsonPrimitive.content)
        assertEquals(
            "false",
            parameters.getValue("v4_prompt").jsonObject
                .getValue("use_coords").jsonPrimitive.content
        )
        assertEquals(
            "false",
            parameters.getValue("v4_negative_prompt").jsonObject
                .getValue("use_coords").jsonPrimitive.content
        )
    }

    @Test
    fun `request uses custom negative prompt for legacy and v4 captions`() {
        val body = NovelAiImageService().buildRequestBody(
            NovelAiPromptPlan(
                "scene",
                emptyList(),
                negativePrompt = "bad hands, bad hands, watermark"
            ),
            seed = 42
        )
        val parameters = Json.parseToJsonElement(body).jsonObject
            .getValue("parameters").jsonObject
        val v4NegativeCaption = parameters.getValue("v4_negative_prompt").jsonObject
            .getValue("caption").jsonObject

        assertEquals("bad hands, watermark", parameters.getValue("negative_prompt").jsonPrimitive.content)
        assertEquals("bad hands, watermark", v4NegativeCaption.getValue("base_caption").jsonPrimitive.content)
    }

    @Test
    fun `request lets NAI choose character positions`() {
        val body = NovelAiImageService().buildRequestBody(
            NovelAiPromptPlan(
                baseCaption = "2girls, outdoors",
                characterCaptions = listOf(
                    NovelAiCharacterCaption(
                        prompt = "girl, black hair",
                        center = DesignedCharacterCenter(0.25f, 0.6f)
                    )
                ),
                negativePrompt = "bad hands"
            ),
            seed = 42
        )
        val parameters = Json.parseToJsonElement(body).jsonObject
            .getValue("parameters").jsonObject

        assertEquals(
            "false",
            parameters.getValue("v4_prompt").jsonObject
                .getValue("use_coords").jsonPrimitive.content
        )
        assertEquals(
            "false",
            parameters.getValue("v4_negative_prompt").jsonObject
                .getValue("use_coords").jsonPrimitive.content
        )
    }

    @Test
    fun `custom ratio maps to normal sized request dimensions`() {
        val imageSize = NovelAiImageSizePolicy.parseUserRatio("16:9")
        val body = NovelAiImageService().buildRequestBody(
            NovelAiPromptPlan("scene", emptyList()),
            seed = 42,
            imageSize = imageSize!!
        )
        val parameters = Json.parseToJsonElement(body).jsonObject
            .getValue("parameters").jsonObject

        assertEquals("1344", parameters.getValue("width").jsonPrimitive.content)
        assertEquals("768", parameters.getValue("height").jsonPrimitive.content)
    }

    @Test
    fun `blank image ratio uses designed preset while invalid input reports error`() {
        val horizontal = NovelAiImageSizePolicy.resolve("", NovelAiImageSizePreset.HORIZONTAL)

        assertEquals(1216, horizontal.width)
        assertEquals(832, horizontal.height)
        assertEquals(null, NovelAiImageSizePolicy.validationError(""))
        assertTrue(NovelAiImageSizePolicy.validationError("wide")!!.contains("比例格式无效"))
    }

    @Test
    fun `prompt stream reports accumulated text while ignoring reasoning`() = runTest {
        val updates = mutableListOf<String>()

        val result = collectPromptText(
            flowOf(
                StreamEvent.ReasoningDelta("hidden"),
                StreamEvent.Delta("{\"scene"),
                StreamEvent.Delta("Prompt\":\"night\"}"),
                StreamEvent.Done
            ),
            updates::add
        )

        assertEquals(listOf("[思考] hidden", "{\"scene", "{\"scenePrompt\":\"night\"}"), updates)
        assertEquals("{\"scenePrompt\":\"night\"}", result)
    }

    @Test
    fun `named relation tags are removed while numeric tags remain`() {
        val prompt = "1girl, source#Oblivionis, target#2, mutual#Alice, standing"

        val normalized = NovelAiPromptDesigner.normalizeRelationTags(prompt)

        assertEquals("1girl, target#2, standing", normalized)
    }

    @Test
    fun `convert clamps centers to safe normalized bounds`() {
        val card = CharacterCard(
            id = "card",
            name = "Card",
            characters = listOf(CharacterInfo(id = "1", name = "Alice", imagePrompt = "alice")),
            greeting = "hello",
            createdAt = 1,
            updatedAt = 1
        )

        val plan = NovelAiPromptDesigner.convert(
            card,
            DesignedImagePrompt(
                baseCaption = "1girl",
                characters = listOf(
                    DesignedCharacterPrompt(
                        caption = "alice",
                        center = DesignedCharacterCenter(-2f, 4f)
                    )
                )
            )
        )

        assertEquals(0.05f, plan.characterCaptions.single().center.x, 0.001f)
        assertEquals(0.95f, plan.characterCaptions.single().center.y, 0.001f)
    }

    @Test
    fun `convert carries card negative prompt`() {
        val card = CharacterCard(
            id = "card",
            name = "Card",
            greeting = "hello",
            defaultImageNegativePrompt = "low quality, watermark",
            createdAt = 1,
            updatedAt = 1
        )

        val plan = NovelAiPromptDesigner.convert(
            card,
            DesignedImagePrompt(baseCaption = "1girl")
        )

        assertEquals("low quality, watermark", plan.negativePrompt)
    }

    @Test
    fun `baseCharacterName extracts primary name from complex names`() {
        assertEquals("千早爱音", NovelAiPromptDesigner.baseCharacterName("千早爱音/Anon；有时可被调侃成\"anon酱\"\"粉毛\""))
        assertEquals("丰川祥子", NovelAiPromptDesigner.baseCharacterName("丰川祥子;舞台名Oblivionis"))
        assertEquals("若叶睦", NovelAiPromptDesigner.baseCharacterName("若叶睦;Mutsum;舞台名Mortis"))
        assertEquals("高松灯", NovelAiPromptDesigner.baseCharacterName("高松灯"))
        assertEquals("椎名立希", NovelAiPromptDesigner.baseCharacterName("椎名立希/Taki；常被爱音叫\"Rikki、立希希\"之类会让她不爽的昵称"))
    }

    private fun message(
        id: String,
        role: MessageRole,
        content: String,
        alternatives: List<String> = emptyList()
    ) = ChatMessage(
        id = id,
        sessionId = "session",
        role = role,
        content = content,
        alternatives = alternatives,
        createdAt = id.toLong(),
        updatedAt = id.toLong()
    )

    private fun frame(payload: ByteArray): ByteArray =
        ByteBuffer.allocate(4 + payload.size).putInt(payload.size).put(payload).array()

    private fun eventFrame(type: String, image: ByteArray, step: Int? = null): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packMapHeader(if (step == null) 2 else 3)
        packer.packString("event_type")
        packer.packString(type)
        packer.packString("image")
        packer.packBinaryHeader(image.size)
        packer.writePayload(image)
        if (step != null) {
            packer.packString("step_ix")
            packer.packInt(step)
        }
        packer.close()
        return packer.toByteArray()
    }
}
