package com.example.chatbar.domain.image

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterEditMode
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.chat.StreamEvent
import com.example.chatbar.domain.chat.StreamingChatService
import com.example.chatbar.domain.prompt.PromptTemplates
import com.example.chatbar.utils.DebugLogManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

@Serializable
data class DesignedImagePrompt(
    val baseCaption: String = "",
    val scenePrompt: String = "",
    val sizePreset: String = NovelAiImageSizePreset.PORTRAIT.name,
    val characters: List<DesignedCharacterPrompt> = emptyList()
) {
    val effectiveBaseCaption: String get() = baseCaption.ifBlank { scenePrompt }
}

@Serializable
data class DesignedCharacterPrompt(
    val name: String,
    val caption: String = "",
    val adjustment: String = "",
    val center: DesignedCharacterCenter? = null
) {
    val effectiveCaption: String get() = caption.ifBlank { adjustment }
}

@Serializable
data class DesignedCharacterCenter(
    val x: Float,
    val y: Float
)

data class NovelAiCharacterCaption(
    val prompt: String,
    val center: DesignedCharacterCenter
)

data class NovelAiPromptPlan(
    val baseCaption: String,
    val characterCaptions: List<NovelAiCharacterCaption>,
    val designed: DesignedImagePrompt? = null,
    val sizePreset: NovelAiImageSizePreset = NovelAiImageSizePreset.PORTRAIT,
    val negativePrompt: String = PromptTemplates.defaultCharacterNaiNegativePrompt()
) {
    val effectiveNegativePrompt: String
        get() = PromptTemplates.effectiveCharacterNaiNegativePrompt(negativePrompt)
}

data class NovelAiPromptDebugExchange(
    val title: String,
    val input: String,
    val output: String
)

data class NovelAiPromptDebugResult(
    val plan: NovelAiPromptPlan,
    val exchanges: List<NovelAiPromptDebugExchange>
)

