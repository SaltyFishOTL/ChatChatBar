package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.prompt.PromptTemplates
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterAppearanceImageServiceTest {
    @Test
    fun `parser accepts fenced json and chooses complete final candidate`() {
        val raw = """
            分析中：
            {"appearance":"银发"}
            ```json
            {
              "appearance": "  银色长发，蓝色眼睛  ",
              "clothing": "  白色立领长裙，黑色短靴  "
            }
            ```
        """.trimIndent()

        val draft = CharacterAppearanceImageService.parseDraft(raw)

        assertEquals("银色长发，蓝色眼睛", draft?.appearance)
        assertEquals("白色立领长裙，黑色短靴", draft?.clothing)
    }

    @Test
    fun `parser rejects empty result`() {
        assertNull(
            CharacterAppearanceImageService.parseDraft(
                """{"appearance":"  ","clothing":""}"""
            )
        )
    }

    @Test
    fun `multimodal current model is preferred`() {
        val current = model(id = "chat-vision", multimodal = true)
        val linked = model(id = "linked-vision", multimodal = true)

        val selected = selectCharacterAppearanceImageModel(current, linked)

        assertSame(current, selected)
    }

    @Test
    fun `text current model uses exact linked multimodal model`() {
        val current = model(
            id = "chat-text",
            multimodal = false,
            visionModelId = "linked-vision"
        )
        val linked = model(id = "linked-vision", multimodal = true)

        val selected = selectCharacterAppearanceImageModel(current, linked)

        assertSame(linked, selected)
    }

    @Test
    fun `invalid linked model is rejected`() {
        val current = model(
            id = "chat-text",
            multimodal = false,
            visionModelId = "linked-vision"
        )

        assertNull(
            selectCharacterAppearanceImageModel(
                current,
                model(id = "other-vision", multimodal = true)
            )
        )
        assertNull(
            selectCharacterAppearanceImageModel(
                current,
                model(id = "linked-vision", multimodal = false)
            )
        )
    }

    @Test
    fun `prompt keeps required json protocol in templates`() {
        val system = PromptTemplates.CHARACTER_APPEARANCE_IMAGE_SYSTEM_PROMPT
        val user = PromptTemplates.characterAppearanceImageUserPrompt("林雾")

        assertTrue(system.contains("\"appearance\""))
        assertTrue(system.contains("\"clothing\""))
        assertTrue(user.contains("林雾"))
        assertTrue(
            PromptTemplates.CHARACTER_APPEARANCE_IMAGE_USER_PROMPT_TEMPLATE
                .contains("{{characterName}}")
        )
    }

    private fun model(
        id: String,
        multimodal: Boolean,
        visionModelId: String? = null
    ): ModelConfig = ModelConfig(
        id = id,
        displayName = id,
        baseUrl = "https://example.com/v1",
        apiKey = "key",
        modelName = id,
        isMultimodal = multimodal,
        visionModelId = visionModelId,
        createdAt = 1L
    )
}
