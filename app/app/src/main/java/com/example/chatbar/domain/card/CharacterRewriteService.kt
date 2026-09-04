package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterEditMode
import com.example.chatbar.data.local.entity.CharacterInfo
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.chat.StreamEvent
import com.example.chatbar.domain.chat.StreamingChatService
import com.example.chatbar.domain.model.EffectiveModelResolver
import com.example.chatbar.domain.prompt.PromptTemplates
import com.example.chatbar.domain.search.CharacterReferenceDocument
import com.example.chatbar.domain.search.CharacterResearchOptions
import com.example.chatbar.domain.search.CharacterResearchService
import com.example.chatbar.domain.search.ResearchBrief
import com.example.chatbar.domain.search.ResearchDebugSnapshot
import com.example.chatbar.domain.search.hasManualUrlSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import java.util.UUID

@Serializable
data class CharacterRewriteDraft(
    val name: String? = null,
    val greeting: String? = null,
    val basicSetting: String? = null,
    val defaultImagePrompt: String? = null,
    val freeformCharacterText: String? = null,
    val deleteCharacterIds: List<String> = emptyList(),
    val characters: List<CharacterRewriteCharacterDraft> = emptyList()
)

@Serializable
data class CharacterRewriteCharacterDraft(
    val id: String? = null,
    val name: String? = null,
    val profile: String? = null,
    val appearance: String? = null,
    val clothing: String? = null,
    val abilities: String? = null,
    val habits: String? = null,
    val background: String? = null,
    val relationships: String? = null,
    val speakingStyle: String? = null,
    val imagePrompt: String? = null
)

data class CharacterRewriteGenerationCheckpoint(
    val research: ResearchDebugSnapshot? = null,
    val rawFinalText: String = ""
)

