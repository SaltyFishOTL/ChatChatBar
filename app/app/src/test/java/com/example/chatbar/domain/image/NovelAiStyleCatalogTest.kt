package com.example.chatbar.domain.image

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NovelAiStyleCatalogTest {
    private val parser = NovelAiStyleCatalogParser(Json { ignoreUnknownKeys = true })

    @Test
    fun `valid catalog preserves order prompt and ignores unknown fields`() {
        val checkedPaths = mutableListOf<String>()
        val result = parser.parse(
            """
            {
              "schemaVersion": 1,
              "futureField": true,
              "styles": [
                {
                  "styleKey": " first ",
                  "displayName": " 第一项 ",
                  "description": " 简介一 ",
                  "prompt": " first prompt  ",
                  "previewImage": " first.webp ",
                  "futureStyleField": "ignored"
                },
                {
                  "styleKey": "second",
                  "displayName": "第二项",
                  "description": "简介二",
                  "prompt": "second prompt",
                  "previewImage": "second.webp"
                }
              ]
            }
            """.trimIndent()
        ) { path ->
            checkedPaths += path
            true
        }

        assertNull(result.fatalError)
        assertTrue(result.errors.isEmpty())
        assertEquals(listOf("first", "second"), result.styles.map { it.styleKey })
        assertEquals(" first prompt  ", result.styles.first().prompt)
        assertEquals("第一项", result.styles.first().displayName)
        assertEquals(
            listOf(
                "presets/image_styles/previews/first.webp",
                "presets/image_styles/previews/second.webp"
            ),
            checkedPaths
        )
    }

    @Test
    fun `invalid entries skip duplicate keeps first and missing preview remains fillable`() {
        val result = parser.parse(
            """
            {
              "schemaVersion": 1,
              "styles": [
                {
                  "styleKey": "kept",
                  "displayName": "保留项",
                  "description": "仍可填充",
                  "prompt": "kept prompt",
                  "previewImage": "missing.webp"
                },
                {
                  "styleKey": "kept",
                  "displayName": "重复项",
                  "description": "跳过",
                  "prompt": "duplicate prompt",
                  "previewImage": "duplicate.webp"
                },
                {
                  "styleKey": "blank-name",
                  "displayName": " ",
                  "description": "跳过",
                  "prompt": "prompt",
                  "previewImage": "blank.webp"
                },
                {
                  "styleKey": "unsafe",
                  "displayName": "越界图",
                  "description": "跳过",
                  "prompt": "prompt",
                  "previewImage": "../unsafe.webp"
                }
              ]
            }
            """.trimIndent()
        ) { false }

        assertEquals(listOf("kept"), result.styles.map { it.styleKey })
        assertFalse(result.styles.single().previewAvailable)
        assertEquals("kept prompt", result.styles.single().prompt)
        assertTrue(result.errors.any { "例图缺失" in it })
        assertTrue(result.errors.any { "重复" in it })
        assertTrue(result.errors.any { "displayName" in it })
        assertTrue(result.errors.any { "安全文件名" in it })
    }

    @Test
    fun `empty catalog returns configured empty state`() {
        val result = parser.parse("""{"schemaVersion":1,"styles":[]}""")

        assertNull(result.fatalError)
        assertTrue(result.styles.isEmpty())
        assertTrue(result.errors.isEmpty())
    }

    @Test
    fun `broken json returns fatal load result`() {
        val result = parser.parse("{broken")

        assertTrue(result.styles.isEmpty())
        assertTrue(result.fatalError.orEmpty().startsWith("画风配置读取失败"))
    }

    @Test
    fun `unsupported schema returns fatal load result`() {
        val result = parser.parse("""{"schemaVersion":2,"styles":[]}""")

        assertEquals("不支持的画风配置版本：2", result.fatalError)
        assertTrue(result.styles.isEmpty())
    }

    @Test
    fun `missing schema returns fatal load result`() {
        val result = parser.parse("""{"styles":[]}""")

        assertTrue(result.fatalError.orEmpty().startsWith("画风配置读取失败"))
        assertTrue(result.styles.isEmpty())
    }

    @Test
    fun `fill replaces prompt and undo restores one previous value`() {
        val preset = preset("first", "第一项", "new prompt")

        val applied = NovelAiStylePromptFillState("manual prompt").apply(preset)
        val restored = applied.undoLastFill()

        assertEquals("new prompt", applied.value)
        assertEquals("manual prompt", applied.undo?.previousPrompt)
        assertEquals("manual prompt", restored.value)
        assertNull(restored.undo)
    }

    @Test
    fun `consecutive fills update undo baseline`() {
        val first = preset("first", "第一项", "first prompt")
        val second = preset("second", "第二项", "second prompt")

        val state = NovelAiStylePromptFillState("manual")
            .apply(first)
            .apply(second)

        assertEquals("second prompt", state.value)
        assertEquals("first prompt", state.undo?.previousPrompt)
        assertEquals("first prompt", state.undoLastFill().value)
    }

    @Test
    fun `manual edit clears undo and cannot overwrite new content`() {
        val state = NovelAiStylePromptFillState("manual")
            .apply(preset("first", "第一项", "first prompt"))
            .edit("hand edited")

        assertNull(state.undo)
        assertEquals("hand edited", state.undoLastFill().value)
    }

    @Test
    fun `catalog prompt passes through character card into base caption unchanged`() {
        val prompt = "watercolor (medium), soft colors"
        val applied = NovelAiStylePromptFillState().apply(
            preset("watercolor", "水彩", prompt)
        )
        val card = CharacterCard(
            id = "card",
            name = "角色",
            greeting = "你好",
            defaultImagePrompt = applied.value,
            defaultImageNegativePrompt = "low quality",
            createdAt = 1L,
            updatedAt = 1L
        )

        val plan = NovelAiPromptDesigner.convert(
            card,
            DesignedImagePrompt(
                baseCaption = "1girl, rain",
                sizePreset = "SQUARE"
            )
        )
        val designMessages = NovelAiPromptDesigner.conversationDesignMessages(
            messages = listOf(
                ChatMessage.create(
                    sessionId = "session",
                    role = MessageRole.ASSISTANT,
                    content = "雨夜场景"
                )
            ),
            playerName = null,
            imageContentHint = "",
            finalPromptRequirement = "",
            characterImagePrompts = emptyList(),
            structured = false
        )

        assertEquals(prompt, card.defaultImagePrompt)
        assertEquals("low quality", card.defaultImageNegativePrompt)
        assertEquals("$prompt, 1girl, rain", plan.baseCaption)
        assertFalse(designMessages.joinToString { it.content.toString() }.contains(prompt))
        assertTrue(designMessages[3].content.jsonPrimitive.content.contains("不要在 `baseCaption`"))
    }

    private fun preset(key: String, name: String, prompt: String) = NovelAiStylePreset(
        styleKey = key,
        displayName = name,
        description = "简介",
        prompt = prompt,
        previewImage = "$key.webp"
    )
}
