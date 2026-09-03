package com.example.chatbar.domain.image

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterEditMode
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.chat.ImageUnderstandingResult
import com.example.chatbar.domain.chat.ImageUnderstandingService
import com.example.chatbar.domain.chat.StreamEvent
import com.example.chatbar.domain.chat.StreamingChatService
import com.example.chatbar.domain.prompt.NovelAiTagSearchEvidence
import com.example.chatbar.domain.prompt.NovelAiCodexEvidence
import com.example.chatbar.domain.prompt.PromptTemplates
import com.example.chatbar.utils.DebugLogManager
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

internal const val NOVEL_AI_SCENE_PLANNING_THINKING_BUDGET = 256
internal const val NOVEL_AI_PROMPT_DESIGN_THINKING_BUDGET = 512

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

@Serializable
data class NovelAiCharacterCaption(
    val prompt: String,
    val center: DesignedCharacterCenter,
    val negativePrompt: String = ""
)

@Serializable
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

internal object NovelAiPromptDelimiterPolicy {
    fun normalizeForRequest(prompt: NovelAiPromptPlan): NovelAiPromptPlan = prompt.copy(
        baseCaption = normalize(prompt.baseCaption),
        characterCaptions = prompt.characterCaptions.map { caption ->
            caption.copy(
                prompt = normalize(caption.prompt),
                negativePrompt = normalize(caption.negativePrompt)
            )
        },
        negativePrompt = normalize(prompt.negativePrompt)
    )