class CharacterRewriteService(
    private val modelResolver: EffectiveModelResolver,
    private val chatService: StreamingChatService,
    private val researchService: CharacterResearchService? = null,
    @OptIn(ExperimentalSerializationApi::class)
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = false
        allowTrailingComma = true
    }
) {
    suspend fun rewriteStreaming(
        userInput: String,
        currentCard: CharacterCard,
        modelOverride: ModelConfig? = null,
        referenceDocuments: List<CharacterReferenceDocument> = emptyList(),
        researchOptions: CharacterResearchOptions = CharacterResearchOptions(),
        resumeFrom: CharacterRewriteGenerationCheckpoint? = null,
        onCheckpoint: (CharacterRewriteGenerationCheckpoint) -> Unit = {},
        onStatus: (String) -> Unit = {},
        onResearchDebug: (ResearchDebugSnapshot) -> Unit = {},
        onVisibleOutput: (String, String, String) -> Unit = { _, _, _ -> },
        onRawText: (String) -> Unit
    ): CharacterRewriteDraft = withContext(Dispatchers.IO) {
        require(userInput.isNotBlank()) { "请输入改写要求" }
        val model = resolveModel(modelOverride)
        var checkpoint = resumeFrom ?: CharacterRewriteGenerationCheckpoint()
        val researchBrief = buildResearchBrief(
            userInput,
            currentCard,
            model,
            referenceDocuments,
            researchOptions,
            onStatus,
            onResearchDebug,
            onVisibleOutput,
            resumeFrom?.research
        ) { snapshot ->
            checkpoint = checkpoint.copy(research = snapshot)
            onCheckpoint(checkpoint)
        }
        onStatus("改写角色卡")
        val messages = listOf(
            ChatApiMessage.text("system", PromptTemplates.CHARACTER_REWRITE_SYSTEM_PROMPT),
            ChatApiMessage.text("user", buildUserPrompt(userInput, currentCard, researchBrief))
        )
        val raw = StringBuilder(checkpoint.rawFinalText)
        val previewThrottle = StreamingTextPreviewThrottle(onRawText)
        var streamError: String? = null
        if (checkpoint.rawFinalText.isBlank()) chatService.streamText(
            messages = messages,
            modelConfig = model,
            maxTokens = 7000,
            thinkingBudget = 512,
            readTimeoutSeconds = CHARACTER_CARD_AI_READ_TIMEOUT_SECONDS
        ).collect { event ->
            when (event) {
                is StreamEvent.Delta -> {
                    raw.append(event.text)
                    previewThrottle.publishIfDue(raw)
                }
                is StreamEvent.Error -> streamError = event.message
                StreamEvent.Done,
                is StreamEvent.Usage,
                is StreamEvent.ReasoningDelta -> Unit
            }
        }

        val rawText = raw.toString()
        previewThrottle.publishFinal(rawText)
        if (rawText.isBlank()) error(streamError ?: "AI 自动改写返回空内容")
        if (checkpoint.rawFinalText.isBlank()) {
            checkpoint = checkpoint.copy(rawFinalText = rawText)
            onCheckpoint(checkpoint)
        }
        val draft = parseGeneratedDraft(rawText) ?: repairDraft(rawText, model, currentCard)
        materializeDraft(currentCard, draft)
    }

    private suspend fun repairDraft(
        raw: String,
        model: ModelConfig,
        currentCard: CharacterCard
    ): CharacterRewriteDraft {
        val repaired = chatService.completeText(
            messages = listOf(
                ChatApiMessage.text("system", PromptTemplates.CHARACTER_REWRITE_REPAIR_PROMPT),
                ChatApiMessage.text(
                    "user",
                    buildJsonObject {
                        put("outputSchema", currentCard.rewriteOutputSchema())
                        put("text", raw)
                    }.toString()
                )
            ),
            modelConfig = model,
            maxTokens = 7000,
            thinkingBudget = 256,
            readTimeoutSeconds = CHARACTER_CARD_AI_READ_TIMEOUT_SECONDS
        )
        return parseGeneratedDraft(repaired)
            ?: error("AI 自动改写结果不是可解析 JSON：${raw.take(500)}")
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
        referenceDocuments: List<CharacterReferenceDocument>,
        researchOptions: CharacterResearchOptions,
        onStatus: (String) -> Unit,
        onResearchDebug: (ResearchDebugSnapshot) -> Unit,
        onVisibleOutput: (String, String, String) -> Unit,
        resumeFrom: ResearchDebugSnapshot? = null,
        onCheckpoint: (ResearchDebugSnapshot) -> Unit = {}
    ): ResearchBrief? {
        val service = researchService ?: if (
            referenceDocuments.isNotEmpty() || researchOptions.hasManualUrlSource()
        ) {
            error("外部资料研究服务不可用")
        } else {
            return null
        }
        val researchModel = runCatching { modelResolver.retrievalModel() }
            .getOrNull()
            ?.takeIf { it.apiKey.isNotBlank() }
            ?: generationModel
        val research: suspend () -> ResearchBrief? = {
            service.research(
                userInput = userInput,
                currentCard = currentCard,
                modelConfig = researchModel,
                researchOptions = researchOptions,
                referenceDocuments = referenceDocuments,
                onDebug = onResearchDebug,
                resumeFrom = resumeFrom,
                onCheckpoint = onCheckpoint,
                onStatus = onStatus,
                onVisibleOutput = onVisibleOutput
            )
        }
        return if (referenceDocuments.isNotEmpty() || researchOptions.hasManualUrlSource()) {
            research()
        } else {
            runCatching { research() }.getOrNull()
        }
    }

    private fun parseGeneratedDraft(raw: String): CharacterRewriteDraft? =
        parseDraft(raw, json)

    fun buildUserPrompt(userInput: String, currentCard: CharacterCard, externalResearch: ResearchBrief? = null): String =
        buildPromptPayload(userInput, currentCard, externalResearch)

    fun mergeInto(
        current: CharacterCard,
        draft: CharacterRewriteDraft,
        idFactory: () -> String = { UUID.randomUUID().toString() }
    ): CharacterCard = CharacterRewriteService.mergeInto(current, draft, idFactory)

    companion object {
        @OptIn(ExperimentalSerializationApi::class)
        private val defaultJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            encodeDefaults = false
            allowTrailingComma = true
        }

        fun parseDraft(raw: String, json: Json = defaultJson): CharacterRewriteDraft? {
            val decoded = raw.extractJsonObjectCandidates().mapIndexedNotNull { index, candidate ->
                runCatching {
                    json.decodeFromString(CharacterRewriteDraft.serializer(), candidate)
                        .normalized()
                        .takeIf(CharacterRewriteDraft::hasAnyPatch)
                        ?.let { index to it }
                }.getOrNull()
            }
            return decoded.maxWithOrNull(
                compareBy<Pair<Int, CharacterRewriteDraft>>(
                    { it.second.patchScore() },
                    { it.first }
                )
            )?.second
        }

        fun buildPromptPayload(
            userInput: String,
            currentCard: CharacterCard,
            externalResearch: ResearchBrief? = null
        ): String =
            buildJsonObject {
                put("request", userInput.trim())
                put("current", currentCard.toRewriteCurrent())
                put("outputSchema", currentCard.rewriteOutputSchema())
                if (currentCard.editMode == CharacterEditMode.STRUCTURED) {
                    put("characterImageGuide", PromptTemplates.CHARACTER_IMAGE_NAI_PROMPT_GUIDE.trim())
                }
                externalResearch?.takeIf(ResearchBrief::hasContent)?.let { brief ->
                    put(
                        "externalResearchUsage",
                        PromptTemplates.CHARACTER_EXTERNAL_RESEARCH_USAGE_PROMPT
                    )
                    put(
                        "externalResearch",
                        defaultJson.parseToJsonElement(defaultJson.encodeToString(ResearchBrief.serializer(), brief))
                    )
                }
            }.toString()

        fun materializeDraft(
            current: CharacterCard,
            draft: CharacterRewriteDraft
        ): CharacterRewriteDraft {
            val normalized = draft.constrainedTo(current)
            return when (current.editMode) {
                CharacterEditMode.STRUCTURED -> {
                    val deletedIds = normalized.deleteCharacterIds.map(String::trim).filter(String::isNotBlank).toSet()
                    val (patchesById, additions) = resolveRewriteCharacterTargets(
                        current = current.characters,
                        deletedIds = deletedIds,
                        drafts = normalized.characters
                    )
                    val kept = current.characters
                        .filterNot { it.id in deletedIds }
                        .map { it.toFullRewriteDraft(patchesById[it.id]) }
                    CharacterRewriteDraft(
                        name = normalized.name.patch(current.name),
                        greeting = normalized.greeting.patch(current.greeting),
                        basicSetting = normalized.basicSetting.patch(current.basicSetting),
                        defaultImagePrompt = normalized.defaultImagePrompt.patch(current.defaultImagePrompt),
                        deleteCharacterIds = normalized.deleteCharacterIds,
                        characters = kept + additions.map(CharacterRewriteCharacterDraft::toFullNewCharacterDraft)
                    )
                }
                CharacterEditMode.FREEFORM -> CharacterRewriteDraft(
                    name = normalized.name.patch(current.name),
                    greeting = normalized.greeting.patch(current.greeting),
                    basicSetting = normalized.basicSetting.patch(current.basicSetting),
                    defaultImagePrompt = normalized.defaultImagePrompt.patch(current.defaultImagePrompt),
                    freeformCharacterText = normalized.freeformCharacterText.patch(current.freeformCharacterText)
                )
            }
        }

        fun mergeInto(
            current: CharacterCard,
            draft: CharacterRewriteDraft,
            idFactory: () -> String = { UUID.randomUUID().toString() }
        ): CharacterCard {
            val normalized = draft.normalized()
            return when (current.editMode) {
                CharacterEditMode.STRUCTURED -> current.copy(
                    name = normalized.name.patch(current.name),
                    greeting = normalized.greeting.patch(current.greeting),
                    basicSetting = normalized.basicSetting.patch(current.basicSetting),
                    defaultImagePrompt = normalized.defaultImagePrompt.patch(current.defaultImagePrompt),
                    defaultImageNegativePrompt = PromptTemplates.effectiveCharacterNaiNegativePrompt(
                        current.defaultImageNegativePrompt
                    ),
                    characters = mergeStructuredCharacters(current.characters, normalized, idFactory)
                )
                CharacterEditMode.FREEFORM -> current.copy(
                    name = normalized.name.patch(current.name),
                    greeting = normalized.greeting.patch(current.greeting),
                    basicSetting = normalized.basicSetting.patch(current.basicSetting),
                    defaultImagePrompt = normalized.defaultImagePrompt.patch(current.defaultImagePrompt),
                    defaultImageNegativePrompt = PromptTemplates.effectiveCharacterNaiNegativePrompt(
                        current.defaultImageNegativePrompt
                    ),
                    freeformCharacterText = normalized.freeformCharacterText.patch(current.freeformCharacterText)
                )
            }
        }
    }
}

