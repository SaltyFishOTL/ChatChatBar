package com.example.chatbar.data.local.entity

import com.example.chatbar.domain.image.NovelAiImageModel
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CharacterCardCompatibilityTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    @Test
    fun legacyJsonDefaultsNewFieldsAndUsesCardNameAsBotName() {
        val card = json.decodeFromString(
            CharacterCard.serializer(),
            """{"id":"card","name":"旧卡","createdAt":1,"updatedAt":2}"""
        )

        assertEquals("", card.botName)
        assertEquals("旧卡", card.effectiveBotName)
        assertEquals(null, card.defaultNovelAiImageModel)
        assertTrue(card.pendingSpeakerRenameTasks.isEmpty())
        assertTrue(
            !json.encodeToString(CharacterCard.serializer(), card)
                .contains("pendingSpeakerRenameTasks")
        )
    }

    @Test
    fun defaultNovelAiImageModelRoundTrips() {
        val card = CharacterCard(
            id = "card",
            name = "测试卡",
            defaultNovelAiImageModel = NovelAiImageModel.V5_FULL,
            createdAt = 1,
            updatedAt = 2
        )

        val restored = json.decodeFromString(
            CharacterCard.serializer(),
            json.encodeToString(CharacterCard.serializer(), card)
        )

        assertEquals(NovelAiImageModel.V5_FULL, restored.defaultNovelAiImageModel)
    }

    @Test
    fun localJsonPersistsMultilineBotName() {
        val card = CharacterCard(
            id = "card",
            name = "显示名称",
            botName = "第一行\n第二行",
            createdAt = 1,
            updatedAt = 2
        )

        val restored = json.decodeFromString(
            CharacterCard.serializer(),
            json.encodeToString(CharacterCard.serializer(), card)
        )

        assertEquals("第一行\n第二行", restored.botName)
        assertEquals("第一行\n第二行", restored.effectiveBotName)
        assertEquals("显示名称", card.copy(botName = " \n ").effectiveBotName)
    }

    @Test
    fun localJsonPersistsPendingSpeakerRenameTask() {
        val card = CharacterCard(
            id = "card",
            name = "测试卡",
            pendingSpeakerRenameTasks = listOf(
                SpeakerTagRenameTask(
                    id = "task",
                    characterCardId = "card",
                    expectedCardUpdatedAt = 2,
                    renames = listOf(SpeakerTagRename("person", "旧名", "新名")),
                    createdAt = 3,
                    lastError = "待重试"
                )
            ),
            createdAt = 1,
            updatedAt = 2
        )

        val restored = json.decodeFromString(
            CharacterCard.serializer(),
            json.encodeToString(CharacterCard.serializer(), card)
        )

        assertTrue(json.encodeToString(CharacterCard.serializer(), card).contains("pendingSpeakerRenameTasks"))
        assertEquals(card.pendingSpeakerRenameTasks, restored.pendingSpeakerRenameTasks)
    }
}