    fun normalize(text: String): String = text.replace('，', ',')
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
    private val tagResearchService: NovelAiTagResearchService,
    private val promptPostProcessor: NovelAiPromptPostProcessor = NovelAiPromptPostProcessor.disabled(),
    private val imageUnderstandingServiceProvider: () -> ImageUnderstandingService? = { null },
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true }
) {
    suspend fun design(
        messages: List<ChatMessage>,
        anchorMessageId: String,
        card: CharacterCard,
        model: ModelConfig,
        playerName: String? = null,
        sessionId: String? = null,
        imageContentHint: String = "",
        finalPromptRequirement: String = "",
        targetImageModel: NovelAiImageModel = NovelAiImageModel.V4_5_FULL,
        onDelta: (String) -> Unit = {}
    ): NovelAiPromptPlan {
        val context = contextForAnchor(messages, anchorMessageId)
        require(context.isNotEmpty()) { "没有可用于生图的聊天上下文" }
        val botName = card.effectiveBotName
        val structured = card.editMode == CharacterEditMode.STRUCTURED
        val characterPrompts = if (structured) {
            card.characters.map { baseCharacterName(it.name) to it.imagePrompt.trim() }
        } else {
            emptyList()
        }
        val requestMessages = conversationDesignMessages(
            messages = context,
            playerName = playerName,
            botName = botName,
            imageContentHint = imageContentHint,
            finalPromptRequirement = finalPromptRequirement,
            characterImagePrompts = characterPrompts,
            structured = structured,
            targetImageModel = targetImageModel
        )
        val progress = NovelAiPromptProgress(onDelta)
        val research = tagResearchService.research(
            taskInput = PromptTemplates.novelAiImagePromptConversation(
                messages = context,
                playerName = playerName,
                botName = botName,
                imageContentHint = imageContentHint,
                finalPromptRequirement = finalPromptRequirement,
                preserveUsername = true
            ),
            characterPrompts = characterPrompts,
            imageBase64s = emptyList(),
            model = model,
            diversityKey = "chat:${sessionId ?: card.id}",
            playerName = null,
            botName = botName,
            onProgress = progress::updatePrelude
        )
        val finalRequestMessages = withResearchEvidence(
            requestMessages,
            research.evidence,
            research.codexEvidence,
            research.sceneDescription
        )
        progress.updateStage(PROMPT_DESIGN_STAGE, WAITING_FOR_AI_TEXT)
        val raw = streamCompletion(
            messages = finalRequestMessages,
            model = model,
            onDelta = { text -> progress.updateStage(PROMPT_DESIGN_STAGE, text) }
        )
        sessionId?.let { sid ->
            DebugLogManager.recordCompleted(
                sessionId = sid,
                modelName = model.modelName,
                apiUrl = "${model.baseUrl.trimEnd('/')}/chat/completions",
                requestBodyJson = buildDesignRequestJson(finalRequestMessages),
                rawAiOutput = raw
            )
        }
        val designed = parseOrRepair(raw, model) { text ->
            progress.updateStage(PROMPT_REPAIR_STAGE, text)
        }
        return convert(card, promptPostProcessor.process(designed).prompt)
    }

    suspend fun designForCharacterCard(
        card: CharacterCard,
        model: ModelConfig,
        finalPromptRequirement: String = "",
        playerName: String? = null,
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
            characterImagePrompts = characterPrompts,
            structured = structured,
            playerName = playerName,
            botName = card.effectiveBotName
        )
        val userPrompt = PromptTemplates.novelAiImagePromptCharacterCard(
            card = card,
            finalPromptRequirement = finalPromptRequirement,
            playerName = playerName,
            botName = card.effectiveBotName
        )
        val requestMessages = listOf(
            ChatApiMessage.text("system", systemPrompt),
            ChatApiMessage.text("user", userPrompt)
        )
        val progress = NovelAiPromptProgress(onDelta)
        val research = tagResearchService.research(
            taskInput = userPrompt,
            characterPrompts = characterPrompts,
            imageBase64s = emptyList(),
            model = model,
            diversityKey = "card:${card.id}",
            playerName = playerName,
            botName = card.effectiveBotName,
            onProgress = progress::updatePrelude
        )
        progress.updateStage(PROMPT_DESIGN_STAGE, WAITING_FOR_AI_TEXT)
        val raw = streamCompletion(
            messages = withResearchEvidence(
                requestMessages,
                research.evidence,
                research.codexEvidence,
                research.sceneDescription
            ),
            model = model,
            onDelta = { text -> progress.updateStage(PROMPT_DESIGN_STAGE, text) }
        )
        val designed = parseOrRepair(raw, model) { text ->
            progress.updateStage(PROMPT_REPAIR_STAGE, text)
        }
        return convert(card, promptPostProcessor.process(designed).prompt)
    }

    suspend fun designForMoment(
        card: CharacterCard,
        momentImageBrief: String,
        model: ModelConfig,
        finalPromptRequirement: String = "",
        playerName: String? = null,
        targetImageModel: NovelAiImageModel = NovelAiImageModel.V4_5_FULL,
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
            characterImagePrompts = characterPrompts,
            structured = structured,
            playerName = playerName,
            botName = card.effectiveBotName,
            targetImageModel = targetImageModel
        )
        val userPrompt = PromptTemplates.novelAiImagePromptMoment(
            momentImageBrief = momentImageBrief,
            finalPromptRequirement = finalPromptRequirement,
            playerName = playerName,
            botName = card.effectiveBotName
        )
        val requestMessages = listOf(
            ChatApiMessage.text("system", systemPrompt),
            ChatApiMessage.text("user", userPrompt)
        )
        val progress = NovelAiPromptProgress(onDelta)
        val research = tagResearchService.research(
            taskInput = userPrompt,
            characterPrompts = characterPrompts,
            imageBase64s = emptyList(),
            model = model,
            diversityKey = "moment:${card.id}",
            playerName = playerName,
            botName = card.effectiveBotName,
            onProgress = progress::updatePrelude
        )
        progress.updateStage(PROMPT_DESIGN_STAGE, WAITING_FOR_AI_TEXT)
        val raw = streamCompletion(
            messages = withResearchEvidence(
                requestMessages,
                research.evidence,
                research.codexEvidence,
                research.sceneDescription
            ),
            model = model,
            onDelta = { text -> progress.updateStage(PROMPT_DESIGN_STAGE, text) }
        )
        val designed = parseOrRepair(raw, model) { text ->
            progress.updateStage(PROMPT_REPAIR_STAGE, text)
        }
        return convert(card, promptPostProcessor.process(designed).prompt)
    }

    suspend fun designForMomentDebug(
        card: CharacterCard,
        momentImageBrief: String,
        model: ModelConfig,
        finalPromptRequirement: String = "",
        playerName: String? = null,
        targetImageModel: NovelAiImageModel = NovelAiImageModel.V4_5_FULL,
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
            characterImagePrompts = characterPrompts,
            structured = structured,
            playerName = playerName,
            botName = card.effectiveBotName,
            targetImageModel = targetImageModel
        )
        val userPrompt = PromptTemplates.novelAiImagePromptMoment(
            momentImageBrief = momentImageBrief,
            finalPromptRequirement = finalPromptRequirement,
            playerName = playerName,
            botName = card.effectiveBotName
        )
        val requestMessages = listOf(
            ChatApiMessage.text("system", systemPrompt),
            ChatApiMessage.text("user", userPrompt)
        )
        val progress = NovelAiPromptProgress(onDelta)
        val research = tagResearchService.research(
            taskInput = userPrompt,
            characterPrompts = characterPrompts,
            imageBase64s = emptyList(),
            model = model,
            diversityKey = "moment:${card.id}",
            playerName = playerName,
            botName = card.effectiveBotName,
            onProgress = progress::updatePrelude
        )
        val finalRequestMessages = withResearchEvidence(
            requestMessages,
            research.evidence,
            research.codexEvidence,
            research.sceneDescription
        )
        val exchanges = mutableListOf<NovelAiPromptDebugExchange>()
        exchanges += tagResearchDebugExchanges(research)
        progress.updateStage(PROMPT_DESIGN_STAGE, WAITING_FOR_AI_TEXT)
        val raw = streamCompletion(
            messages = finalRequestMessages,
            model = model,
            onDelta = { text -> progress.updateStage(PROMPT_DESIGN_STAGE, text) }
        )
        exchanges += NovelAiPromptDebugExchange(
            title = "NovelAI Prompt 设计",
            input = debugMessages(finalRequestMessages),
            output = raw
        )
        val designed = parseOrRepairDebug(
            raw = raw,
            model = model,
            onDelta = { text -> progress.updateStage(PROMPT_REPAIR_STAGE, text) },
            exchanges = exchanges
        )
        val processed = promptPostProcessor.process(designed)
        exchanges += postProcessDebugExchange(processed)
        return NovelAiPromptDebugResult(
            plan = convert(card, processed.prompt),
            exchanges = exchanges
        )
    }

    suspend fun designForPromptTool(
        imageDescription: String,
        characterPrompt: String,
        characterImagePrompts: List<Pair<String, String>> = emptyList(),
        finalPromptRequirement: String = "",
        imageBase64s: List<String> = emptyList(),
        referenceImageProvided: Boolean = imageBase64s.any(String::isNotBlank),
        model: ModelConfig,
        playerName: String? = null,
        botName: String = "",
        targetImageModel: NovelAiImageModel = NovelAiImageModel.V4_5_FULL,
        referenceImageInstruction: String? = null,
        excludeStyle: Boolean = true,
        onContentDelta: (String) -> Unit = {},
        onReasoningDelta: (String) -> Unit = {}
    ): NovelAiPromptPlan = designForPromptToolDetailed(
        imageDescription = imageDescription,
        characterPrompt = characterPrompt,
        characterImagePrompts = characterImagePrompts,
        finalPromptRequirement = finalPromptRequirement,
        imageBase64s = imageBase64s,
        referenceImageProvided = referenceImageProvided,
        model = model,
        playerName = playerName,
        botName = botName,
        targetImageModel = targetImageModel,
        referenceImageInstruction = referenceImageInstruction,
        excludeStyle = excludeStyle,
        onContentDelta = onContentDelta,
        onReasoningDelta = onReasoningDelta
    ).plan

    suspend fun designForPromptToolDetailed(
        imageDescription: String,
        characterPrompt: String,
        characterImagePrompts: List<Pair<String, String>> = emptyList(),
        finalPromptRequirement: String = "",
        imageBase64s: List<String> = emptyList(),
        referenceImageProvided: Boolean = imageBase64s.any(String::isNotBlank),
        model: ModelConfig,
        playerName: String? = null,
        botName: String = "",
        targetImageModel: NovelAiImageModel = NovelAiImageModel.V4_5_FULL,
        referenceImageInstruction: String? = null,
        excludeStyle: Boolean = true,
        naturalLanguageMode: Boolean = false,
        onContentDelta: (String) -> Unit = {},
        onReasoningDelta: (String) -> Unit = {}
    ): NovelAiPromptToolDesignResult {
        require(!naturalLanguageMode || targetImageModel == NovelAiImageModel.V5_FULL) {
            "自然语言 Prompt 仅支持 NovelAI Diffusion V5 Full"
        }
        val sourceImages = imageBase64s.filter(String::isNotBlank)
        val imageProgress = NovelAiImageUnderstandingProgress(onContentDelta)
        val understoodImages = if (sourceImages.isEmpty()) {
            ImageUnderstandingResult()
        } else {
            imageUnderstandingServiceProvider()?.prepare(
                imageBase64s = sourceImages,
                generationModel = model,
                requireUnderstanding = true,
                announceDirect = true,
                onStatus = { status -> imageProgress.updateStatus(status) },
                onDescriptionText = { index, text -> imageProgress.updateDescription(index, text) }
            ) ?: if (model.isMultimodal) {
                imageProgress.updateStatus("当前模型支持多模态，直接使用上传图片")
                ImageUnderstandingResult(directImageBase64s = sourceImages)
            } else {
                error("当前模型不支持多模态，且未配置可用的视觉模型，无法反推图片")
            }
        }
        val directImages = understoodImages.directImageBase64s
        val request = promptToolInputText(
            imageDescription = imageDescription,
            characterPrompt = characterPrompt
        )
        require(request.isNotBlank() || sourceImages.isNotEmpty()) { "请输入图片描述、角色提示词或上传图片" }
        val systemPrompt = if (naturalLanguageMode) {
            PromptTemplates.novelAiImageNaturalLanguagePromptCoreSystem(playerName, botName)
        } else {
            PromptTemplates.novelAiImagePromptCoreSystem(playerName, botName, targetImageModel)
        }
        val scenePrompt = PromptTemplates.novelAiImagePromptConversation(
            listOf(
                ChatMessage.create(
                    sessionId = PROMPT_TOOL_SESSION_ID,
                    role = MessageRole.USER,
                    content = request
                )
            ),
            playerName = playerName,
            botName = botName,
            finalPromptRequirement = finalPromptRequirement
        )
        val userPrompt = buildString {
            append(scenePrompt)
            appendLine()
            appendLine()
            append(PromptTemplates.novelAiImageTargetModelUser(targetImageModel.displayName))
            if (understoodImages.descriptions.isNotEmpty()) {
                appendLine()
                appendLine()
                append(
                    PromptTemplates.novelAiImagePromptImageContentHintUser(
                        understoodImages.descriptions.joinToString("\n\n")
                    )
                )
            }
            if (referenceImageProvided) {
                appendLine()
                appendLine()
                append(referenceImageInstruction ?: PromptTemplates.novelAiImagePromptReferenceImageUser())
            }
        }
        val userMessage = if (directImages.isEmpty()) {
            ChatApiMessage.text("user", userPrompt)
        } else {
            ChatApiMessage.withImages("user", userPrompt, directImages)
        }
        val requestMessages = buildList {
            add(ChatApiMessage.text("system", systemPrompt))
            if (excludeStyle) {
                add(ChatApiMessage.text("system", PromptTemplates.novelAiImagePromptStyleExclusionSystem()))
            }
            if (characterImagePrompts.isNotEmpty()) {
                add(
                    ChatApiMessage.text(
                        "system",
                        PromptTemplates.novelAiImagePromptCharacterPresetSystem(
                            characterImagePrompts = characterImagePrompts,
                            structured = true,
                            playerName = playerName,
                            botName = botName
                        )
                    )
                )
            }
            add(userMessage)
        }
        val progress = NovelAiPromptProgress(
            onProgress = onContentDelta,
            initialProgress = imageProgress.snapshot()
        )
        val reasoningProgress = NovelAiPromptProgress(onReasoningDelta)
        val research = tagResearchService.research(
            taskInput = userPrompt,
            characterPrompts = characterImagePrompts,
            imageBase64s = directImages,
            model = model,
            diversityKey = PROMPT_TOOL_SESSION_ID,
            playerName = playerName,
            botName = botName,
            onProgress = progress::updatePrelude
        )
        progress.updateStage(PROMPT_DESIGN_STAGE, WAITING_FOR_AI_TEXT)
        val raw = streamCompletion(
            messages = withResearchEvidence(
                requestMessages,
                research.evidence,
                research.codexEvidence,
                research.sceneDescription,
                naturalLanguageMode = naturalLanguageMode
            ),
            model = model,
            onContentDelta = { text -> progress.updateStage(PROMPT_DESIGN_STAGE, text) },
            onReasoningDelta = { text -> reasoningProgress.updateStage(PROMPT_DESIGN_STAGE, text) }
        )
        var finalRawResponse = raw
        val designed = parseOrRepair(
            raw = raw,
            model = model,
            onContentDelta = { text -> progress.updateStage(PROMPT_REPAIR_STAGE, text) },
            onReasoningDelta = { text -> reasoningProgress.updateStage(PROMPT_REPAIR_STAGE, text) },
            repairSystemPrompt = if (naturalLanguageMode) {
                PromptTemplates.NOVELAI_IMAGE_NATURAL_LANGUAGE_PROMPT_REPAIR_SYSTEM_V5
            } else {
                PromptTemplates.NOVELAI_IMAGE_PROMPT_REPAIR_SYSTEM
            },
            onFinalResponse = { response -> finalRawResponse = response }
        )
        return NovelAiPromptToolDesignResult(
            plan = convert(
                designed = if (naturalLanguageMode) designed else promptPostProcessor.process(designed).prompt,
                maxCharacters = targetImageModel.maxCharacters
            ),
            research = NovelAiDesignResearchSnapshot.from(
                tagEvidence = research.evidence,
                codexEvidence = research.codexEvidence
            ),
            rawResponse = finalRawResponse
        )
    }

    suspend fun reviseForPromptTool(
        previousPlan: NovelAiPromptPlan,
        modificationRequest: String,
        characterPrompt: String,
        characterImagePrompts: List<Pair<String, String>> = emptyList(),
        initialResearch: NovelAiDesignResearchSnapshot,
        finalPromptRequirement: String = "",
        model: ModelConfig,
        playerName: String? = null,
        botName: String = "",
        targetImageModel: NovelAiImageModel = NovelAiImageModel.V4_5_FULL,
        naturalLanguageMode: Boolean = false,
        onContentDelta: (String) -> Unit = {},
        onReasoningDelta: (String) -> Unit = {}
    ): NovelAiPromptPlan {
        require(modificationRequest.isNotBlank()) { "请输入修改需求" }
        require(!naturalLanguageMode || targetImageModel == NovelAiImageModel.V5_FULL) {
            "自然语言 Prompt 仅支持 NovelAI Diffusion V5 Full"
        }
        val previousPromptJson = promptPlanJson(previousPlan)
        val effectiveModification = buildString {
            append(modificationRequest.trim())
            if (characterPrompt.isNotBlank()) {
                appendLine()
                appendLine()
                appendLine("工作室当前角色 Prompt 参考：")
                append(characterPrompt.trim())
            }
        }
        val researchTask = PromptTemplates.novelAiImagePromptRevisionResearchUser(
            previousPromptJson = previousPromptJson,
            modificationRequest = effectiveModification,
            finalPromptRequirement = finalPromptRequirement
        )
        val progress = NovelAiPromptProgress(onContentDelta)
        val currentResearch = tagResearchService.researchTagsOnly(
            taskInput = researchTask,
            characterPrompts = characterImagePrompts,
            model = model,
            playerName = playerName,
            botName = botName,
            onProgress = progress::updatePrelude
        )
        val requestMessages = listOf(
            ChatApiMessage.text(
                "system",
                if (naturalLanguageMode) {
                    PromptTemplates.novelAiImageNaturalLanguagePromptCoreSystem(playerName, botName)
                } else {
                    PromptTemplates.novelAiImagePromptCoreSystem(playerName, botName, targetImageModel)
                }
            ),
            ChatApiMessage.text("system", PromptTemplates.novelAiImagePromptStyleExclusionSystem()),
            ChatApiMessage.text(
                "system",
                PromptTemplates.novelAiImagePromptCharacterPresetSystem(
                    characterImagePrompts = characterImagePrompts,
                    structured = true,
                    playerName = playerName,
                    botName = botName
                )
            ),
            ChatApiMessage.text("assistant", previousPromptJson),
            ChatApiMessage.text(
                "user",
                if (naturalLanguageMode) {
                    PromptTemplates.novelAiImageNaturalLanguagePromptRevisionUser(
                        modificationRequest = effectiveModification,
                        finalPromptRequirement = finalPromptRequirement
                    )
                } else {
                    PromptTemplates.novelAiImagePromptRevisionUser(
                        modificationRequest = effectiveModification,
                        finalPromptRequirement = finalPromptRequirement,
                        targetImageModel = targetImageModel
                    )
                }
            )
        )
        val finalRequestMessages = withResearchEvidence(
            messages = requestMessages,
            tagEvidence = initialResearch.promptTagEvidence() + currentResearch.evidence,
            codexEvidence = initialResearch.promptCodexEvidence(),
            sceneDescription = currentResearch.sceneDescription,
            naturalLanguageMode = naturalLanguageMode
        )
        progress.updateStage(PROMPT_DESIGN_STAGE, WAITING_FOR_AI_TEXT)
        val raw = streamCompletion(
            messages = finalRequestMessages,
            model = model,
            onContentDelta = { text -> progress.updateStage(PROMPT_DESIGN_STAGE, text) },
            onReasoningDelta = onReasoningDelta
        )
        val designed = parseOrRepair(
            raw = raw,
            model = model,
            onContentDelta = { text -> progress.updateStage(PROMPT_REPAIR_STAGE, text) },
            onReasoningDelta = onReasoningDelta,
            repairSystemPrompt = if (naturalLanguageMode) {
                PromptTemplates.NOVELAI_IMAGE_NATURAL_LANGUAGE_PROMPT_REPAIR_SYSTEM_V5
            } else {
                PromptTemplates.NOVELAI_IMAGE_PROMPT_REPAIR_SYSTEM
            }
        )
        return convert(
            designed = if (naturalLanguageMode) designed else promptPostProcessor.process(designed).prompt,
            maxCharacters = targetImageModel.maxCharacters
        )
    }

    private suspend fun parseOrRepair(
        raw: String,
        model: ModelConfig,
        onDelta: (String) -> Unit
    ): DesignedImagePrompt {
        parse(raw)?.let { return it }
        onDelta(WAITING_FOR_AI_TEXT)
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
        onReasoningDelta: (String) -> Unit,
        repairSystemPrompt: String = PromptTemplates.NOVELAI_IMAGE_PROMPT_REPAIR_SYSTEM,
        onFinalResponse: (String) -> Unit = {}
    ): DesignedImagePrompt {
        parse(raw)?.let {
            onFinalResponse(raw)
            return it
        }
        onContentDelta(WAITING_FOR_AI_TEXT)
        val repaired = streamCompletion(
            messages = listOf(
                ChatApiMessage.text(
                    "system",
                    repairSystemPrompt
                ),
                ChatApiMessage.text("user", raw)
            ),
            model = model,
            onContentDelta = onContentDelta,
            onReasoningDelta = onReasoningDelta
        )
        val parsed = parse(repaired)
            ?: error("对话 AI 返回的生图 Prompt JSON 无法解析，原始内容: ${raw.take(500)}")
        onFinalResponse(repaired)
        return parsed
    }

    private suspend fun parseOrRepairDebug(
        raw: String,
        model: ModelConfig,
        onDelta: (String) -> Unit,
        exchanges: MutableList<NovelAiPromptDebugExchange>
    ): DesignedImagePrompt {
        parse(raw)?.let { return it }
        onDelta(WAITING_FOR_AI_TEXT)
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
                modelConfig = model,
                thinkingBudget = NOVEL_AI_PROMPT_DESIGN_THINKING_BUDGET
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
                modelConfig = model,
                thinkingBudget = NOVEL_AI_PROMPT_DESIGN_THINKING_BUDGET
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
            convert(
                designed = designed,
                negativePrompt = card.defaultImageNegativePrompt,
                stylePrompt = card.defaultImagePrompt
            )

        internal fun convert(
            designed: DesignedImagePrompt,
            negativePrompt: String = PromptTemplates.defaultCharacterNaiNegativePrompt(),
            stylePrompt: String = "",
            maxCharacters: Int = NOVEL_AI_MAX_CHARACTER_PROMPTS
        ): NovelAiPromptPlan {
            val normalizedBase = prependStylePrompt(
                stylePrompt = stylePrompt,
                baseCaption = normalizeRelationTags(designed.effectiveBaseCaption)
            )
            val sizePreset = NovelAiImageSizePreset.from(designed.sizePreset)
            val effectiveNegativePrompt = PromptTemplates.effectiveCharacterNaiNegativePrompt(negativePrompt)
            val characters = designed.characters.take(maxCharacters.coerceAtLeast(0))
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

        internal fun prependStylePrompt(stylePrompt: String, baseCaption: String): String {
            val style = stylePrompt.trim()
            val scene = baseCaption.trim()
            return when {
                style.isBlank() -> scene
                scene.isBlank() -> style
                style.endsWith(',') -> "$style $scene"
                else -> "$style, $scene"
            }
        }

        internal fun conversationDesignMessages(
            messages: List<ChatMessage>,
            playerName: String?,
            botName: String = "",
            imageContentHint: String,
            finalPromptRequirement: String,
            characterImagePrompts: List<Pair<String, String>>,
            structured: Boolean,
            targetImageModel: NovelAiImageModel = NovelAiImageModel.V4_5_FULL
        ): List<ChatApiMessage> {
            val scene = messages.singleOrNull()
                ?: error("聊天生图必须传入一条锚定消息")
            return listOf(
                ChatApiMessage.text(
                    "assistant",
                    PromptTemplates.novelAiImagePromptAssistantScene(
                        message = scene,
                        playerName = playerName,
                        botName = botName,
                        preserveUsername = true
                    )
                ),
                ChatApiMessage.text(
                    "user",
                    PromptTemplates.novelAiImagePromptImageContentHintUser(
                        imageContentHint,
                        playerName,
                        botName,
                        preserveUsername = true
                    )
                ),
                ChatApiMessage.text(
                    "system",
                    PromptTemplates.novelAiImagePromptCoreSystem(
                        playerName = null,
                        botName = botName,
                        targetImageModel = targetImageModel
                    )
                ),
                ChatApiMessage.text(
                    "system",
                    PromptTemplates.novelAiImagePromptStyleExclusionSystem()
                ),
                ChatApiMessage.text(
                    "system",
                    PromptTemplates.novelAiImagePromptCharacterPresetSystem(
                        characterImagePrompts,
                        structured,
                        null,
                        botName
                    )
                ),
                ChatApiMessage.text(
                    "user",
                    PromptTemplates.novelAiImagePromptPreferenceUser(
                        finalPromptRequirement,
                        playerName,
                        botName,
                        preserveUsername = true
                    )
                )
            )
        }

        internal fun withTagSearchEvidence(
            messages: List<ChatApiMessage>,
            evidence: List<NovelAiTagSearchEvidence>
        ): List<ChatApiMessage> = withResearchEvidence(messages, evidence, emptyList(), "")

        internal fun withResearchEvidence(
            messages: List<ChatApiMessage>,
            tagEvidence: List<NovelAiTagSearchEvidence>,
            codexEvidence: List<NovelAiCodexEvidence>,
            sceneDescription: String,
            naturalLanguageMode: Boolean = false
        ): List<ChatApiMessage> {
            if (tagEvidence.isEmpty() && codexEvidence.isEmpty() && sceneDescription.isBlank()) return messages
            val finalUserIndex = messages.indexOfLast { it.role == "user" }
            require(finalUserIndex >= 0) { "NovelAI Prompt 设计消息缺少最终 user 消息" }
            return messages.toMutableList().apply {
                var insertionIndex = finalUserIndex
                if (sceneDescription.isNotBlank()) {
                    add(
                        insertionIndex++,
                        ChatApiMessage.text(
                            "system",
                            PromptTemplates.novelAiSceneDescriptionSystem(
                                sceneDescription,
                                naturalLanguageMode
                            )
                        )
                    )
                }
                if (codexEvidence.isNotEmpty()) {
                    add(
                        insertionIndex++,
                        ChatApiMessage.text(
                            "system",
                            PromptTemplates.novelAiCodexEvidenceSystem(
                                codexEvidence,
                                naturalLanguageMode
                            )
                        )
                    )
                }
                if (tagEvidence.isNotEmpty()) {
                    add(
                        insertionIndex,
                        ChatApiMessage.text(
                            "system",
                            PromptTemplates.novelAiTagSearchEvidenceSystem(
                                tagEvidence,
                                naturalLanguageMode
                            )
                        )
                    )
                }
            }
        }

        internal fun postProcessDebugExchange(
            result: NovelAiPromptPostProcessResult
        ): NovelAiPromptDebugExchange = NovelAiPromptDebugExchange(
            title = "NovelAI Prompt 工程化后处理",
            input = "按 TagCanonicalizer → SyntaxNormalizer → PromptLinter 处理 AI 输出；锁定画风和负面提示词不进入此步骤。",
            output = buildString {
                if (result.rewrites.isEmpty()) appendLine("无自动改写")
                result.rewrites.forEach { rewrite ->
                    appendLine("- ${rewrite.location}: ${rewrite.before} → ${rewrite.after}")
                }
                if (result.issues.isNotEmpty()) {
                    appendLine("警告：")
                    result.issues.forEach { issue ->
                        appendLine("- ${issue.location}: ${issue.message}")
                    }
                }
            }.trim()
        )

        internal fun tagResearchDebugExchanges(
            research: NovelAiTagResearchResult
        ): List<NovelAiPromptDebugExchange> {
            val planningOutput = research.decisionResults
                .mapIndexed { index, result ->
                    buildString {
                        appendLine("[画面设计 ${index + 1}]")
                        append(result.displayResponse().trim())
                        if (result.failureReason.isNotBlank()) {
                            if (isNotEmpty()) appendLine()
                            append("失败：${result.failureReason}")
                        }
                    }.trim()
                }
                .joinToString("\n\n")
                .ifBlank { "无画面设计输出" }
            val searchInput = research.queryResults
                .joinToString("\n") { result ->
                    if (result.query == result.effectiveQuery) {
                        "- q=${result.query}"
                    } else {
                        "- query=${result.query} → q=${result.effectiveQuery}"
                    }
                }
                .ifBlank { "AI 未查询 Danbooru 词条库" }
            val searchOutput = research.queryResults
                .joinToString("\n\n") { result ->
                    buildString {
                        append("[${result.query}] ")
                        when {
                            result.failureReason.isNotBlank() -> append("失败：${result.failureReason}")
                            result.candidates.isEmpty() -> append("无可用候选")
                            else -> {
                                append("${result.candidates.size} 个候选")
                                if (result.fromCache) append("（缓存）")
                                result.candidates.forEach { candidate ->
                                    appendLine()
                                    append("- ${candidate.name}")
                                    candidate.translatedName.takeIf(String::isNotBlank)
                                        ?.let { append("｜$it") }
                                    append("｜${candidate.category.label}｜count=${candidate.count}")
                                }
                            }
                        }
                    }
                }
                .ifBlank { "未执行 Danbooru 词条库搜索" }
            val codexInput = buildString {
                appendLine("画面草案：${research.sceneDescription.ifBlank { "(none)" }}")
                val queries = research.decisionResults.firstOrNull()?.decision?.queries.orEmpty()
                append("检索词：")
                append(queries.joinToString("、").ifBlank { "(none)" })
            }
            val codexOutput = when {
                research.codexSearchResult.failureReason.isNotBlank() ->
                    "失败：${research.codexSearchResult.failureReason}"
                research.codexSearchResult.matches.isEmpty() -> "无可用参考"
                else -> research.codexSearchResult.matches.joinToString("\n\n") { match ->
                    buildString {
                        appendLine("[${match.entry.kind}] ${match.entry.title}｜${match.entry.category}")
                        append(match.entry.prompt)
                    }
                }
            }
            return listOf(
                NovelAiPromptDebugExchange(
                    title = "自然语言画面设计与检索规划",
                    input = debugMessages(
                        research.decisionResults.firstOrNull()
                            ?.systemPrompt
                            ?.takeIf(String::isNotBlank)
                            ?: PromptTemplates.novelAiTagSearchPlannerSystem(),
                        research.plannerRequestText
                    ),
                    output = planningOutput
                ),
                NovelAiPromptDebugExchange(
                    title = "本地 NovelAI 法典模糊召回",
                    input = codexInput,
                    output = codexOutput
                ),
                NovelAiPromptDebugExchange(
                    title = "Danbooru 词条库批量搜索",
                    input = searchInput,
                    output = searchOutput
                )
            )
        }

        private fun buildDesignRequestJson(messages: List<ChatApiMessage>): String =
            buildJsonObject {
                put("messages", kotlinx.serialization.json.buildJsonArray {
                    messages.forEach { message ->
                        add(buildJsonObject {
                            put("role", message.role)
                            put("content", message.content)
                        })
                    }
                })
            }.toString()

        private fun debugMessages(messages: List<ChatApiMessage>): String = messages
            .joinToString("\n\n") { message ->
                val content = runCatching { message.content.jsonPrimitive.content }
                    .getOrElse { message.content.toString() }
                "[${message.role}]\n$content"
            }
            .trim()

        private fun debugMessages(systemPrompt: String, userPrompt: String): String = buildString {
            appendLine("[system]")
            appendLine(systemPrompt)
            appendLine()
            appendLine("[user]")
            appendLine(userPrompt)
        }.trim()

        internal fun promptToolInputText(
            imageDescription: String,
            characterPrompt: String
        ): String =
            listOf(imageDescription, characterPrompt)
                .map(String::trim)
                .filter(String::isNotBlank)
                .joinToString("\n\n")

        private const val PROMPT_TOOL_SESSION_ID = "image-prompt-tool"
    }

    private fun promptPlanJson(plan: NovelAiPromptPlan): String {
        val designed = DesignedImagePrompt(
            baseCaption = plan.baseCaption,
            sizePreset = plan.sizePreset.name,
            characters = plan.characterCaptions.map { caption ->
                DesignedCharacterPrompt(caption = caption.prompt)
            }
        )
        return json.encodeToString(DesignedImagePrompt.serializer(), designed)
    }
}