class NovelAiPromptDesigner(
    private val chatService: StreamingChatService,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }
) {
    suspend fun design(
        messages: List<ChatMessage>,
        anchorMessageId: String,
        card: CharacterCard,
        model: ModelConfig,
        playerName: String? = null,
        sessionId: String? = null,
        onDelta: (String) -> Unit = {}
    ): NovelAiPromptPlan {
        val context = contextForAnchor(messages, anchorMessageId)
        require(context.isNotEmpty()) { "没有可用于生图的聊天上下文" }
        val structured = card.editMode == CharacterEditMode.STRUCTURED
        val characterPrompts = if (structured) {
            card.characters.map { baseCharacterName(it.name) to it.imagePrompt.trim() }
        } else {
            emptyList()
        }
        val systemPrompt = PromptTemplates.novelAiImagePromptSystem(
            cardDefaultImagePrompt = card.defaultImagePrompt,
            characterImagePrompts = characterPrompts,
            structured = structured
        )
        val userPrompt = PromptTemplates.novelAiImagePromptConversation(context, playerName)
        val raw = streamCompletion(
            messages = listOf(
                ChatApiMessage.text("system", systemPrompt),
                ChatApiMessage.text("user", userPrompt)
            ),
            model = model,
            onDelta = onDelta
        )
        sessionId?.let { sid ->
            DebugLogManager.recordCompleted(
                sessionId = sid,
                modelName = model.modelName,
                apiUrl = "${model.baseUrl.trimEnd('/')}/chat/completions",
                requestBodyJson = buildDesignRequestJson(systemPrompt, userPrompt),
                rawAiOutput = raw
            )
        }
        val designed = parseOrRepair(raw, model, onDelta)
        return convert(card, designed)
    }

    suspend fun designForCharacterCard(
        card: CharacterCard,
        model: ModelConfig,
        onDelta: (String) -> Unit = {}
    ): NovelAiPromptPlan {
        require(card.hasImageDesignSource()) { "没有可用于生图的角色卡内容" }
        val structured = card.editMode == CharacterEditMode.STRUCTURED
        val characterPrompts = if (structured) {
            card.characters.map { baseCharacterName(it.name) to it.imagePrompt.trim() }
        } else {
            emptyList()
        }
        val systemPrompt = PromptTemplates.novelAiImagePromptSystem(
            cardDefaultImagePrompt = card.defaultImagePrompt,
            characterImagePrompts = characterPrompts,
            structured = structured
        )
        val raw = streamCompletion(
            messages = listOf(
                ChatApiMessage.text("system", systemPrompt),
                ChatApiMessage.text("user", characterCardImagePrompt(card))
            ),
            model = model,
            onDelta = onDelta
        )
        val designed = parseOrRepair(raw, model, onDelta)
        return convert(card, designed)
    }

    suspend fun designForMoment(
        card: CharacterCard,
        momentImageBrief: String,
        model: ModelConfig,
        onDelta: (String) -> Unit = {}
    ): NovelAiPromptPlan {
        require(momentImageBrief.isNotBlank()) { "没有可用于朋友圈生图的图片设计" }
        val structured = card.editMode == CharacterEditMode.STRUCTURED
        val characterPrompts = if (structured) {
            card.characters.map { baseCharacterName(it.name) to it.imagePrompt.trim() }
        } else {
            emptyList()
        }
        val systemPrompt = PromptTemplates.novelAiImagePromptSystem(
            cardDefaultImagePrompt = card.defaultImagePrompt,
            characterImagePrompts = characterPrompts,
            structured = structured
        )
        val raw = streamCompletion(
            messages = listOf(
                ChatApiMessage.text("system", systemPrompt),
                ChatApiMessage.text("user", PromptTemplates.novelAiImagePromptMoment(momentImageBrief))
            ),
            model = model,
            onDelta = onDelta
        )
        val designed = parseOrRepair(raw, model, onDelta)
        return convert(card, designed)
    }

    suspend fun designForMomentDebug(
        card: CharacterCard,
        momentImageBrief: String,
        model: ModelConfig,
        onDelta: (String) -> Unit = {}
    ): NovelAiPromptDebugResult {
        require(momentImageBrief.isNotBlank()) { "没有可用于朋友圈生图的图片设计" }
        val structured = card.editMode == CharacterEditMode.STRUCTURED
        val characterPrompts = if (structured) {
            card.characters.map { baseCharacterName(it.name) to it.imagePrompt.trim() }
        } else {
            emptyList()
        }
        val systemPrompt = PromptTemplates.novelAiImagePromptSystem(
            cardDefaultImagePrompt = card.defaultImagePrompt,
            characterImagePrompts = characterPrompts,
            structured = structured
        )
        val userPrompt = PromptTemplates.novelAiImagePromptMoment(momentImageBrief)
        val exchanges = mutableListOf<NovelAiPromptDebugExchange>()
        val raw = streamCompletion(
            messages = listOf(
                ChatApiMessage.text("system", systemPrompt),
                ChatApiMessage.text("user", userPrompt)
            ),
            model = model,
            onDelta = onDelta
        )
        exchanges += NovelAiPromptDebugExchange(
            title = "NovelAI Prompt 设计",
            input = debugMessages(systemPrompt, userPrompt),
            output = raw
        )
        val designed = parseOrRepairDebug(raw, model, onDelta, exchanges)
        return NovelAiPromptDebugResult(
            plan = convert(card, designed),
            exchanges = exchanges
        )
    }

    suspend fun designForPromptTool(
        imageDescription: String,
        stylePrompt: String,
        characterPrompt: String,
        model: ModelConfig,
        onContentDelta: (String) -> Unit = {},
        onReasoningDelta: (String) -> Unit = {}
    ): NovelAiPromptPlan {
        val request = promptToolInputText(
            imageDescription = imageDescription,
            stylePrompt = stylePrompt,
            characterPrompt = characterPrompt
        )
        require(request.isNotBlank()) { "请输入图片描述、画风或角色提示词" }
        val systemPrompt = PromptTemplates.NOVELAI_IMAGE_PROMPT_SYSTEM.trim()
        val userPrompt = PromptTemplates.novelAiImagePromptConversation(
            listOf(
                ChatMessage.create(
                    sessionId = PROMPT_TOOL_SESSION_ID,
                    role = MessageRole.USER,
                    content = request
                )
            )
        )
        val raw = streamCompletion(
            messages = listOf(
                ChatApiMessage.text("system", systemPrompt),
                ChatApiMessage.text("user", userPrompt)
            ),
            model = model,
            onContentDelta = onContentDelta,
            onReasoningDelta = onReasoningDelta
        )
        val designed = parseOrRepair(raw, model, onContentDelta, onReasoningDelta)
        return convert(designed)
    }

    private suspend fun parseOrRepair(
        raw: String,
        model: ModelConfig,
        onDelta: (String) -> Unit
    ): DesignedImagePrompt {
        parse(raw)?.let { return it }
        val repaired = streamCompletion(
            messages = listOf(
                ChatApiMessage.text(
                    "system",
                    PromptTemplates.NOVELAI_IMAGE_PROMPT_REPAIR_SYSTEM
                ),
                ChatApiMessage.text("user", raw)
            ),
            model = model,
            onDelta = onDelta
        )
        return parse(repaired) ?: error("对话 AI 返回的生图 Prompt JSON 无法解析，原始内容: ${raw.take(500)}")
    }

    private suspend fun parseOrRepair(
        raw: String,
        model: ModelConfig,
        onContentDelta: (String) -> Unit,
        onReasoningDelta: (String) -> Unit
    ): DesignedImagePrompt {
        parse(raw)?.let { return it }
        val repaired = streamCompletion(
            messages = listOf(
                ChatApiMessage.text(
                    "system",
                    PromptTemplates.NOVELAI_IMAGE_PROMPT_REPAIR_SYSTEM
                ),
                ChatApiMessage.text("user", raw)
            ),
            model = model,
            onContentDelta = onContentDelta,
            onReasoningDelta = onReasoningDelta
        )
        return parse(repaired) ?: error("对话 AI 返回的生图 Prompt JSON 无法解析，原始内容: ${raw.take(500)}")
    }

    private suspend fun parseOrRepairDebug(
        raw: String,
        model: ModelConfig,
        onDelta: (String) -> Unit,
        exchanges: MutableList<NovelAiPromptDebugExchange>
    ): DesignedImagePrompt {
        parse(raw)?.let { return it }
        val systemPrompt = PromptTemplates.NOVELAI_IMAGE_PROMPT_REPAIR_SYSTEM
        val repaired = streamCompletion(
            messages = listOf(
                ChatApiMessage.text("system", systemPrompt),
                ChatApiMessage.text("user", raw)
            ),
            model = model,
            onDelta = onDelta
        )
        exchanges += NovelAiPromptDebugExchange(
            title = "NovelAI Prompt 修复",
            input = debugMessages(systemPrompt, raw),
            output = repaired
        )
        return parse(repaired) ?: error("对话 AI 返回的生图 Prompt JSON 无法解析，原始内容: ${raw.take(500)}")
    }

    private suspend fun streamCompletion(
        messages: List<ChatApiMessage>,
        model: ModelConfig,
        onDelta: (String) -> Unit
    ): String {
        return collectPromptText(
            events = chatService.streamText(
                messages = messages,
                modelConfig = model
            ),
            onDelta = onDelta
        )
    }

    private suspend fun streamCompletion(
        messages: List<ChatApiMessage>,
        model: ModelConfig,
        onContentDelta: (String) -> Unit,
        onReasoningDelta: (String) -> Unit
    ): String {
        return collectPromptText(
            events = chatService.streamText(
                messages = messages,
                modelConfig = model
            ),
            onDelta = onContentDelta,
            onReasoningDelta = onReasoningDelta
        )
    }

    private fun parse(raw: String): DesignedImagePrompt? {
        val candidate = raw.trim()
            .removePrefix("```json").removePrefix("```")
            .removeSuffix("```").trim()
            .let { text ->
                val start = text.indexOf('{')
                val end = text.lastIndexOf('}')
                if (start >= 0 && end > start) text.substring(start, end + 1) else text
            }
        return runCatching { json.decodeFromString(DesignedImagePrompt.serializer(), candidate) }
            .getOrNull()
            ?.takeIf { it.effectiveBaseCaption.isNotBlank() }
    }

    companion object {
        fun contextForAnchor(
            messages: List<ChatMessage>,
            anchorMessageId: String
        ): List<ChatMessage> {
            val msg = messages.firstOrNull { it.id == anchorMessageId }
                ?: return emptyList()
            if (msg.role == MessageRole.SYSTEM || msg.displayContent.isBlank()) return emptyList()
            return listOf(msg)
        }

        internal fun convert(card: CharacterCard, designed: DesignedImagePrompt): NovelAiPromptPlan =
            convert(designed, card.defaultImageNegativePrompt)

        internal fun convert(
            designed: DesignedImagePrompt,
            negativePrompt: String = PromptTemplates.defaultCharacterNaiNegativePrompt()
        ): NovelAiPromptPlan {
            val normalizedBase = normalizeRelationTags(designed.effectiveBaseCaption)
            val sizePreset = NovelAiImageSizePreset.from(designed.sizePreset)
            val effectiveNegativePrompt = PromptTemplates.effectiveCharacterNaiNegativePrompt(negativePrompt)
            val characters = designed.characters.take(6)
            if (characters.isEmpty()) return NovelAiPromptPlan(
                normalizedBase,
                emptyList(),
                designed,
                sizePreset,
                effectiveNegativePrompt
            )
            val captions = characters.mapIndexedNotNull { index, selected ->
                selected.effectiveCaption.trim().takeIf(String::isNotBlank)?.let {
                    NovelAiCharacterCaption(
                        prompt = it,
                        center = selected.center?.normalized()
                            ?: fallbackCenter(index, characters.size)
                    )
                }
            }
            return NovelAiPromptPlan(normalizedBase, captions, designed, sizePreset, effectiveNegativePrompt)
        }

        private fun DesignedCharacterCenter.normalized() = DesignedCharacterCenter(
            x = x.coerceIn(0.05f, 0.95f),
            y = y.coerceIn(0.05f, 0.95f)
        )

        internal fun fallbackCenter(index: Int, count: Int): DesignedCharacterCenter {
            if (count <= 1) return DesignedCharacterCenter(0.5f, 0.5f)
            return DesignedCharacterCenter(
                x = (index + 1f) / (count + 1f),
                y = 0.5f
            )
        }

        internal fun normalizeRelationTags(prompt: String): String =
            prompt.replace(
                Regex("""\b(source|target|mutual)#(?!\d+\b)[^,\s]+""", RegexOption.IGNORE_CASE)
            ) { "" }
                .split(',')
                .map(String::trim)
                .filter(String::isNotBlank)
                .joinToString(", ")

        internal fun baseCharacterName(fullName: String): String =
            fullName.split(Regex("""[/;；]""")).first().trim()

        internal fun characterCardImagePrompt(card: CharacterCard): String = buildString {
            appendLine("Design one reusable character-card cover image from this finalized card.")
            appendLine("The same image will be used as both avatar and chat background.")
            appendLine("Make it recognizable, portrait-friendly, and stable: major character identity, appearance, mood, and a light representative background.")
            appendLine("No UI, no text, no logo, no watermark. Avoid action-heavy story moments.")
            appendLine()
            appendField("Card name", card.name)
            appendField("Basic setting", card.basicSetting)
            appendField("Opening scene", card.greeting)
            if (card.editMode == CharacterEditMode.FREEFORM) {
                appendField("Freeform character text", card.freeformCharacterText)
            } else {
                card.characters.take(6).forEachIndexed { index, character ->
                    appendLine()
                    appendLine("Character ${index + 1}:")
                    appendField("Name", character.name)
                    appendField("Profile", character.profile)
                    appendField("Appearance", character.appearance)
                    appendField("Clothing", character.clothing)
                    appendField("Abilities", character.abilities)
                    appendField("Habits", character.habits)
                    appendField("Background", character.background)
                    appendField("Relationships", character.relationships)
                    appendField("Speaking style", character.speakingStyle)
                    appendField("Image prompt", character.imagePrompt)
                }
            }
        }


        private fun StringBuilder.appendField(label: String, value: String) {
            val text = value.trim().take(1200)
            if (text.isNotEmpty()) appendLine("$label: $text")
        }

        private fun buildDesignRequestJson(systemPrompt: String, userPrompt: String): String =
            buildJsonObject {
                put("messages", kotlinx.serialization.json.buildJsonArray {
                    add(buildJsonObject {
                        put("role", "system")
                        put("content", systemPrompt)
                    })
                    add(buildJsonObject {
                        put("role", "user")
                        put("content", userPrompt)
                    })
                })
            }.toString()

        private fun debugMessages(systemPrompt: String, userPrompt: String): String = buildString {
            appendLine("[system]")
            appendLine(systemPrompt)
            appendLine()
            appendLine("[user]")
            appendLine(userPrompt)
        }.trim()

        internal fun promptToolInputText(
            imageDescription: String,
            stylePrompt: String,
            characterPrompt: String
        ): String =
            listOf(imageDescription, stylePrompt, characterPrompt)
                .map(String::trim)
                .filter(String::isNotBlank)
                .joinToString("\n\n")

        private const val PROMPT_TOOL_SESSION_ID = "image-prompt-tool"
    }
}

