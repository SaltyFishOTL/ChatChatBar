package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterEditMode
import com.example.chatbar.data.local.entity.CharacterInfo
import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.chat.StreamingChatService
import com.example.chatbar.domain.model.EffectiveModelResolver
import com.example.chatbar.domain.prompt.PromptTemplates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

@Serializable
data class CharacterAutoFillDraft(
    val name: String = "",
    val greeting: String = "",
    val basicSetting: String = "",
    val defaultImagePrompt: String = "",
    val characters: List<CharacterAutoFillCharacterDraft> = emptyList()
)

@Serializable
data class CharacterAutoFillCharacterDraft(
    val targetIndex: Int? = null,
    val name: String = "",
    val profile: String = "",
    val appearance: String = "",
    val clothing: String = "",
    val abilities: String = "",
    val habits: String = "",
    val background: String = "",
    val relationships: String = "",
    val speakingStyle: String = "",
    val imagePrompt: String = ""
)

class CharacterAutoFillService(
    private val modelResolver: EffectiveModelResolver,
    private val chatService: StreamingChatService,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
    }
) {
    private val promptJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
    }

    suspend fun generate(
        userInput: String,
        currentCard: CharacterCard
    ): CharacterAutoFillDraft = withContext(Dispatchers.IO) {
        require(currentCard.editMode == CharacterEditMode.STRUCTURED) { "AI 自动填充仅支持分段模式" }
        require(userInput.isNotBlank()) { "请输入角色信息或想玩的角色" }
        val model = modelResolver.defaultChatModel()
            ?: error("未配置可用的默认对话模型")
        require(model.apiKey.isNotBlank()) { "默认对话模型 API Key 为空" }

        val raw = chatService.completeText(
            messages = listOf(
                ChatApiMessage.text("system", PromptTemplates.CHARACTER_AUTO_FILL_SYSTEM_PROMPT),
                ChatApiMessage.text("user", buildUserPrompt(userInput, currentCard))
            ),
            modelConfig = model,
            maxTokens = 6000,
            thinkingBudget = 512
        )
        val draft = parseGeneratedDraft(raw) ?: repairDraft(raw, model)
        draft.constrainedToTargets(currentCard)
    }

    private suspend fun repairDraft(
        raw: String,
        model: com.example.chatbar.data.local.entity.ModelConfig
    ): CharacterAutoFillDraft {
        val repaired = chatService.completeText(
            messages = listOf(
                ChatApiMessage.text("system", PromptTemplates.CHARACTER_AUTO_FILL_REPAIR_PROMPT),
                ChatApiMessage.text("user", raw)
            ),
            modelConfig = model,
            maxTokens = 6000,
            thinkingBudget = 256
        )
        return parseGeneratedDraft(repaired)
            ?: error("AI 自动填充结果不是可解析 JSON：${raw.take(500)}")
    }

    private fun parseGeneratedDraft(raw: String): CharacterAutoFillDraft? =
        parseDraft(raw, json)

    companion object {
        private val defaultJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            encodeDefaults = true
        }
        private val defaultPromptJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = false
        }

        fun parseDraft(raw: String, json: Json = defaultJson): CharacterAutoFillDraft? {
        val candidates = buildList {
            add(raw.trim())
            add(raw.removeMarkdownFence().trim())
            raw.extractBalancedJsonObject()?.let { add(it) }
            raw.removeMarkdownFence().extractBalancedJsonObject()?.let { add(it) }
        }.distinct().filter { it.isNotBlank() }

        return candidates.firstNotNullOfOrNull { candidate ->
            runCatching {
                json.decodeFromString(CharacterAutoFillDraft.serializer(), candidate)
                    .normalized()
                    .takeIf(CharacterAutoFillDraft::hasAnyContent)
            }.getOrNull()
        }
        }

        fun mergeInto(
            current: CharacterCard,
            draft: CharacterAutoFillDraft,
            idFactory: () -> String = { UUID.randomUUID().toString() }
        ): CharacterCard {
            require(current.editMode == CharacterEditMode.STRUCTURED) { "AI 自动填充仅支持分段模式" }
            val normalizedDraft = draft.normalized()
            return current.copy(
                name = current.name.fillBlank(normalizedDraft.name),
                greeting = current.greeting.fillBlank(normalizedDraft.greeting),
                basicSetting = current.basicSetting.fillBlank(normalizedDraft.basicSetting),
                defaultImagePrompt = current.defaultImagePrompt.fillBlank(
                    normalizedDraft.defaultImagePrompt.ifBlank {
                        PromptTemplates.DEFAULT_CHARACTER_NAI_STYLE_PROMPT
                    }
                ),
                characters = mergeCharacters(current.characters, normalizedDraft.characters, idFactory)
            )
        }

        fun mergeCharacters(
            current: List<CharacterInfo>,
            draft: List<CharacterAutoFillCharacterDraft>,
            idFactory: () -> String = { UUID.randomUUID().toString() }
        ): List<CharacterInfo> {
            val draftCharacters = draft
                .map(CharacterAutoFillCharacterDraft::normalized)
                .filter(CharacterAutoFillCharacterDraft::hasAnyContent)
            if (draftCharacters.isEmpty()) return current
            if (current.isDefaultEmptyCharacterList()) {
                return listOf(draftCharacters.first().toCharacterInfo(idFactory))
            }
            val usedDraftIndexes = mutableSetOf<Int>()
            return current.mapIndexed { index, existing ->
                val selected = draftCharacters.selectForSlot(index, existing, usedDraftIndexes)
                if (selected == null) existing else existing.mergeBlank(selected)
            }
        }

        fun buildPromptPayload(
            userInput: String,
            currentCard: CharacterCard,
            promptJson: Json = defaultPromptJson
        ): String =
            buildJsonObject {
                put("request", userInput.trim())
                put("fillTargets", currentCard.buildFillTargets())
                put("lockedContext", promptJson.parseToJsonElement(promptJson.encodeToString(currentCard.toLockedDraft())))
                put("defaultNaiStyle", PromptTemplates.DEFAULT_CHARACTER_NAI_STYLE_PROMPT.trim())
                put("characterImageGuide", PromptTemplates.CHARACTER_IMAGE_NAI_PROMPT_GUIDE.trim())
            }.toString()
    }

    fun mergeInto(
        current: CharacterCard,
        draft: CharacterAutoFillDraft,
        idFactory: () -> String = { UUID.randomUUID().toString() }
    ): CharacterCard = CharacterAutoFillService.mergeInto(current, draft, idFactory)

    fun buildUserPrompt(userInput: String, currentCard: CharacterCard): String =
        CharacterAutoFillService.buildPromptPayload(userInput, currentCard, promptJson)
}

