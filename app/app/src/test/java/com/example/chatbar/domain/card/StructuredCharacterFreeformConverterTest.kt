package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.CharacterEditMode
import com.example.chatbar.data.local.entity.CharacterInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StructuredCharacterFreeformConverterTest {
    @Test
    fun `converts model-facing fields into readable sections`() {
        val character = CharacterInfo(
            id = "alice",
            name = "爱丽丝",
            profile = "侦探。",
            appearance = "银发。",
            clothing = "黑色风衣。",
            abilities = "推理。",
            habits = "记录线索。",
            background = "来自雾都。",
            relationships = "与鲍勃是搭档。",
            speakingStyle = "简短、冷静。"
        )

        val result = StructuredCharacterFreeformConverter.convert(listOf(character))

        assertEquals(
            """
            【角色名称】
            爱丽丝

            【简介】
            侦探。

            【外貌】
            银发。

            【服装】
            黑色风衣。

            【能力】
            推理。

            【习惯与爱好】
            记录线索。

            【背景经历】
            来自雾都。

            【人际关系】
            与鲍勃是搭档。

            【语气与口癖】
            简短、冷静。
            """.trimIndent(),
            result
        )
    }

    @Test
    fun `omits blank fields and preserves character order`() {
        val characters = listOf(
            CharacterInfo(id = "alice", name = " 爱丽丝 ", appearance = " 银发 "),
            CharacterInfo(id = "blank", name = "   "),
            CharacterInfo(id = "bob", name = "鲍勃", profile = "搭档")
        )

        val result = StructuredCharacterFreeformConverter.convert(characters)

        assertEquals(
            """
            【角色名称】
            爱丽丝

            【外貌】
            银发

            【角色名称】
            鲍勃

            【简介】
            搭档
            """.trimIndent(),
            result
        )
    }

    @Test
    fun `excludes image and voice configuration fields`() {
        val character = CharacterInfo(
            id = "alice",
            name = "爱丽丝",
            appearanceImage = "/images/alice.png",
            imagePrompt = "1girl, silver hair"
        )

        val result = StructuredCharacterFreeformConverter.convert(listOf(character))

        assertEquals("【角色名称】\n爱丽丝", result)
        assertFalse(result.contains("alice.png"))
        assertFalse(result.contains("silver hair"))
    }

    @Test
    fun `reports no convertible content for blank characters`() {
        assertFalse(
            StructuredCharacterFreeformConverter.hasConvertibleContent(
                listOf(CharacterInfo(id = "blank", name = " "))
            )
        )
        assertTrue(
            StructuredCharacterFreeformConverter.hasConvertibleContent(
                listOf(CharacterInfo(id = "alice", name = "爱丽丝"))
            )
        )
    }

    @Test
    fun `transition targets freeform mode`() {
        val transition = StructuredCharacterFreeformConverter.createTransition(
            listOf(CharacterInfo(id = "alice", name = "爱丽丝"))
        )

        assertEquals(CharacterEditMode.FREEFORM, transition?.targetMode)
        assertEquals("【角色名称】\n爱丽丝", transition?.freeformCharacterText)
        assertEquals(
            null,
            StructuredCharacterFreeformConverter.createTransition(
                listOf(CharacterInfo(id = "blank", name = " "))
            )
        )
    }
}