internal fun CharacterCard.hasImageDesignSource(): Boolean =
    name.isNotBlank() ||
        basicSetting.isNotBlank() ||
        greeting.isNotBlank() ||
        freeformCharacterText.isNotBlank() ||
        characters.any {
            it.name.isNotBlank() ||
                it.profile.isNotBlank() ||
                it.appearance.isNotBlank() ||
                it.clothing.isNotBlank() ||
                it.background.isNotBlank() ||
                it.imagePrompt.isNotBlank()
        }

internal suspend fun collectPromptText(
    events: Flow<StreamEvent>,
    onDelta: (String) -> Unit,
    onReasoningDelta: ((String) -> Unit)? = null
): String {
    val content = StringBuilder()
    val reasoning = StringBuilder()
    events.collect { event ->
        when (event) {
            is StreamEvent.Delta -> {
                content.append(event.text)
                onDelta(content.toString())
            }
            is StreamEvent.ReasoningDelta -> {
                reasoning.append(event.text)
                if (onReasoningDelta != null) {
                    onReasoningDelta(reasoning.toString())
                } else {
                    onDelta("[思考] " + reasoning.toString())
                }
            }
            is StreamEvent.Error -> error(event.message)
            StreamEvent.Done -> Unit
        }
    }
    return content.toString().takeIf(String::isNotBlank)
        ?: error("对话 AI 流式生图 Prompt 返回空内容")
}
