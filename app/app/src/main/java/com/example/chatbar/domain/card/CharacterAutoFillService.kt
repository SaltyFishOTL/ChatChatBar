package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterEditMode
import com.example.chatbar.data.local.entity.CharacterInfo
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.chat.ImageUnderstandingResult
import com.example.chatbar.domain.chat.ImageUnderstandingService
import com.example.chatbar.domain.chat.StreamEvent
import com.example.chatbar.domain.chat.StreamingChatService
import com.example.chatbar.domain.model.EffectiveModelResolver
import com.example.chatbar.domain.prompt.PromptTemplates
import com.example.chatbar.domain.search.CharacterResearchService
import com.example.chatbar.domain.search.ResearchBrief
import com.example.chatbar.domain.search.ResearchDebugSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
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

data class CharacterAutoFillImageContext(
    val hasSourceImages: Boolean = false,
    val descriptions: List<String> = emptyList()
) {
    fun hasContent(): Boolean = hasSourceImages || descriptions.any(String::isNotBlank)
}

class CharacterAutoFillService(
    private val modelResolver: EffectiveModelResolver,
    private val chatService: StreamingChatService,
    private val researchService: CharacterResearchService? = null,
    private val imageUnderstandingService: ImageUnderstandingService? = null,
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
        currentCard: CharacterCard,
        modelOverride: ModelConfig? = null,
        imageBase64s: List<String> = emptyList()
    ): CharacterAutoFillDraft = withContext(Dispatchers.IO) {
        require(currentCard.editMode == CharacterEditMode.STRUCTURED) { "AI 自动填充仅支持分段模式" }
        require(userInput.isNotBlank() || imageBase64s.any(String::isNotBlank)) { "请输入角色信息或上传图片" }
        val model = resolveModel(modelOverride)
        val imageContext = prepareImageContext(imageBase64s, model)
        val researchBrief = if (userInput.isNotBlank()) buildResearchBrief(userInput, currentCard, model) else null
        val userPrompt = buildUserPrompt(userInput, currentCard, researchBrief, imageContext.promptContext)

        val raw = chatService.completeText(
            messages = listOf(
                ChatApiMessage.text("system", PromptTemplates.CHARACTER_AUTO_FILL_SYSTEM_PROMPT),
                userPrompt.toChatApiMessage(imageContext.directImageBase64s)
            ),
            modelConfig = model,
            maxTokens = 6000,
            thinkingBudget = 512
        )
        val draft = parseGeneratedDraft(raw) ?: repairDraft(raw, model)
        draft.constrainedToTargets(currentCard)
    }

    suspend fun generateStreaming(
        userInput: String,
        currentCard: CharacterCard,
        modelOverride: ModelConfig? = null,
        imageBase64s: List<String> = emptyList(),
        onStatus: (String) -> Unit = {},
        onResearchDebug: (ResearchDebugSnapshot) -> Unit = {},
        onVisibleOutput: (String, String, String) -> Unit = { _, _, _ -> },
        onRawText: (String) -> Unit
    ): CharacterAutoFillDraft = withContext(Dispatchers.IO) {
        require(currentCard.editMode == CharacterEditMode.STRUCTURED) { "AI 自动填充仅支持分段模式" }
        require(userInput.isNotBlank() || imageBase64s.any(String::isNotBlank)) { "请输入角色信息或上传图片" }
        val model = resolveModel(modelOverride)
        val imageContext = prepareImageContext(imageBase64s, model, onStatus, onVisibleOutput)
        val researchBrief = if (userInput.isNotBlank()) {
            buildResearchBrief(userInput, currentCard, model, onStatus, onResearchDebug, onVisibleOutput)
        } else {
            null
        }
        onStatus("生成角色卡")

        val userPrompt = buildUserPrompt(userInput, currentCard, researchBrief, imageContext.promptContext)
        val messages = listOf(
            ChatApiMessage.text("system", PromptTemplates.CHARACTER_AUTO_FILL_SYSTEM_PROMPT),
            userPrompt.toChatApiMessage(imageContext.directImageBase64s)
        )
        val raw = StringBuilder()
        var streamError: String? = null
        chatService.streamText(
            messages = messages,
            modelConfig = model,
            maxTokens = 6000,
            thinkingBudget = 512
        ).collect { event ->
            when (event) {
                is StreamEvent.Delta -> {
                    raw.append(event.text)
                    onRawText(raw.toString())
                }
                is StreamEvent.Error -> streamError = event.message
                StreamEvent.Done,
                is StreamEvent.ReasoningDelta -> Unit
            }
        }

        val rawText = raw.toString()
        if (rawText.isBlank()) {
            error(streamError ?: "AI 自动填充返回空内容")
        }
        val draft = parseGeneratedDraft(rawText) ?: repairDraft(rawText, model)
        draft.constrainedToTargets(currentCard)
    }

    private suspend fun repairDraft(
        raw: String,
        model: ModelConfig
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

    private suspend fun resolveModel(modelOverride: ModelConfig?): ModelConfig {
        val model = modelOverride ?: modelResolver.defaultChatModel()
            ?: error("未配置可用的默认对话模型")
        require(model.apiKey.isNotBlank()) { "${model.displayName.ifBlank { model.modelName }} API Key 为空" }
        return model
    }

    private suspend fun buildResearchBrief(
        userInput: String,
        currentCard: CharacterCard,
        generationModel: ModelConfig,
        onStatus: (String) -> Unit = {},
        onResearchDebug: (ResearchDebugSnapshot) -> Unit = {},
        onVisibleOutput: (String, String, String) -> Unit = { _, _, _ -> }
    ): ResearchBrief? {
        val service = researchService ?: return null
        val researchModel = runCatching { modelResolver.retrievalModel() }
            .getOrNull()
            ?.takeIf { it.apiKey.isNotBlank() }
            ?: generationModel
        return runCatching {
            service.research(
                userInput = userInput,
                currentCard = currentCard,
                modelConfig = researchModel,
                onDebug = onResearchDebug,
                onStatus = onStatus,
                onVisibleOutput = onVisibleOutput
            )
        }.getOrNull()
    }

    private fun parseGeneratedDraft(raw: String): CharacterAutoFillDraft? =
        parseDraft(raw, json)

    private suspend fun prepareImageContext(
        imageBase64s: List<String>,
        model: ModelConfig,
        onStatus: suspend (String) -> Unit = {},
        onVisibleOutput: (String, String, String) -> Unit = { _, _, _ -> }
    ): PreparedAutoFillImageContext {
        val images = imageBase64s.filter(String::isNotBlank)
        if (images.isEmpty()) return PreparedAutoFillImageContext()
        val result = imageUnderstandingService?.prepare(
            imageBase64s = images,
            generationModel = model,
            requireUnderstanding = true,
            announceDirect = true,
            onStatus = onStatus,
            onDescriptionText = { index, text ->
                onVisibleOutput("image-description-$index", "图片识别输出", text)
            }
        ) ?: if (model.isMultimodal) {
            ImageUnderstandingResult(directImageBase64s = images)
        } else {
            error("当前模型不支持多模态，且未配置可用的视觉模型，无法基于图片生成")
        }
        return PreparedAutoFillImageContext(
            promptContext = CharacterAutoFillImageContext(
                hasSourceImages = result.hasSourceImages,
                descriptions = result.descriptions
            ),
            directImageBase64s = result.directImageBase64s
        )
    }

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
            val decoded = raw.extractJsonObjectCandidates().mapIndexedNotNull { index, candidate ->
                runCatching {
                    json.decodeFromString(CharacterAutoFillDraft.serializer(), candidate)
                        .normalized()
                        .takeIf(CharacterAutoFillDraft::hasAnyContent)
                        ?.let { index to it }
                }.getOrNull()
            }
            return decoded.maxWithOrNull(
                compareBy<Pair<Int, CharacterAutoFillDraft>>(
                    { it.second.contentScore() },
                    { it.first }
                )
            )?.second
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
            if (current.canCreateCharacterList()) {
                return draftCharacters
                    .map { it.toCharacterInfo(idFactory) }
            }
            val usedDraftIndexes = mutableSetOf<Int>()
            val mergedExisting = current.mapIndexed { index, existing ->
                val selected = draftCharacters.selectForSlot(index, existing, usedDraftIndexes)
                if (selected == null) existing else existing.mergeBlank(selected)
            }
            val existingNames = mergedExisting.characterInfoNameKeys()
            val appended = draftCharacters.createdCharactersToAppend(
                usedDraftIndexes = usedDraftIndexes,
                initialNameKeys = existingNames
            )
                .map { it.toCharacterInfo(idFactory) }
            return mergedExisting + appended
        }

        fun buildPromptPayload(
            userInput: String,
            currentCard: CharacterCard,
            externalResearch: ResearchBrief? = null,
            imageContext: CharacterAutoFillImageContext? = null,
            promptJson: Json = defaultPromptJson
        ): String =
            buildJsonObject {
                put("request", userInput.trim())
                imageContext?.takeIf(CharacterAutoFillImageContext::hasContent)?.let { context ->
                    put(
                        "sourceImageInstructions",
                        PromptTemplates.CHARACTER_AUTO_FILL_SOURCE_IMAGE_INSTRUCTIONS
                    )
                    if (context.descriptions.isNotEmpty()) {
                        put("sourceImageDescription", context.descriptions.joinToString("\n"))
                    }
                }
                put("fillTargets", currentCard.buildFillTargets())
                put("lockedContext", promptJson.parseToJsonElement(promptJson.encodeToString(currentCard.toLockedDraft())))
                put("defaultNaiStyle", PromptTemplates.DEFAULT_CHARACTER_NAI_STYLE_PROMPT.trim())
                put("characterImageGuide", PromptTemplates.CHARACTER_IMAGE_NAI_PROMPT_GUIDE.trim())
                externalResearch?.takeIf(ResearchBrief::hasContent)?.let { brief ->
                    put(
                        "externalResearchUsage",
                        PromptTemplates.CHARACTER_EXTERNAL_RESEARCH_USAGE_PROMPT
                    )
                    put(
                        "externalResearch",
                        promptJson.parseToJsonElement(promptJson.encodeToString(ResearchBrief.serializer(), brief))
                    )
                }
            }.toString()
    }

    fun mergeInto(
        current: CharacterCard,
        draft: CharacterAutoFillDraft,
        idFactory: () -> String = { UUID.randomUUID().toString() }
    ): CharacterCard = CharacterAutoFillService.mergeInto(current, draft, idFactory)

    fun buildUserPrompt(
        userInput: String,
        currentCard: CharacterCard,
        externalResearch: ResearchBrief? = null,
        imageContext: CharacterAutoFillImageContext? = null
    ): String =
        CharacterAutoFillService.buildPromptPayload(userInput, currentCard, externalResearch, imageContext, promptJson)
}