private fun CharacterCard.toRewriteCurrent(): JsonObject = buildJsonObject {
    putNonBlank("name", name)
    putNonBlank("greeting", greeting)
    putNonBlank("basicSetting", basicSetting)
    putNonBlank("defaultImagePrompt", defaultImagePrompt)
    when (editMode) {
        CharacterEditMode.STRUCTURED -> {
            val visibleCharacters = characters.mapNotNull(CharacterInfo::toRewriteCurrentCharacter)
            if (visibleCharacters.isNotEmpty()) {
                put("characters", buildJsonArray {
                    visibleCharacters.forEach { add(it) }
                })
            }
        }
        CharacterEditMode.FREEFORM -> {
            putNonBlank("freeformCharacterText", freeformCharacterText)
        }
    }
}

private fun CharacterInfo.toRewriteCurrentCharacter(): JsonObject? {
    val hasContent = listOf(
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
    ).any(String::isNotBlank)
    if (!hasContent) return null
    return buildJsonObject {
        put("id", id)
        putNonBlank("name", name)
        putNonBlank("profile", profile)
        putNonBlank("appearance", appearance)
        putNonBlank("clothing", clothing)
        putNonBlank("abilities", abilities)
        putNonBlank("habits", habits)
        putNonBlank("background", background)
        putNonBlank("relationships", relationships)
        putNonBlank("speakingStyle", speakingStyle)
        putNonBlank("imagePrompt", imagePrompt)
    }
}