private fun CharacterCard.toLockedDraft(): CharacterAutoFillDraft =
    CharacterAutoFillDraft(
        name = name,
        greeting = greeting,
        basicSetting = basicSetting,
        defaultImagePrompt = defaultImagePrompt,
        characters = if (characters.isDefaultEmptyCharacterList()) {
            emptyList()
        } else {
            characters.map {
            CharacterAutoFillCharacterDraft(
                name = it.name,
                profile = it.profile,
                appearance = it.appearance,
                clothing = it.clothing,
                abilities = it.abilities,
                habits = it.habits,
                background = it.background,
                relationships = it.relationships,
                speakingStyle = it.speakingStyle,
                imagePrompt = it.imagePrompt
            )
            }
        }
    )

private fun CharacterCard.buildFillTargets(): JsonObject = buildJsonObject {
        val cardTargets = buildJsonArray {
            if (name.isBlank()) add(JsonPrimitive("name"))
            if (greeting.isBlank()) add(JsonPrimitive("greeting"))
            if (basicSetting.isBlank()) add(JsonPrimitive("basicSetting"))
            if (defaultImagePrompt.isBlank()) add(JsonPrimitive("defaultImagePrompt"))
        }
        put("card", cardTargets)
        val characterTargets = buildJsonArray {
            characters.forEachIndexed { index, character ->
                val fields = character.blankFieldNames()
                if (fields.isNotEmpty()) {
                    add(buildJsonObject {
                        put("mode", "fillCharacterSlot")
                        put("index", index)
                        if (character.name.isNotBlank()) {
                            put("matchName", character.name.trim())
                        }
                        put("fields", JsonArray(fields.map(::JsonPrimitive)))
                    })
                }
            }
        }
        put("characters", characterTargets)
    }

private fun CharacterInfo.blankFieldNames(): List<String> = buildList {
    if (name.isBlank()) add("name")
    if (profile.isBlank()) add("profile")
    if (appearance.isBlank()) add("appearance")
    if (clothing.isBlank()) add("clothing")
    if (abilities.isBlank()) add("abilities")
    if (habits.isBlank()) add("habits")
    if (background.isBlank()) add("background")
    if (relationships.isBlank()) add("relationships")
    if (speakingStyle.isBlank()) add("speakingStyle")
    if (imagePrompt.isBlank()) add("imagePrompt")
}

private fun CharacterInfo.mergeBlank(draft: CharacterAutoFillCharacterDraft): CharacterInfo =
    copy(
        name = name.fillBlank(draft.name),
        profile = profile.fillBlank(draft.profile),
        appearance = appearance.fillBlank(draft.appearance),
        clothing = clothing.fillBlank(draft.clothing),
        abilities = abilities.fillBlank(draft.abilities),
        habits = habits.fillBlank(draft.habits),
        background = background.fillBlank(draft.background),
        relationships = relationships.fillBlank(draft.relationships),
        speakingStyle = speakingStyle.fillBlank(draft.speakingStyle),
        imagePrompt = imagePrompt.fillBlank(draft.imagePrompt)
    )

private fun CharacterAutoFillCharacterDraft.toCharacterInfo(idFactory: () -> String): CharacterInfo =
    CharacterInfo(
        id = idFactory(),
        name = name,
        profile = profile,
        appearance = appearance,
        clothing = clothing,
        abilities = abilities,
        habits = habits,
        background = background,
        relationships = relationships,
        speakingStyle = speakingStyle,
        imagePrompt = imagePrompt
    )