private data class PreparedAutoFillImageContext(
    val promptContext: CharacterAutoFillImageContext = CharacterAutoFillImageContext(),
    val directImageBase64s: List<String> = emptyList()
)

private fun String.toChatApiMessage(imageBase64s: List<String>): ChatApiMessage =
    if (imageBase64s.isNotEmpty()) {
        ChatApiMessage.withImages("user", this, imageBase64s)
    } else {
        ChatApiMessage.text("user", this)
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
            if (!characters.canCreateCharacterList()) {
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
        }
        put("characters", characterTargets)
        if (characters.canCreateCharacterList()) {
            put(
                "createCharacters",
                buildJsonObject {
                    put("enabled", true)
                    put("fields", JsonArray(characterFieldNames.map(::JsonPrimitive)))
                }
            )
        } else {
            put(
                "createCharacters",
                buildJsonObject {
                    put("enabled", true)
                    put("fields", JsonArray(characterFieldNames.map(::JsonPrimitive)))
                }
            )
        }
    }

private val characterFieldNames = listOf(
    "name",
    "profile",
    "appearance",
    "clothing",
    "abilities",
    "habits",
    "background",
    "relationships",
    "speakingStyle",
    "imagePrompt"
)

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
    val constrainedCharacters = if (currentCard.characters.canCreateCharacterList()) {
        draftCharacters
    } else {
        val usedDraftIndexes = mutableSetOf<Int>()
        val existingMatches = currentCard.characters.mapIndexedNotNull { index, existing ->
            if (existing.blankFieldNames().isEmpty()) {
                null
            } else {
                draftCharacters.selectForSlot(index, existing, usedDraftIndexes)
                    ?.let { it.copy(targetIndex = it.targetIndex ?: index) }
            }
        }
        val existingNames = currentCard.characters.characterInfoNameKeys() + existingMatches.draftNameKeys()
        val createdCharacters = draftCharacters.createdCharactersToAppend(
            usedDraftIndexes = usedDraftIndexes,
            initialNameKeys = existingNames
        )
        existingMatches + createdCharacters
    }
    return normalizedDraft.copy(characters = constrainedCharacters)
}

