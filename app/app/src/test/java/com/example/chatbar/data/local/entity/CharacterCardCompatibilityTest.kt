package com.example.chatbar.data.local.entity

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
    fun legacyJsonDefaultsPendingSpeakerRenamesToEmpty() {
        val card = json.decodeFromString(
            CharacterCard.serializer(),
            """{"id":"card","name":"旧卡","createdAt":1,"updatedAt":2}"""
        )

        assertTrue(card.pendingSpeakerRenameTasks.isEmpty())
        assertTrue(
            !json.encodeToString(CharacterCard.serializer(), card)
                .contains("pendingSpeakerRenameTasks")
        )
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