private fun List<CharacterAutoFillCharacterDraft>.selectForSlot(
    slotIndex: Int,
    existing: CharacterInfo,
    usedDraftIndexes: MutableSet<Int>
): CharacterAutoFillCharacterDraft? {
    val existingName = existing.name.trim()

    fun firstUnused(
        predicate: (CharacterAutoFillCharacterDraft) -> Boolean
    ): CharacterAutoFillCharacterDraft? {
        for (draftIndex in indices) {
            if (draftIndex !in usedDraftIndexes && predicate(this[draftIndex])) {
                usedDraftIndexes += draftIndex
                return this[draftIndex]
            }
        }
        return null
    }

    fun nameCompatible(draft: CharacterAutoFillCharacterDraft): Boolean =
        existingName.isBlank() ||
            draft.name.isBlank() ||
            draft.name.equals(existingName, ignoreCase = true)

    firstUnused { it.targetIndex == slotIndex && nameCompatible(it) }?.let { return it }
    if (existingName.isNotBlank()) {
        return firstUnused { it.name.equals(existingName, ignoreCase = true) }
    }
    return firstUnused { it.targetIndex == null }
}

private fun CharacterAutoFillDraft.constrainedToTargets(currentCard: CharacterCard): CharacterAutoFillDraft {
    val normalizedDraft = normalized()
    val draftCharacters = normalizedDraft.characters.filter(CharacterAutoFillCharacterDraft::hasAnyContent)
    if (draftCharacters.isEmpty()) return normalizedDraft
    val constrainedCharacters = if (currentCard.characters.isDefaultEmptyCharacterList()) {
        draftCharacters.take(1).map { it.copy(targetIndex = it.targetIndex ?: 0) }
    } else {
        val usedDraftIndexes = mutableSetOf<Int>()
        currentCard.characters.mapIndexedNotNull { index, existing ->
            if (existing.blankFieldNames().isEmpty()) {
                null
            } else {
                draftCharacters.selectForSlot(index, existing, usedDraftIndexes)
                    ?.let { it.copy(targetIndex = it.targetIndex ?: index) }
            }
        }
    }
    return normalizedDraft.copy(characters = constrainedCharacters)
}

private fun List<CharacterInfo>.isDefaultEmptyCharacterList(): Boolean =
    size == 1 && single().let {
        it.name.isBlank() &&
            it.profile.isBlank() &&
            it.appearance.isBlank() &&
            it.clothing.isBlank() &&
            it.abilities.isBlank() &&
            it.habits.isBlank() &&
            it.background.isBlank() &&
            it.relationships.isBlank() &&
            it.speakingStyle.isBlank() &&
            it.imagePrompt.isBlank() &&
            it.appearanceImage.isNullOrBlank()
    }

private fun CharacterAutoFillDraft.normalized(): CharacterAutoFillDraft =
    copy(
        name = name.trim(),
        greeting = greeting.trim(),
        basicSetting = basicSetting.trim(),
        defaultImagePrompt = defaultImagePrompt.trim(),
        characters = characters.map(CharacterAutoFillCharacterDraft::normalized)
    )

private fun CharacterAutoFillDraft.hasAnyContent(): Boolean =
    name.isNotBlank() ||
        greeting.isNotBlank() ||
        basicSetting.isNotBlank() ||
        defaultImagePrompt.isNotBlank() ||
        characters.any(CharacterAutoFillCharacterDraft::hasAnyContent)

private fun CharacterAutoFillCharacterDraft.normalized(): CharacterAutoFillCharacterDraft =
    copy(
        name = name.trim(),
        profile = profile.trim(),
        appearance = appearance.trim(),
        clothing = clothing.trim(),
        abilities = abilities.trim(),
        habits = habits.trim(),
        background = background.trim(),
        relationships = relationships.trim(),
        speakingStyle = speakingStyle.trim(),
        imagePrompt = imagePrompt.trim()
    )

private fun CharacterAutoFillCharacterDraft.hasAnyContent(): Boolean =
    name.isNotBlank() ||
        profile.isNotBlank() ||
        appearance.isNotBlank() ||
        clothing.isNotBlank() ||
        abilities.isNotBlank() ||
        habits.isNotBlank() ||
        background.isNotBlank() ||
        relationships.isNotBlank() ||
        speakingStyle.isNotBlank() ||
        imagePrompt.isNotBlank()

private fun String.fillBlank(candidate: String): String =
    if (isBlank()) candidate.trim() else this

private fun String.removeMarkdownFence(): String =
    trim()
        .removePrefix("```json")
        .removePrefix("```JSON")
        .removePrefix("```")
        .removeSuffix("```")
        .trim()

private fun String.extractBalancedJsonObject(): String? {
    val start = indexOf('{')
    if (start < 0) return null
    var depth = 0
    var inString = false
    var escaped = false
    for (i in start until length) {
        val ch = this[i]
        when {
            escaped -> escaped = false
            ch == '\\' && inString -> escaped = true
            ch == '"' -> inString = !inString
            !inString && ch == '{' -> depth++
            !inString && ch == '}' -> {
                depth--
                if (depth == 0) return substring(start, i + 1)
            }
        }
    }
    return null
}
