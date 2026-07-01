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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
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

class CharacterRewriteService(
    private val modelResolver: EffectiveModelResolver,
    private val chatService: StreamingChatService,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = false
        encodeDefaults = false
    }
) {
    private val promptJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
        encodeDefaults = false
    }

    suspend fun rewriteStreaming(
        userInput: String,
        currentCard: CharacterCard,
        modelOverride: ModelConfig? = null,
        onRawText: (String) -> Unit
    ): CharacterRewriteDraft = withContext(Dispatchers.IO) {
        require(userInput.isNotBlank()) { "请输入改写要求" }
        val model = resolveModel(modelOverride)
        val messages = listOf(
            ChatApiMessage.text("system", PromptTemplates.CHARACTER_REWRITE_SYSTEM_PROMPT),
            ChatApiMessage.text("user", buildUserPrompt(userInput, currentCard))
        )
        val raw = StringBuilder()
        var streamError: String? = null
        chatService.streamText(
            messages = messages,
            modelConfig = model,
            maxTokens = 7000,
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
        if (rawText.isBlank()) error(streamError ?: "AI 自动改写返回空内容")
        val draft = parseGeneratedDraft(rawText) ?: repairDraft(rawText, model)
        draft.constrainedTo(currentCard)
    }

    private suspend fun repairDraft(raw: String, model: ModelConfig): CharacterRewriteDraft {
        val repaired = chatService.completeText(
            messages = listOf(
                ChatApiMessage.text("system", PromptTemplates.CHARACTER_REWRITE_REPAIR_PROMPT),
                ChatApiMessage.text("user", raw)
            ),
            modelConfig = model,
            maxTokens = 7000,
            thinkingBudget = 256
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

    private fun parseGeneratedDraft(raw: String): CharacterRewriteDraft? =
        parseDraft(raw, json)

    fun buildUserPrompt(userInput: String, currentCard: CharacterCard): String =
        buildPromptPayload(userInput, currentCard, promptJson)

    fun mergeInto(
        current: CharacterCard,
        draft: CharacterRewriteDraft,
        idFactory: () -> String = { UUID.randomUUID().toString() }
    ): CharacterCard = CharacterRewriteService.mergeInto(current, draft, idFactory)

    companion object {
        private val defaultJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = false
            encodeDefaults = false
        }
        private val defaultPromptJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
            encodeDefaults = false
        }

        fun parseDraft(raw: String, json: Json = defaultJson): CharacterRewriteDraft? {
            val candidates = buildList {
                add(raw.trim())
                add(raw.removeMarkdownFence().trim())
                raw.extractBalancedJsonObject()?.let { add(it) }
                raw.removeMarkdownFence().extractBalancedJsonObject()?.let { add(it) }
            }.distinct().filter { it.isNotBlank() }

            return candidates.firstNotNullOfOrNull { candidate ->
                runCatching {
                    json.decodeFromString(CharacterRewriteDraft.serializer(), candidate)
                        .normalized()
                        .takeIf(CharacterRewriteDraft::hasAnyPatch)
                }.getOrNull()
            }
        }

        fun buildPromptPayload(
            userInput: String,
            currentCard: CharacterCard,
            promptJson: Json = defaultPromptJson
        ): String =
            buildJsonObject {
                put("request", userInput.trim())
                put("mode", currentCard.editMode.name)
                put("current", promptJson.parseToJsonElement(promptJson.encodeToString(currentCard.toRewriteSource())))
                put("rules", currentCard.rewriteRules())
                put("characterImageGuide", PromptTemplates.CHARACTER_IMAGE_NAI_PROMPT_GUIDE.trim())
            }.toString()

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
                    characters = mergeStructuredCharacters(current.characters, normalized, idFactory)
                )
                CharacterEditMode.FREEFORM -> current.copy(
                    name = normalized.name.patch(current.name),
                    greeting = normalized.greeting.patch(current.greeting),
                    basicSetting = normalized.basicSetting.patch(current.basicSetting),
                    defaultImagePrompt = normalized.defaultImagePrompt.patch(current.defaultImagePrompt),
                    freeformCharacterText = normalized.freeformCharacterText.patch(current.freeformCharacterText)
                )
            }
        }
    }
}

@Serializable
private data class CharacterRewriteSource(
    val name: String,
    val greeting: String,
    val basicSetting: String,
    val defaultImagePrompt: String,
    val freeformCharacterText: String? = null,
    val characters: List<CharacterRewriteCharacterSource>? = null
)

@Serializable
private data class CharacterRewriteCharacterSource(
    val id: String,
    val name: String,
    val profile: String,
    val appearance: String,
    val clothing: String,
    val abilities: String,
    val habits: String,
    val background: String,
    val relationships: String,
    val speakingStyle: String,
    val imagePrompt: String
)

private fun CharacterCard.toRewriteSource(): CharacterRewriteSource =
    when (editMode) {
        CharacterEditMode.STRUCTURED -> CharacterRewriteSource(
            name = name,
            greeting = greeting,
            basicSetting = basicSetting,
            defaultImagePrompt = defaultImagePrompt,
            characters = characters.map {
                CharacterRewriteCharacterSource(
                    id = it.id,
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
        )
        CharacterEditMode.FREEFORM -> CharacterRewriteSource(
            name = name,
            greeting = greeting,
            basicSetting = basicSetting,
            defaultImagePrompt = defaultImagePrompt,
            freeformCharacterText = freeformCharacterText
        )
    }

private fun CharacterCard.rewriteRules(): JsonObject = buildJsonObject {
    put("nullablePatch", "字段缺失或 null 表示保持当前；空字符串表示清空；非空字符串表示替换。")
    put("cardFields", buildJsonArray {
        listOf("name", "greeting", "basicSetting", "defaultImagePrompt").forEach { add(JsonPrimitive(it)) }
    })
    when (editMode) {
        CharacterEditMode.STRUCTURED -> {
            put("characterFields", buildJsonArray {
                listOf(
                    "id",
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
                ).forEach { add(JsonPrimitive(it)) }
            })
            put("maxCharacters", 6)
            put("allowDeleteCharacterIds", true)
            put("allowNewCharacters", true)
        }
        CharacterEditMode.FREEFORM -> {
            put("freeformField", "freeformCharacterText")
        }
    }
}

private fun mergeStructuredCharacters(
    current: List<CharacterInfo>,
    draft: CharacterRewriteDraft,
    idFactory: () -> String
): List<CharacterInfo> {
    val deletedIds = draft.deleteCharacterIds.map(String::trim).filter(String::isNotBlank).toSet()
    val patchesById = draft.characters
        .map(CharacterRewriteCharacterDraft::normalized)
        .filter { !it.id.isNullOrBlank() }
        .associateBy { it.id!!.trim() }
    val kept = current
        .filterNot { it.id in deletedIds }
        .map { existing -> patchesById[existing.id]?.let(existing::rewriteWith) ?: existing }
        .toMutableList()
    val currentIds = current.map { it.id }.toSet()
    val additions = draft.characters
        .map(CharacterRewriteCharacterDraft::normalized)
        .filter(CharacterRewriteCharacterDraft::hasVisibleContent)
        .filter { it.id.isNullOrBlank() || it.id !in currentIds }
    additions.forEach { addition ->
        if (kept.size >= 6) return@forEach
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
    return kept.take(6)
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

private fun CharacterRewriteCharacterDraft.hasVisibleContent(): Boolean =
    listOf(name, profile, appearance, clothing, abilities, habits, background, relationships, speakingStyle, imagePrompt)
        .any { !it.isNullOrBlank() }

private fun String?.patch(current: String): String =
    this ?: current

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