private fun CharacterCard.rewriteOutputSchema(): JsonObject =
    when (editMode) {
        CharacterEditMode.STRUCTURED -> buildJsonObject {
            put("schemaName", "structuredCharacterRewriteCandidate")
            put("candidateSemantics", "输出应用后的完整候选；保留不变的现有内容也要原样写回；空字符串只表示明确清空。")
            put("allowedTopLevelKeys", jsonStringArray(cardPatchFields + listOf("deleteCharacterIds", "characters")))
            put("cardFields", jsonStringArray(cardPatchFields))
            put("deleteCharacterIds", "string[]；只有用户明确要求删除角色时输出")
            put("characters", buildJsonArray {
                add(
                    buildJsonObject {
                        put("id", "已有角色 id；新增角色省略或写 null")
                        structuredCharacterPatchFields.forEach { put(it, "string，保留内容也要原样写回") }
                    }
                )
            })
            put("rules", buildJsonArray {
                add(JsonPrimitive("characters 是应用后的完整人物候选列表；保留人物也要输出。"))
                add(JsonPrimitive("已有角色必须按 current.characters[].id 改写并保留 id。"))
                add(JsonPrimitive("删除角色必须写入 deleteCharacterIds；不要靠遗漏删除。"))
                add(JsonPrimitive("用户明确要求新增人物时，可以新增无 id 的角色对象。"))
                add(JsonPrimitive("新增人物必须基于 current 与 request，不要变成无关原创卡。"))
                add(JsonPrimitive("imagePrompt 只写稳定外观、身份、发型、体型、服装等角色形象标签。"))
            })
        }
        CharacterEditMode.FREEFORM -> buildJsonObject {
            put("schemaName", "freeformCharacterRewriteCandidate")
            put("candidateSemantics", "输出应用后的完整候选；保留不变的现有内容也要原样写回；空字符串只表示明确清空。")
            put("allowedTopLevelKeys", jsonStringArray(cardPatchFields + listOf("freeformCharacterText")))
            put("cardFields", jsonStringArray(cardPatchFields))
            put("freeformCharacterText", "string，保留内容也要原样写回")
            put("rules", buildJsonArray {
                add(JsonPrimitive("输出应用后的完整自由模式候选。"))
                add(JsonPrimitive("输出 JSON 不包含 characters 或 deleteCharacterIds。"))
            })
        }
    }

private val cardPatchFields = listOf("name", "greeting", "basicSetting", "defaultImagePrompt")

