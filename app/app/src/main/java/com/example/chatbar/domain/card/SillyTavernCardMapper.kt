package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.CharacterEditMode
import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.data.local.entity.WorldBookEntry
import com.example.chatbar.data.local.entity.WorldBookPosition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

object SillyTavernCardMapper {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun toCharacterCardPackage(st: SillyTavernCard): CharacterCardPackage {
        val freeformText = buildString {
            appendLine("【角色名称】")
            appendLine(st.name)
            appendLine()
            if (st.description.isNotBlank()) {
                appendLine("【人物描述】")
                appendLine(translatePlaceholders(st.description))
                appendLine()
            }
            if (st.personality.isNotBlank()) {
                appendLine("【性格特点】")
                appendLine(translatePlaceholders(st.personality))
                appendLine()
            }
            if (st.scenario.isNotBlank()) {
                appendLine("【背景场景】")
                appendLine(translatePlaceholders(st.scenario))
                appendLine()
            }
            if (st.mesExample.isNotBlank()) {
                appendLine("【对话示例】")
                appendLine(translatePlaceholders(st.mesExample))
                appendLine()
            }
        }.trim()

        val mesExample = if (st.mesExample.isNotBlank()) translatePlaceholders(st.mesExample) else ""
        val systemPrompt = st.systemPrompt.takeIf { it.isNotBlank() }?.let { translatePlaceholders(it) } ?: ""
        val postHistory = st.postHistoryInstructions.takeIf { it.isNotBlank() }?.let { translatePlaceholders(it) } ?: ""

        val card = PackagedCharacterCard(
            name = st.name,
            greeting = translatePlaceholders(st.firstMes),
            alternateGreetings = st.alternateGreetings.map { translatePlaceholders(it) },
            editMode = CharacterEditMode.FREEFORM,
            freeformCharacterText = freeformText,
            systemPrompt = systemPrompt,
            postHistoryInstructions = postHistory,
            mesExample = mesExample,
            creatorNotes = st.creatorNotes,
            tags = st.tags,
            creator = st.creator,
            characterVersion = st.characterVersion,
            extensions = st.extensions,
            characterBook = parseCharacterBook(st.characterBook)
        )

        return CharacterCardPackage(
            schemaVersion = 4,
            card = card,
            documents = emptyList(),
            images = emptyMap()
        )
    }

    private fun parseCharacterBook(raw: String?): WorldBook? {
        if (raw.isNullOrBlank()) return null
        return try {
            val doc = json.parseToJsonElement(raw).jsonObject
            val now = System.currentTimeMillis()
            val entries = doc["entries"]?.jsonObject?.let { entriesDoc ->
                // ST entries are stored as numbered keys like "0", "1", etc.
                entriesDoc.entries.mapNotNull { (_, v) ->
                    val e = v.jsonObject
                    WorldBookEntry(
                        id = UUID.randomUUID().toString(),
                        name = e.string("name"),
                        keys = parseStringArray(e, "keys"),
                        content = e.string("content"),
                        enabled = e["enabled"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: true,
                        insertionOrder = e["insertion_order"]?.jsonPrimitive?.content?.toIntOrNull() ?: 100,
                        priority = e["priority"]?.jsonPrimitive?.content?.toIntOrNull(),
                        constant = e["constant"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                        position = when (e.string("position")) {
                            "after_char" -> WorldBookPosition.AFTER_CHAR
                            else -> WorldBookPosition.BEFORE_CHAR
                        },
                        caseSensitive = e["case_sensitive"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                        selective = e["selective"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                        secondaryKeys = parseStringArray(e, "secondary_keys"),
                        useRegex = false,
                        outletName = e["extensions"]?.jsonObject?.string("outlet_name") ?: "",
                        extensions = e.toString()
                    )
                }
            } ?: emptyList()

            WorldBook(
                id = UUID.randomUUID().toString(),
                name = doc.string("name"),
                description = doc.string("description"),
                entries = entries,
                scanDepth = doc["scan_depth"]?.jsonPrimitive?.content?.toIntOrNull() ?: 10,
                tokenBudget = doc["token_budget"]?.jsonPrimitive?.content?.toIntOrNull(),
                recursiveScanning = doc["recursive_scanning"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                createdAt = now,
                updatedAt = now
            )
        } catch (_: Exception) { null }
    }

    private fun JsonObject.string(key: String): String =
        this[key]?.jsonPrimitive?.content ?: ""

    private fun parseStringArray(doc: JsonObject, key: String): List<String> =
        doc[key]?.toString()?.let { raw ->
            val trimmed = raw.trim()
            if (trimmed.startsWith("[") && trimmed.endsWith("]")) {
                val inner = trimmed.substring(1, trimmed.length - 1).trim()
                if (inner.isEmpty()) emptyList()
                else inner.split(",").map { it.trim().removeSurrounding("\"") }
            } else emptyList()
        } ?: emptyList()

    private fun translatePlaceholders(text: String): String =
        text.replace("{{char}}", "\$botname")
            .replace("{{user}}", "\$username")
            .replace("<BOT>", "\$botname")
            .replace("<USER>", "\$username")
}
