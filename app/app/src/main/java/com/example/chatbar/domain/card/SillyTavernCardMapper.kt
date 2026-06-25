package com.example.chatbar.domain.card

import android.util.Base64
import android.util.Log
import com.example.chatbar.data.local.entity.CharacterEditMode
import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.data.local.entity.WorldBookEntry
import com.example.chatbar.data.local.entity.WorldBookPosition
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.util.UUID

object SillyTavernCardMapper {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val ST_DATA_BLOCK = Regex("""<(?:UpdateVariable|initvar)\b[^>]*>[\s\S]*?</(?:UpdateVariable|initvar)>""", RegexOption.IGNORE_CASE)
    private val ST_SELF_CLOSE = Regex("""<StatusPlaceHolderImpl\s*/>""", RegexOption.IGNORE_CASE)
    private val ST_WRAPPER_TAG = Regex("""</?(?i)(?:scene|content|场景|开场白|开场|内容)[^>]*>""")
    private val ST_HTML_FONT = Regex("""<font\b[^>]*>|</font>""", RegexOption.IGNORE_CASE)

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

        val cleanedGreeting = cleanSTTags(translatePlaceholders(st.firstMes))
        val cleanedAlternates = st.alternateGreetings.map { cleanSTTags(translatePlaceholders(it)) }
            .filter { it.isNotBlank() }

        val (finalGreeting, finalAlternates) = if (cleanedGreeting.isBlank() && cleanedAlternates.isNotEmpty()) {
            cleanedAlternates.first() to cleanedAlternates.drop(1)
        } else {
            cleanedGreeting to cleanedAlternates
        }

        val card = PackagedCharacterCard(
            name = st.name,
            greeting = finalGreeting,
            alternateGreetings = finalAlternates,
            avatarResourceId = if (st.pngBytes != null) "card-avatar" else null,
            chatBackgroundResourceId = if (st.pngBytes != null) "card-avatar" else null,
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
            images = if (st.pngBytes != null) {
                mapOf("card-avatar" to PackagedImage(
                    fileName = "card.png",
                    data = Base64.encodeToString(st.pngBytes, Base64.NO_WRAP)
                ))
            } else emptyMap()
        )
    }

    private fun parseCharacterBook(raw: String?): WorldBook? {
        if (raw.isNullOrBlank()) return null
        return try {
            val doc = json.parseToJsonElement(raw).jsonObject
            val now = System.currentTimeMillis()
            val entries = parseWorldBookEntries(doc)
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
        } catch (e: Exception) {
            Log.e("ChatBar", "解析角色卡内嵌世界书失败: ${e.message}", e)
            null
        }
    }

    private fun parseWorldBookEntries(doc: JsonObject): List<WorldBookEntry> {
        val entriesNode = doc["entries"] ?: return emptyList()
        val entryObjects: List<JsonObject> = when {
            entriesNode is JsonObject -> entriesNode.entries.map { it.value.jsonObject }
            entriesNode is JsonArray -> entriesNode.map { it.jsonObject }
            else -> return emptyList()
        }

        return entryObjects.mapNotNull { e ->
            try {
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
                        "outlet" -> WorldBookPosition.OUTLET
                        else -> WorldBookPosition.BEFORE_CHAR
                    },
                    caseSensitive = e["case_sensitive"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                    selective = e["selective"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                    secondaryKeys = parseStringArray(e, "secondary_keys"),
                    useRegex = e["use_regex"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false,
                    probability = e["probability"]?.jsonPrimitive?.content?.toIntOrNull()
                        ?: e["extensions"]?.jsonObject?.let { ext ->
                            ext["probability"]?.jsonPrimitive?.content?.toIntOrNull()
                        } ?: 100,
                    sticky = e["sticky"]?.jsonPrimitive?.content?.toIntOrNull()
                        ?: e["extensions"]?.jsonObject?.let { ext ->
                            ext["sticky"]?.jsonPrimitive?.content?.toIntOrNull()
                        } ?: 0,
                    cooldown = e["cooldown"]?.jsonPrimitive?.content?.toIntOrNull()
                        ?: e["extensions"]?.jsonObject?.let { ext ->
                            ext["cooldown"]?.jsonPrimitive?.content?.toIntOrNull()
                        } ?: 0,
                    delay = e["delay"]?.jsonPrimitive?.content?.toIntOrNull()
                        ?: e["extensions"]?.jsonObject?.let { ext ->
                            ext["delay"]?.jsonPrimitive?.content?.toIntOrNull()
                        } ?: 0,
                    group = e["group"]?.jsonPrimitive?.content
                        ?: e["extensions"]?.jsonObject?.let { ext ->
                            ext["group"]?.jsonPrimitive?.content
                        } ?: "",
                    groupWeight = e["group_weight"]?.jsonPrimitive?.content?.toIntOrNull()
                        ?: e["extensions"]?.jsonObject?.let { ext ->
                            ext["group_weight"]?.jsonPrimitive?.content?.toIntOrNull()
                        } ?: 100,
                    outletName = e["outlet_name"]?.jsonPrimitive?.content
                        ?: e["extensions"]?.jsonObject?.let { ext ->
                            ext["outlet_name"]?.jsonPrimitive?.content
                        } ?: "",
                    characterFilter = e["characterFilter"]?.jsonObject?.let { cf ->
                        parseStringArray(cf, "names")
                    } ?: emptyList(),
                    characterFilterExclude = e["characterFilter"]?.jsonObject?.let { cf ->
                        cf["isExclude"]?.jsonPrimitive?.content?.toBooleanStrictOrNull() ?: false
                    } ?: false,
                    extensions = e.toString()
                )
            } catch (ex: Exception) {
                Log.e("ChatBar", "解析世界书条目失败: ${ex.message}", ex)
                null
            }
        }
    }

    private fun JsonObject.string(key: String): String =
        this[key]?.jsonPrimitive?.content ?: ""

    private fun parseStringArray(doc: JsonObject, key: String): List<String> {
        val element = doc[key] ?: return emptyList()
        return try {
            when (element) {
                is JsonArray -> element.mapNotNull { it.jsonPrimitive?.content }
                else -> {
                    val raw = element.toString().trim()
                    if (raw.startsWith("[") && raw.endsWith("]")) {
                        val inner = raw.substring(1, raw.length - 1).trim()
                        if (inner.isEmpty()) emptyList()
                        else inner.split(",").map { it.trim().removeSurrounding("\"") }
                    } else emptyList()
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }

    private fun translatePlaceholders(text: String): String =
        text.replace("{{char}}", "\$botname")
            .replace("{{user}}", "\$username")
            .replace("<BOT>", "\$botname")
            .replace("<USER>", "\$username")

    private fun cleanSTTags(text: String): String =
        text.replace(ST_DATA_BLOCK, "")
            .replace(ST_SELF_CLOSE, "")
            .replace(ST_WRAPPER_TAG, "")
            .replace(ST_HTML_FONT, "")
            .replace(Regex("""\n{3,}"""), "\n\n")
            .trim()
}
