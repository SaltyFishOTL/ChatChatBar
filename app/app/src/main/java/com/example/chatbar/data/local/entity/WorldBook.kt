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
    val createdAt: Long,
    val updatedAt: Long
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
    val selective: Boolean = false,
    val secondaryKeys: List<String> = emptyList(),
    val comment: String = "",
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