internal class NovelAiPromptProgress(
    private val onProgress: (String) -> Unit,
    private val initialProgress: String = ""
) {
    private var prelude = ""
    private val completedStages = mutableListOf<String>()
    private var activeTitle = ""
    private var activeText = ""

    fun updatePrelude(text: String) {
        prelude = text.trimEnd()
        emit()
    }

    fun updateStage(title: String, text: String) {
        if (activeTitle.isNotBlank() && activeTitle != title) commitActive()
        activeTitle = title
        activeText = text.trimEnd()
        emit()
    }

    private fun emit() {
        onProgress(
            buildList {
                initialProgress.takeIf(String::isNotBlank)?.let(::add)
                prelude.takeIf(String::isNotBlank)?.let(::add)
                addAll(completedStages)
                if (activeTitle.isNotBlank()) add("【$activeTitle】\n$activeText")
            }.joinToString("\n\n")
        )
    }

    private fun commitActive() {
        completedStages += "【$activeTitle】\n$activeText"
        activeTitle = ""
        activeText = ""
    }
}

internal class NovelAiImageUnderstandingProgress(
    private val onProgress: (String) -> Unit
) {
    private var status = ""
    private val descriptions = linkedMapOf<Int, String>()

    fun updateStatus(text: String) {
        status = text.trimEnd()
        emit()
    }

    fun updateDescription(index: Int, text: String) {
        descriptions[index] = text.trimEnd()
        emit()
    }

    fun snapshot(): String = buildString {
        if (status.isNotBlank()) append(status)
        descriptions.toSortedMap().forEach { (index, text) ->
            if (isNotEmpty()) appendLine().appendLine()
            if (descriptions.size > 1) appendLine("图片 ${index + 1}")
            append(text)
        }
    }.trimEnd().takeIf(String::isNotBlank)?.let { "【图片理解】\n$it" }.orEmpty()

    private fun emit() {
        snapshot().takeIf(String::isNotBlank)?.let(onProgress)
    }
}

internal fun CharacterCard.hasImageDesignSource(): Boolean =
    name.isNotBlank() ||
        basicSetting.isNotBlank() ||
        greeting.isNotBlank() ||
        if (editMode == CharacterEditMode.FREEFORM) {
            freeformCharacterText.isNotBlank()
        } else {
            characters.any { it.imagePrompt.isNotBlank() }
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
            is StreamEvent.Usage,
            StreamEvent.Done -> Unit
        }
    }
    return content.toString().takeIf(String::isNotBlank)
        ?: error("对话 AI 流式生图 Prompt 返回空内容")
}

private const val PROMPT_DESIGN_STAGE = "最终 Prompt 设计"
private const val PROMPT_REPAIR_STAGE = "JSON 修复"
private const val WAITING_FOR_AI_TEXT = "等待 AI 输出…"
