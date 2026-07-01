package com.example.chatbar.data.local.entity

import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
data class WorldBook(
    val id: String,
    val name: String,
    val description: String = "",
    val entries: List<WorldBookEntry> = emptyList(),
    val scanDepth: Int = 10,
    val tokenBudget: Int? = null,
    val recursiveScanning: Boolean = false,
    val caseSensitive: Boolean = false,
    val matchWholeWords: Boolean = false,
    val sourcePresetKey: String? = null,
    val sourcePresetVersion: Int? = null,
    val createdAt: Long = 0L,
    val updatedAt: Long = createdAt
) {
    companion object {
        @OptIn(ExperimentalUuidApi::class)
        fun create(name: String, description: String = ""): WorldBook {
            val now = System.currentTimeMillis()
            return WorldBook(
                id = Uuid.random().toString(),
                name = name,
                description = description,
                createdAt = now,
                updatedAt = now
            )
        }
    }
}

@Serializable
data class WorldBookEntry(
    val id: String,
    val name: String = "",
    val keys: List<String> = emptyList(),
    val content: String = "",
    val enabled: Boolean = true,
    val insertionOrder: Int = 100,
    val priority: Int? = null,
    val constant: Boolean = false,
    val position: WorldBookPosition = WorldBookPosition.BEFORE_CHAR,
    val caseSensitive: Boolean = false,
    val matchWholeWords: Boolean? = null,
    val selective: Boolean = false,
    val secondaryKeys: List<String> = emptyList(),
    val selectiveLogic: Int = WorldBookSelectiveLogic.AND_ANY.value,
    val comment: String = "",
    val scanDepth: Int? = null,
    val role: String? = null,
    val ignoreBudget: Boolean = false,
    val excludeRecursion: Boolean = false,
    val preventRecursion: Boolean = false,
    val delayUntilRecursion: Boolean = false,
    val recursionLevel: Int? = null,
    val originalPosition: String? = null,
    val matchCharacterDescription: Boolean = false,
    val matchCharacterPersonality: Boolean = false,
    val matchScenario: Boolean = false,
    val matchCreatorNotes: Boolean = false,
    val matchPersonaDescription: Boolean = false,
    // V2 extensions
    val probability: Int = 100,
    val group: String = "",
    val groupWeight: Int = 100,
    val sticky: Int = 0,
    val cooldown: Int = 0,
    val delay: Int = 0,
    // V3 extensions
    val useRegex: Boolean = false,
    val outletName: String = "",
    val characterFilter: List<String> = emptyList(),
    val characterFilterExclude: Boolean = false,
    val extensions: String = ""
) {
    companion object {
        @OptIn(ExperimentalUuidApi::class)
        fun create(keys: List<String> = emptyList(), content: String = ""): WorldBookEntry =
            WorldBookEntry(id = Uuid.random().toString(), keys = keys, content = content)
    }
}

@Serializable
enum class WorldBookPosition {
    BEFORE_CHAR,
    AFTER_CHAR,
    OUTLET
}

@Serializable
enum class WorldBookSelectiveLogic(val value: Int) {
    AND_ANY(0),
    NOT_ALL(1),
    NOT_ANY(2),
    AND_ALL(3)
}
