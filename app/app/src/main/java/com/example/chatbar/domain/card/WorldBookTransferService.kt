package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.data.local.entity.WorldBookEntry
import com.example.chatbar.data.local.entity.WorldBookPosition
import com.example.chatbar.data.local.entity.WorldBookSelectiveLogic
import com.example.chatbar.data.repository.WorldBookRepository
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

class WorldBookTransferService(
    private val repository: WorldBookRepository?,
    private val json: Json
) {
    constructor(json: Json) : this(null, json)

    suspend fun exportJson(id: String): String {
        val book = repository?.getById(id) ?: error("世界书不存在")
        return json.encodeToString(WorldBookPackage.serializer(), WorldBookPackage(book = book))
    }

    suspend fun exportSillyTavernJson(id: String): String {
        val book = repository?.getById(id) ?: error("世界书不存在")
        return json.encodeToString(JsonObject.serializer(), toSillyTavernJson(book))
    }

    fun decode(rawJson: String, fallbackName: String = "导入世界书"): WorldBookPackage {
        val element = json.parseToJsonElement(rawJson)
        val obj = element.jsonObject
        if (obj["schemaVersion"]?.jsonPrimitive?.intOrNull != null && obj["book"] != null) {
            return json.decodeFromString(WorldBookPackage.serializer(), rawJson)
        }
        if (looksLikeUnsupportedLorebook(obj)) {
            error("暂不支持 NovelAI / Agnai / Risu 世界书转换，请先导出为 SillyTavern World Info JSON")
        }
        return WorldBookPackage(book = parseSillyTavernWorldBook(obj, fallbackName))
    }

    fun decodeCharacterBook(rawJson: String, fallbackName: String): WorldBook =
        parseCharacterBook(json.parseToJsonElement(rawJson).jsonObject, fallbackName)

    suspend fun duplicate(id: String): WorldBook {
        val repo = repository ?: error("世界书仓库不可用")
        val source = repo.getById(id) ?: error("世界书不存在")
        val copy = source.copy(
            id = UUID.randomUUID().toString(),
            name = NamePolicy.nextCopyName(source.name, repo.getAll().map { it.name }),
            entries = source.entries.map { it.copy(id = UUID.randomUUID().toString()) },
            sourcePresetKey = null,
            sourcePresetVersion = null,
            createdAt = System.currentTimeMillis(),
            updatedAt = System.currentTimeMillis()
        )
        repo.save(copy)
        return copy
    }

    suspend fun importNew(packageData: WorldBookPackage, requestedName: String = packageData.book.name): WorldBook {
        val repo = repository ?: error("世界书仓库不可用")
        val allNames = repo.getAll().map { it.name }
        val name = if (allNames.any { NamePolicy.isSame(it, requestedName) }) {
            NamePolicy.nextCopyName(requestedName, allNames)
        } else {
            NamePolicy.normalize(requestedName)
        }
        val now = System.currentTimeMillis()
        val book = packageData.book.copy(
            id = UUID.randomUUID().toString(),
            name = name.ifBlank { "导入世界书" },
            entries = packageData.book.entries.map { it.copy(id = UUID.randomUUID().toString()) },
            createdAt = now,
            updatedAt = now
        )
        repo.save(book)
        return book
    }

    suspend fun overwrite(existingId: String, packageData: WorldBookPackage): WorldBook {
        val repo = repository ?: error("世界书仓库不可用")
        val existing = repo.getById(existingId) ?: error("待覆盖世界书不存在")
        val updated = packageData.book.copy(
            id = existing.id,
            name = existing.name,
            createdAt = existing.createdAt,
            updatedAt = System.currentTimeMillis()
        )
        repo.save(updated)
        return updated
    }

    private fun parseSillyTavernWorldBook(obj: JsonObject, fallbackName: String): WorldBook {
        require(obj.containsKey("entries")) { "文件必须包含 SillyTavern 世界书 entries" }
        val now = System.currentTimeMillis()
        return WorldBook(
            id = UUID.randomUUID().toString(),
            name = obj.string("name").ifBlank { fallbackName },
            description = obj.string("description"),
            entries = parseEntries(obj["entries"], characterBookShape = false),
            scanDepth = obj.int("scanDepth") ?: obj.int("depth") ?: obj.int("scan_depth") ?: 10,
            tokenBudget = obj.int("tokenBudget") ?: obj.int("budget") ?: obj.int("token_budget"),
            recursiveScanning = obj.bool("recursiveScanning") ?: obj.bool("recursive_scanning") ?: false,
            caseSensitive = obj.bool("caseSensitive") ?: obj.bool("case_sensitive") ?: false,
            matchWholeWords = obj.bool("matchWholeWords") ?: obj.bool("match_whole_words") ?: false,
            sourcePresetKey = obj.stringOrNull("sourcePresetKey"),
            sourcePresetVersion = obj.int("sourcePresetVersion"),
            createdAt = now,
            updatedAt = now
        )
    }

    private fun parseCharacterBook(obj: JsonObject, fallbackName: String): WorldBook {
        val now = System.currentTimeMillis()
        return WorldBook(
            id = UUID.randomUUID().toString(),
            name = obj.string("name").ifBlank { fallbackName },
            description = obj.string("description"),
            entries = parseEntries(obj["entries"], characterBookShape = true),
            scanDepth = obj.int("scan_depth") ?: obj.int("scanDepth") ?: 10,
            tokenBudget = obj.int("token_budget") ?: obj.int("tokenBudget"),
            recursiveScanning = obj.bool("recursive_scanning") ?: obj.bool("recursiveScanning") ?: false,
            caseSensitive = obj.bool("case_sensitive") ?: obj.bool("caseSensitive") ?: false,
            matchWholeWords = obj.bool("match_whole_words") ?: obj.bool("matchWholeWords") ?: false,
            createdAt = now,
            updatedAt = now
        )
    }

    private fun parseEntries(entriesNode: JsonElement?, characterBookShape: Boolean): List<WorldBookEntry> {
        val objects: List<Pair<String?, JsonObject>> = when (entriesNode) {
            is JsonObject -> entriesNode.entries.mapNotNull { (uid, value) ->
                runCatching { uid to value.jsonObject }.getOrNull()
            }
            is JsonArray -> entriesNode.mapIndexedNotNull { index, value ->
                runCatching { index.toString() to value.jsonObject }.getOrNull()
            }
            else -> emptyList()
        }
        return objects.mapNotNull { (uid, entry) ->
            runCatching { parseEntry(uid, entry, characterBookShape) }.getOrNull()
        }
    }

    private fun parseEntry(uid: String?, e: JsonObject, characterBookShape: Boolean): WorldBookEntry {
        val ext = e["extensions"]?.takeIf { it !is JsonNull }?.jsonObject
        val originalPosition = e.anyPrimitive("position") ?: e.anyPrimitive("insertion_position")
        val keys = parseStringArray(e, if (characterBookShape) "keys" else "key")
            .ifEmpty { parseStringArray(e, "keys") }
        val secondary = parseStringArray(e, if (characterBookShape) "secondary_keys" else "keysecondary")
            .ifEmpty { parseStringArray(e, "secondaryKeys") }
            .ifEmpty { parseStringArray(e, "keysecondary") }
        val disabled = e.bool("disable") ?: false
        val comment = e.string("comment")
        val name = e.string("name").ifBlank { comment }
        return WorldBookEntry(
            id = UUID.randomUUID().toString(),
            name = name,
            keys = keys,
            content = e.string("content"),
            enabled = e.bool("enabled") ?: !disabled,
            insertionOrder = e.int("order") ?: e.int("insertion_order") ?: e.int("insertionOrder") ?: 100,
            priority = e.int("priority"),
            constant = e.bool("constant") ?: false,
            position = mapPosition(originalPosition),
            caseSensitive = e.bool("caseSensitive") ?: e.bool("case_sensitive") ?: false,
            matchWholeWords = e.bool("matchWholeWords") ?: e.bool("match_whole_words"),
            selective = e.bool("selective") ?: secondary.isNotEmpty(),
            secondaryKeys = secondary,
            selectiveLogic = e.int("selectiveLogic") ?: WorldBookSelectiveLogic.AND_ANY.value,
            comment = comment,
            scanDepth = e.int("scanDepth") ?: e.int("depth"),
            role = e.anyPrimitive("role"),
            ignoreBudget = e.bool("ignoreBudget") ?: false,
            excludeRecursion = e.bool("excludeRecursion") ?: false,
            preventRecursion = e.bool("preventRecursion") ?: false,
            delayUntilRecursion = e.bool("delayUntilRecursion") ?: false,
            recursionLevel = e.int("recursionLevel"),
            originalPosition = originalPosition,
            matchCharacterDescription = e.bool("matchCharacterDescription") ?: false,
            matchCharacterPersonality = e.bool("matchCharacterPersonality") ?: false,
            matchScenario = e.bool("matchScenario") ?: false,
            matchCreatorNotes = e.bool("matchCreatorNotes") ?: false,
            matchPersonaDescription = e.bool("matchPersonaDescription") ?: false,
            probability = e.int("probability") ?: ext?.int("probability") ?: 100,
            group = e.stringOrNull("group") ?: ext?.string("group") ?: "",
            groupWeight = e.int("groupWeight") ?: e.int("group_weight") ?: ext?.int("group_weight") ?: 100,
            sticky = e.int("sticky") ?: ext?.int("sticky") ?: 0,
            cooldown = e.int("cooldown") ?: ext?.int("cooldown") ?: 0,
            delay = e.int("delay") ?: ext?.int("delay") ?: 0,
            useRegex = e.bool("useRegex") ?: e.bool("use_regex") ?: keys.any(::isRegexKey),
            outletName = e.stringOrNull("outletName") ?: e.stringOrNull("outlet_name") ?: ext?.string("outlet_name") ?: "",
            characterFilter = e["characterFilter"]?.jsonObject?.let { parseStringArray(it, "names") } ?: emptyList(),
            characterFilterExclude = e["characterFilter"]?.jsonObject?.bool("isExclude") ?: false,
            extensions = buildJsonObject {
                uid?.let { put("uid", it) }
                put("rawJson", e.toString())
            }.toString()
        )
    }

    private fun toSillyTavernJson(book: WorldBook): JsonObject = buildJsonObject {
        put("name", book.name)
        put("description", book.description)
        put("scanDepth", book.scanDepth)
        book.tokenBudget?.let { put("tokenBudget", it) }
        put("recursiveScanning", book.recursiveScanning)
        put("caseSensitive", book.caseSensitive)
        put("matchWholeWords", book.matchWholeWords)
        putJsonObject("entries") {
            book.entries.forEachIndexed { index, entry ->
                putJsonObject(index.toString()) {
                    put("uid", index)
                    put("comment", entry.comment.ifBlank { entry.name })
                    putJsonArray("key") { entry.keys.forEach { add(it) } }
                    putJsonArray("keysecondary") { entry.secondaryKeys.forEach { add(it) } }
                    put("content", entry.content)
                    put("disable", !entry.enabled)
                    put("constant", entry.constant)
                    put("selective", entry.selective)
                    put("selectiveLogic", entry.selectiveLogic)
                    put("order", entry.insertionOrder)
                    put("position", entry.originalPosition ?: stPosition(entry.position))
                    put("caseSensitive", entry.caseSensitive)
                    entry.matchWholeWords?.let { put("matchWholeWords", it) }
                    entry.scanDepth?.let { put("scanDepth", it) }
                    entry.role?.let { put("role", it) }
                    put("ignoreBudget", entry.ignoreBudget)
                    put("excludeRecursion", entry.excludeRecursion)
                    put("preventRecursion", entry.preventRecursion)
                    put("delayUntilRecursion", entry.delayUntilRecursion)
                    entry.recursionLevel?.let { put("recursionLevel", it) }
                    put("probability", entry.probability)
                    put("group", entry.group)
                    put("groupWeight", entry.groupWeight)
                    put("sticky", entry.sticky)
                    put("cooldown", entry.cooldown)
                    put("delay", entry.delay)
                    put("useRegex", entry.useRegex)
                    put("outletName", entry.outletName)
                    putJsonObject("characterFilter") {
                        putJsonArray("names") { entry.characterFilter.forEach { add(it) } }
                        put("isExclude", entry.characterFilterExclude)
                    }
                }
            }
        }
    }

    private fun looksLikeUnsupportedLorebook(obj: JsonObject): Boolean =
        obj.containsKey("kind") || obj.containsKey("lorebookVersion") || obj.containsKey("items")

    private fun mapPosition(value: String?): WorldBookPosition = when (value?.lowercase()) {
        "0", "before_char", "before char defs", "before_char_defs" -> WorldBookPosition.BEFORE_CHAR
        "1", "after_char", "after char defs", "after_char_defs" -> WorldBookPosition.AFTER_CHAR
        "outlet" -> WorldBookPosition.OUTLET
        else -> WorldBookPosition.AFTER_CHAR
    }

    private fun stPosition(position: WorldBookPosition): String = when (position) {
        WorldBookPosition.BEFORE_CHAR -> "before_char"
        WorldBookPosition.AFTER_CHAR -> "after_char"
        WorldBookPosition.OUTLET -> "outlet"
    }

    private fun isRegexKey(key: String): Boolean =
        key.startsWith("/") && key.length > 2 && key.drop(1).contains("/")

    private fun JsonObject.string(key: String): String =
        this[key]?.jsonPrimitive?.content ?: ""

    private fun JsonObject.stringOrNull(key: String): String? =
        this[key]?.jsonPrimitive?.content?.takeIf { it.isNotBlank() }

    private fun JsonObject.int(key: String): Int? =
        this[key]?.jsonPrimitive?.intOrNull

    private fun JsonObject.bool(key: String): Boolean? =
        this[key]?.jsonPrimitive?.booleanOrNull

    private fun JsonObject.anyPrimitive(key: String): String? =
        this[key]?.jsonPrimitive?.content

    private fun parseStringArray(doc: JsonObject, key: String): List<String> {
        val element = doc[key] ?: return emptyList()
        return when (element) {
            is JsonArray -> element.mapNotNull { it.jsonPrimitive.content.takeIf(String::isNotBlank) }
            else -> element.jsonPrimitive.content
                .split(",")
                .map { it.trim() }
                .filter { it.isNotBlank() }
        }
    }
}