private val structuredCharacterPatchFields = listOf(
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

private fun JsonObjectBuilder.putNonBlank(key: String, value: String) {
    if (value.isNotBlank()) put(key, value)
}

private fun jsonStringArray(values: List<String>) = buildJsonArray {
    values.forEach { add(JsonPrimitive(it)) }
}

private fun CharacterInfo.toFullRewriteDraft(patch: CharacterRewriteCharacterDraft?): CharacterRewriteCharacterDraft =
    CharacterRewriteCharacterDraft(
        id = id,
        name = patch?.name.patch(name),
        profile = patch?.profile.patch(profile),
        appearance = patch?.appearance.patch(appearance),
        clothing = patch?.clothing.patch(clothing),
        abilities = patch?.abilities.patch(abilities),
        habits = patch?.habits.patch(habits),
        background = patch?.background.patch(background),
        relationships = patch?.relationships.patch(relationships),
        speakingStyle = patch?.speakingStyle.patch(speakingStyle),
        imagePrompt = patch?.imagePrompt.patch(imagePrompt)
    )

private fun CharacterRewriteCharacterDraft.toFullNewCharacterDraft(): CharacterRewriteCharacterDraft =
    CharacterRewriteCharacterDraft(
        id = null,
        name = name.orEmpty(),
        profile = profile.orEmpty(),
        appearance = appearance.orEmpty(),
        clothing = clothing.orEmpty(),
        abilities = abilities.orEmpty(),
        habits = habits.orEmpty(),
        background = background.orEmpty(),
        relationships = relationships.orEmpty(),
        speakingStyle = speakingStyle.orEmpty(),
        imagePrompt = imagePrompt.orEmpty()
    )

private fun mergeStructuredCharacters(
    current: List<CharacterInfo>,
    draft: CharacterRewriteDraft,
    idFactory: () -> String
): List<CharacterInfo> {
    val deletedIds = draft.deleteCharacterIds.map(String::trim).filter(String::isNotBlank).toSet()
    val (patchesById, additions) = resolveRewriteCharacterTargets(current, deletedIds, draft.characters)
    val kept = current
        .filterNot { it.id in deletedIds }
        .map { existing -> patchesById[existing.id]?.let(existing::rewriteWith) ?: existing }
        .toMutableList()
    additions.forEach { addition ->
        kept += CharacterInfo(
            id = idFactory(),
            name = addition.name.orEmpty(),
            profile = addition.profile.orEmpty(),
            appearance = addition.appearance.orEmpty(),
            clothing = addition.clothing.orEmpty(),
            abilities = addition.abilities.orEmpty(),
            habits = addition.habits.orEmpty(),
            background = addition.background.orEmpty(),
            relationships = addition.relationships.orEmpty(),
            speakingStyle = addition.speakingStyle.orEmpty(),
            imagePrompt = addition.imagePrompt.orEmpty()
        )
    }
    return kept
}

/**
 * 把 AI 输出的人物候选解析成补丁与新增：
 * 带 id 且匹配现有角色的条目按 id 打补丁；id 缺失或未知的条目先按名称匹配现有角色打补丁，
 * 无法匹配且含有可见内容时视为新增；同一角色被重复输出时保留第一次命中，避免重复人物。
 */
private fun resolveRewriteCharacterTargets(
    current: List<CharacterInfo>,
    deletedIds: Set<String>,
    drafts: List<CharacterRewriteCharacterDraft>
): Pair<Map<String, CharacterRewriteCharacterDraft>, List<CharacterRewriteCharacterDraft>> {
    val normalized = drafts.map(CharacterRewriteCharacterDraft::normalized)
    val currentIds = current.map { it.id }.toSet()
    val namesToCharacters = current
        .filterNot { it.id in deletedIds }
        .filter { it.name.isNotBlank() }
        .groupBy { it.name.trim().lowercase() }
    val claimed = mutableSetOf<String>()
    val patches = mutableMapOf<String, CharacterRewriteCharacterDraft>()
    val additions = mutableListOf<CharacterRewriteCharacterDraft>()

    fun claim(id: String, draft: CharacterRewriteCharacterDraft) {
        if (claimed.add(id)) {
            patches[id] = draft
        }
    }

    for (draft in normalized) {
        val id = draft.id?.trim()
        if (!id.isNullOrBlank() && id in currentIds && id !in deletedIds) {
            claim(id, draft)
            continue
        }
        val nameKey = draft.name?.trim()?.lowercase()?.takeIf(String::isNotBlank)
        val nameMatches = nameKey?.let { key -> namesToCharacters[key] }
        if (nameMatches != null && nameMatches.isNotEmpty()) {
            val nameMatch = nameMatches.firstOrNull { it.id !in claimed }
            if (nameMatch != null) {
                claim(nameMatch.id, draft)
            }
            continue
        }
        if (draft.hasVisibleContent()) additions += draft
    }
    return patches to additions
}

private fun CharacterInfo.rewriteWith(draft: CharacterRewriteCharacterDraft): CharacterInfo =
    copy(
        name = draft.name.patch(name),
        profile = draft.profile.patch(profile),
        appearance = draft.appearance.patch(appearance),
        clothing = draft.clothing.patch(clothing),
        abilities = draft.abilities.patch(abilities),
        habits = draft.habits.patch(habits),
        background = draft.background.patch(background),
        relationships = draft.relationships.patch(relationships),
        speakingStyle = draft.speakingStyle.patch(speakingStyle),
        imagePrompt = draft.imagePrompt.patch(imagePrompt)
    )

private fun CharacterRewriteDraft.constrainedTo(currentCard: CharacterCard): CharacterRewriteDraft {
    val normalized = normalized()
    return when (currentCard.editMode) {
        CharacterEditMode.STRUCTURED -> normalized.copy(freeformCharacterText = null)
        CharacterEditMode.FREEFORM -> normalized.copy(characters = emptyList(), deleteCharacterIds = emptyList())
    }
}

private fun CharacterRewriteDraft.normalized(): CharacterRewriteDraft =
    copy(
        name = name?.trim(),
        greeting = greeting?.trim(),
        basicSetting = basicSetting?.trim(),
        defaultImagePrompt = defaultImagePrompt?.trim(),
        freeformCharacterText = freeformCharacterText?.trim(),
        deleteCharacterIds = deleteCharacterIds.map(String::trim).filter(String::isNotBlank),
        characters = characters.map(CharacterRewriteCharacterDraft::normalized)
    )

private fun CharacterRewriteCharacterDraft.normalized(): CharacterRewriteCharacterDraft =
    copy(
        id = id?.trim(),
        name = name?.trim(),
        profile = profile?.trim(),
        appearance = appearance?.trim(),
        clothing = clothing?.trim(),
        abilities = abilities?.trim(),
        habits = habits?.trim(),
        background = background?.trim(),
        relationships = relationships?.trim(),
        speakingStyle = speakingStyle?.trim(),
        imagePrompt = imagePrompt?.trim()
    )

private fun CharacterRewriteDraft.hasAnyPatch(): Boolean =
    name != null ||
        greeting != null ||
        basicSetting != null ||
        defaultImagePrompt != null ||
        freeformCharacterText != null ||
        deleteCharacterIds.isNotEmpty() ||
        characters.any(CharacterRewriteCharacterDraft::hasAnyPatch)

private fun CharacterRewriteDraft.patchScore(): Int =
    listOf(name, greeting, basicSetting, defaultImagePrompt, freeformCharacterText).count { it != null } +
        deleteCharacterIds.size +
        characters.sumOf(CharacterRewriteCharacterDraft::patchScore)

private fun CharacterRewriteCharacterDraft.hasAnyPatch(): Boolean =
    id != null ||
        name != null ||
        profile != null ||
        appearance != null ||
        clothing != null ||
        abilities != null ||
        habits != null ||
        background != null ||
        relationships != null ||
        speakingStyle != null ||
        imagePrompt != null

private fun CharacterRewriteCharacterDraft.patchScore(): Int =
    listOf(
        id,
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
    ).count { it != null }

private fun CharacterRewriteCharacterDraft.hasVisibleContent(): Boolean =
    listOf(name, profile, appearance, clothing, abilities, habits, background, relationships, speakingStyle, imagePrompt)
        .any { !it.isNullOrBlank() }

private fun String?.patch(current: String): String =
    this ?: current
