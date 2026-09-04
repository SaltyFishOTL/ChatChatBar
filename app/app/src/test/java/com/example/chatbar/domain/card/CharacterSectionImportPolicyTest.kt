package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterInfo
import com.example.chatbar.data.local.entity.WorldBookEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Test

class CharacterSectionImportPolicyTest {
    @Test
    fun `world book import updates same-name entry and creates missing entry`() {
        val existing = WorldBookEntry(id = "entry-a", name = " 爱丽丝 ", keys = listOf("旧触发词"), content = "旧内容")
        val result = CharacterSectionImportPolicy.importIntoWorldBook(
            currentEntries = listOf(existing),
            sourceCharacters = listOf(
                CharacterInfo(id = "a", name = "爱丽丝", profile = "侦探", appearance = "银发"),
                CharacterInfo(id = "b", name = "鲍勃", abilities = "机械维修")
            )
        )

        assertEquals(1, result.updatedCount)
        assertEquals(1, result.createdCount)
        assertEquals("entry-a", result.entries[0].id)
        assertEquals(listOf("旧触发词"), result.entries[0].keys)
        assertEquals("【角色名称】\n爱丽丝\n\n【简介】\n侦探\n\n【外貌】\n银发", result.entries[0].content)
        assertEquals("鲍勃", result.entries[1].name)
        assertEquals(listOf("鲍勃"), result.entries[1].keys)
    }

    @Test
    fun `clear keeps profile prompt and owned integrations`() {
        val source = CharacterInfo(
            id = "a",
            name = "爱丽丝",
            profile = "侦探",
            appearance = "银发",
            clothing = "风衣",
            imagePrompt = "1girl",
            appearanceImage = "/owned/alice.png",
            fishAudioVoice = null
        )
        val card = CharacterCard.create("卡", characters = listOf(source))

        val cleared = CharacterSectionImportPolicy.clearWorldBookImportedSections(card, setOf("a")).characters.single()

        assertEquals("侦探", cleared.profile)
        assertEquals("1girl", cleared.imagePrompt)
        assertEquals("/owned/alice.png", cleared.appearanceImage)
        assertEquals("", cleared.appearance)
        assertEquals("", cleared.clothing)
        assertNull(cleared.fishAudioVoice)
    }

    @Test
    fun `character import overwrites selected sections and creates independent character`() {
        val target = CharacterInfo(id = "target-a", name = "爱丽丝", profile = "旧简介", appearance = "旧外貌", clothing = "保留服装")
        val sourceA = CharacterInfo(id = "source-a", name = " 爱丽丝 ", profile = "新简介", appearance = "新外貌", clothing = "")
        val sourceB = CharacterInfo(id = "source-b", name = "鲍勃", abilities = "机械维修", appearanceImage = "/source/bob.png")

        val result = CharacterSectionImportPolicy.importIntoCharacterCard(
            currentCharacters = listOf(target),
            sourceCharacters = listOf(sourceA, sourceB),
            selections = listOf(
                CharacterSectionSelection("source-a", setOf(CharacterTextSection.PROFILE, CharacterTextSection.APPEARANCE, CharacterTextSection.CLOTHING)),
                CharacterSectionSelection("source-b", setOf(CharacterTextSection.ABILITIES))
            )
        )

        assertEquals(1, result.updatedCount)
        assertEquals(1, result.createdCount)
        assertEquals("target-a", result.characters[0].id)
        assertEquals("新简介", result.characters[0].profile)
        assertEquals("新外貌", result.characters[0].appearance)
        assertEquals("保留服装", result.characters[0].clothing)
        assertNotEquals("source-b", result.characters[1].id)
        assertEquals("机械维修", result.characters[1].abilities)
        assertNull(result.characters[1].appearanceImage)
    }
}