private fun List<CharacterInfo>.canCreateCharacterList(): Boolean =
    isEmpty() || isDefaultEmptyCharacterList()

private fun List<CharacterInfo>.characterInfoNameKeys(): Set<String> =
    mapNotNull { it.name.nameKey().takeIf(String::isNotBlank) }.toSet()

private fun List<CharacterAutoFillCharacterDraft>.draftNameKeys(): Set<String> =
    mapNotNull { it.name.nameKey().takeIf(String::isNotBlank) }.toSet()

private fun List<CharacterAutoFillCharacterDraft>.createdCharactersToAppend(
    usedDraftIndexes: Set<Int>,
    initialNameKeys: Set<String>
): List<CharacterAutoFillCharacterDraft> {
    val nameKeys = initialNameKeys.toMutableSet()
    val result = mutableListOf<CharacterAutoFillCharacterDraft>()
    forEachIndexed { draftIndex, draft ->
        if (draftIndex in usedDraftIndexes || draft.targetIndex != null) return@forEachIndexed
        val key = draft.name.nameKey()
        if (key.isNotBlank() && !nameKeys.add(key)) return@forEachIndexed
        result += draft
    }
    return result
}

private fun String.nameKey(): String =
    trim().lowercase()

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

private fun CharacterAutoFillDraft.contentScore(): Int =
    listOf(name, greeting, basicSetting, defaultImagePrompt).count(String::isNotBlank) +
        characters.sumOf(CharacterAutoFillCharacterDraft::contentScore)

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

private fun CharacterAutoFillCharacterDraft.contentScore(): Int =
    listOf(
        name,
        profile,
        appearance,
        clothing,
        abilities,
        habits,
        background,
        relationships,
        speakingStyle,
        imagePrompt
    ).count(String::isNotBlank)

private fun String.fillBlank(candidate: String): String =
    if (isBlank()) candidate.trim() else this
