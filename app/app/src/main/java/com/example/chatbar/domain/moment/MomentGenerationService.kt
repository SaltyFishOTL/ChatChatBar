package com.example.chatbar.domain.moment

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterInfo
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.MomentPost
import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.chat.StreamEvent
import com.example.chatbar.domain.chat.StreamingChatService
import com.example.chatbar.domain.image.NovelAiGenerationSettings
import com.example.chatbar.domain.image.NovelAiImageEvent
import com.example.chatbar.domain.image.NovelAiImageModel
import com.example.chatbar.domain.image.NovelAiImageService
import com.example.chatbar.domain.image.NovelAiImageSize
import com.example.chatbar.domain.image.NovelAiImageSizePolicy
import com.example.chatbar.domain.image.NovelAiImageSizePreset
import com.example.chatbar.domain.image.NovelAiImageStorage
import com.example.chatbar.domain.image.NovelAiPromptPlan
import com.example.chatbar.domain.image.NovelAiPromptDesigner
import com.example.chatbar.domain.image.toGeneratedImageMetadata
import com.example.chatbar.domain.model.hasConfiguredAuthentication
import com.example.chatbar.domain.prompt.AiTaskContext
import com.example.chatbar.domain.prompt.AiTaskKind
import com.example.chatbar.domain.prompt.AiTaskStage
import com.example.chatbar.domain.prompt.withAiTaskRun
import com.example.chatbar.domain.prompt.rethrowIfAiTaskTerminalFailure
import com.example.chatbar.domain.prompt.PromptTemplates
import com.example.chatbar.data.security.NovelAiCredentialStore
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class MomentPostDecision(
    val shouldPost: Boolean = false,
    val reason: String = ""
)

@Serializable
data class MomentDraft(
    val shouldPost: Boolean = true,
    val senderName: String = "",
    val text: String = "",
    val imageBrief: String = "",
    val isPrivate: Boolean = false,
    val likeTier: String = "normal",
    val reason: String = ""
)

sealed class MomentGenerationResult {
    data class Posted(val post: MomentPost) : MomentGenerationResult()
    data class Skipped(val reason: String) : MomentGenerationResult()
}

data class MomentDebugExchange(
    val title: String,
    val input: String,
    val output: String
)

@Serializable
data class MomentGenerationCheckpoint(
    val decision: MomentPostDecision? = null,
    val draft: MomentDraft? = null,
    val imagePrompt: NovelAiPromptPlan? = null
)

data class MomentDebugGenerationResult(
    val post: MomentPost? = null,
    val errorMessage: String? = null,
    val exchanges: List<MomentDebugExchange> = emptyList()
)

enum class MomentGenerationProgressPhase {
    JUDGING,
    WRITING,
    DESIGNING_IMAGE,
    GENERATING_IMAGE,
    SAVING,
    DONE
}

data class MomentGenerationProgress(
    val phase: MomentGenerationProgressPhase,
    val message: String,
    val streamedText: String = "",
    val progress: Float = 0f
)

class MomentGenerationService(
    private val chatService: StreamingChatService,
    private val promptDesigner: NovelAiPromptDesigner,
    private val imageService: NovelAiImageService,
    private val imageStorage: NovelAiImageStorage,
    private val novelAiCredentials: NovelAiCredentialStore,
    private val json: Json = Json { ignoreUnknownKeys = true; isLenient = true },
    private val compiledMemoryProvider: suspend (ChatSession) -> String = { it.longTermMemory }
) {
    suspend fun generate(
        card: CharacterCard,
        session: ChatSession,
        messages: List<ChatMessage>,
        latestPost: MomentPost?,
        model: ModelConfig,
        imageModel: ModelConfig?,
        novelAiImageModel: NovelAiImageModel = NovelAiImageModel.V4_5_FULL,
        scheduledAt: Long,
        finalPromptRequirement: String = "",
        playerName: String? = session.playerName,
        allowCleartextModelApi: Boolean = false,
        autoGenerateImages: Boolean = true,
        imageAspectRatio: String = "",
        resumeFrom: MomentGenerationCheckpoint? = null,
        onCheckpoint: suspend (MomentGenerationCheckpoint) -> Unit = {}
    ): MomentGenerationResult = generateInternal(
        card = card,
        session = session,
        messages = messages,
        latestPost = latestPost,
        model = model,
        imageModel = imageModel,
        novelAiImageModel = novelAiImageModel,
        scheduledAt = scheduledAt,
        finalPromptRequirement = finalPromptRequirement,
        playerName = playerName,
        allowCleartextModelApi = allowCleartextModelApi,
        autoGenerateImages = autoGenerateImages,
        imageAspectRatio = imageAspectRatio,
        resumeFrom = resumeFrom,
        onCheckpoint = onCheckpoint,
        streamText = true,
        onProgress = {}
    )

    suspend fun generateStreaming(
        card: CharacterCard,
        session: ChatSession,
        messages: List<ChatMessage>,
        latestPost: MomentPost?,
        model: ModelConfig,
        imageModel: ModelConfig?,
        novelAiImageModel: NovelAiImageModel = NovelAiImageModel.V4_5_FULL,
        scheduledAt: Long,
        finalPromptRequirement: String = "",
        playerName: String? = session.playerName,
        allowCleartextModelApi: Boolean = false,
        autoGenerateImages: Boolean = true,
        imageAspectRatio: String = "",
        resumeFrom: MomentGenerationCheckpoint? = null,
        onCheckpoint: suspend (MomentGenerationCheckpoint) -> Unit = {},
        onProgress: (MomentGenerationProgress) -> Unit
    ): MomentGenerationResult = generateInternal(
        card = card,
        session = session,
        messages = messages,
        latestPost = latestPost,
        model = model,
        imageModel = imageModel,
        novelAiImageModel = novelAiImageModel,
        scheduledAt = scheduledAt,
        finalPromptRequirement = finalPromptRequirement,
        playerName = playerName,
        allowCleartextModelApi = allowCleartextModelApi,
        autoGenerateImages = autoGenerateImages,
        imageAspectRatio = imageAspectRatio,
        resumeFrom = resumeFrom,
        onCheckpoint = onCheckpoint,
        streamText = true,
        onProgress = onProgress
    )

    private suspend fun generateInternal(
        card: CharacterCard,
        session: ChatSession,
        messages: List<ChatMessage>,
        latestPost: MomentPost?,
        model: ModelConfig,
        imageModel: ModelConfig?,
        novelAiImageModel: NovelAiImageModel,
        scheduledAt: Long,
        finalPromptRequirement: String,
        playerName: String?,
        allowCleartextModelApi: Boolean,
        autoGenerateImages: Boolean,
        imageAspectRatio: String,
        resumeFrom: MomentGenerationCheckpoint?,
        onCheckpoint: suspend (MomentGenerationCheckpoint) -> Unit,
        streamText: Boolean,
        onProgress: (MomentGenerationProgress) -> Unit
    ): MomentGenerationResult = withAiTaskRun() {
        val contextMessages = messages
            .filter { it.role != MessageRole.SYSTEM && it.displayContent.isNotBlank() }
            .takeLast(3)
        if (contextMessages.isEmpty()) return@withAiTaskRun MomentGenerationResult.Skipped("没有可用于朋友圈生成的交流内容")
        val promptSession = session.copy(longTermMemory = compiledMemoryProvider(session))
        val token = novelAiCredentials.load()
        val imageCapable = autoGenerateImages &&
            token != null &&
            imageModel != null &&
            imageModel.hasConfiguredAuthentication(allowCleartextModelApi)
        val textOnlyPost = !imageCapable
        if (imageCapable) {
            NovelAiImageSizePolicy.validationError(imageAspectRatio)?.let { error(it) }
        }

        var checkpoint = resumeFrom ?: MomentGenerationCheckpoint()
        val draft = checkpoint.draft ?: run {
            onProgress(MomentGenerationProgress(MomentGenerationProgressPhase.WRITING, "正在生成朋友圈文案"))
            designMoment(card, promptSession, contextMessages, latestPost, model, textOnlyPost, streamText, onProgress).also {
                checkpoint = checkpoint.copy(draft = it)
                onCheckpoint(checkpoint)
            }
        }
        val maxTextLength = if (textOnlyPost) TEXT_ONLY_MOMENT_MAX_LENGTH else IMAGE_MOMENT_MAX_LENGTH
        val text = draft.text.compactMomentText(maxTextLength)
        if (text.isBlank()) error("AI 未生成朋友圈文案")
        val normalizedDraft = draft.copy(text = text)
        if (textOnlyPost) {
            val post = createPost(
                card,
                session,
                normalizedDraft,
                prompt = null,
                imagePath = null,
                imageSize = null,
                scheduledAt = scheduledAt
            )
            onProgress(MomentGenerationProgress(MomentGenerationProgressPhase.DONE, "已生成纯文字朋友圈", progress = 1f))
            return@withAiTaskRun MomentGenerationResult.Posted(post)
        }
        val prompt = checkpoint.imagePrompt ?: run {
            onProgress(MomentGenerationProgress(MomentGenerationProgressPhase.DESIGNING_IMAGE, "正在设计图片提示词"))
            promptDesigner.designForMoment(
                card = card,
                momentImageBrief = normalizedDraft.imageBrief,
                model = imageModel,
                finalPromptRequirement = finalPromptRequirement,
                playerName = playerName,
                targetImageModel = novelAiImageModel,
                onDelta = { transcript ->
                    onProgress(
                        MomentGenerationProgress(
                            MomentGenerationProgressPhase.DESIGNING_IMAGE,
                            transcript
                        )
                    )
                }
            ).also {
                checkpoint = checkpoint.copy(imagePrompt = it)
                onCheckpoint(checkpoint)
            }
        }
        val imageSize = NovelAiImageSizePolicy.resolve(imageAspectRatio, NovelAiImageSizePreset.SQUARE)
        val bytes = generateImageWithRetry(token, prompt, imageSize, novelAiImageModel, card.defaultImageGenerationSettings, onProgress)
        onProgress(MomentGenerationProgress(MomentGenerationProgressPhase.SAVING, "正在保存图片", progress = 1f))
        val imagePath = imageStorage.save("moments_${card.id}", bytes)
        val post = createPost(card, session, normalizedDraft, prompt, imagePath, imageSize, scheduledAt)
        onProgress(MomentGenerationProgress(MomentGenerationProgressPhase.DONE, "朋友圈生成完成", progress = 1f))
        return@withAiTaskRun MomentGenerationResult.Posted(post)
    }

    private suspend fun generateImageWithRetry(
        token: String,
        prompt: NovelAiPromptPlan,
        imageSize: NovelAiImageSize,
        novelAiImageModel: NovelAiImageModel,
        cardSettings: com.example.chatbar.domain.image.NovelAiCharacterImageSettings?,
        onProgress: (MomentGenerationProgress) -> Unit
    ): ByteArray {
        var lastError = "NovelAI 未返回最终图片"
        repeat(IMAGE_GENERATION_ATTEMPTS) { attempt ->
            val retryLabel = if (attempt == 0) "" else "（重试 ${attempt + 1}/$IMAGE_GENERATION_ATTEMPTS）"
            var finalImage: ByteArray? = null
            var errorMessage: String? = null
            onProgress(
                MomentGenerationProgress(
                    MomentGenerationProgressPhase.GENERATING_IMAGE,
                    "正在生成图片$retryLabel"
                )
            )
            val seed = imageService.newSeed()
            imageService.generate(
                token = token,
                prompt = prompt,
                imageSize = imageSize,
                settings = NovelAiGenerationSettings.legacy(seed, model = novelAiImageModel).let { cardSettings?.applyTo(it) ?: it }
            ).collect { event ->
                when (event) {
                    is NovelAiImageEvent.Final -> finalImage = event.image
                    is NovelAiImageEvent.Error -> errorMessage = event.message
                    is NovelAiImageEvent.Intermediate -> {
                        onProgress(
                            MomentGenerationProgress(
                                phase = MomentGenerationProgressPhase.GENERATING_IMAGE,
                                message = "图片生成 ${(event.progress * 100).toInt()}%$retryLabel",
                                progress = event.progress
                            )
                        )
                    }
                }
            }
            finalImage?.let { return it }
            lastError = errorMessage ?: "NovelAI 未返回最终图片"
            if (!lastError.isTransientMomentImageFailure() || attempt == IMAGE_GENERATION_ATTEMPTS - 1) {
                error(lastError)
            }
            delay(IMAGE_RETRY_DELAY_MS)
        }
        error(lastError)
    }

    suspend fun debugGenerateNow(
        card: CharacterCard,
        session: ChatSession,
        messages: List<ChatMessage>,
        latestPost: MomentPost?,
        model: ModelConfig,
        imageModel: ModelConfig?,
        novelAiImageModel: NovelAiImageModel = NovelAiImageModel.V4_5_FULL,
        scheduledAt: Long = System.currentTimeMillis(),
        finalPromptRequirement: String = "",
        playerName: String? = session.playerName,
        allowCleartextModelApi: Boolean = false,
        imageAspectRatio: String = ""
    ): MomentDebugGenerationResult = withAiTaskRun() {
        val exchanges = mutableListOf<MomentDebugExchange>()
        return@withAiTaskRun runCatching {
            val contextMessages = messages
                .filter { it.role != MessageRole.SYSTEM && it.displayContent.isNotBlank() }
                .takeLast(3)
            require(contextMessages.isNotEmpty()) { "没有可用于朋友圈生成的交流内容" }
            val promptSession = session.copy(longTermMemory = compiledMemoryProvider(session))

            val draft = designMomentDebug(card, promptSession, contextMessages, latestPost, model, exchanges)
            val text = draft.text.compactMomentText(IMAGE_MOMENT_MAX_LENGTH)
            require(text.isNotBlank()) { "AI 未生成朋友圈文案" }
            val normalizedDraft = draft.copy(text = text)
            val token = novelAiCredentials.load()
            if (token == null) {
                exchanges += MomentDebugExchange(
                    title = "NovelAI 生图",
                    input = "NovelAI Token 未配置",
                    output = "跳过图片生成，保存无图朋友圈。"
                )
                val post = createPost(
                    card,
                    session,
                    normalizedDraft,
                    prompt = null,
                    imagePath = null,
                    imageSize = null,
                    scheduledAt = scheduledAt
                )
                return@runCatching MomentDebugGenerationResult(post = post, exchanges = exchanges)
            }
            require(imageModel != null && imageModel.hasConfiguredAuthentication(allowCleartextModelApi)) {
                "默认生图模型/API Key 未配置"
            }
            val promptDebug = promptDesigner.designForMomentDebug(
                card = card,
                momentImageBrief = normalizedDraft.imageBrief,
                model = imageModel,
                finalPromptRequirement = finalPromptRequirement,
                playerName = playerName,
                targetImageModel = novelAiImageModel
            )
            exchanges += promptDebug.exchanges.map { exchange ->
                MomentDebugExchange(
                    title = exchange.title,
                    input = exchange.input,
                    output = exchange.output
                )
            }
            val prompt = promptDebug.plan
            NovelAiImageSizePolicy.validationError(imageAspectRatio)?.let { error(it) }
            val imageSize = NovelAiImageSizePolicy.resolve(imageAspectRatio, NovelAiImageSizePreset.SQUARE)
            val seed = imageService.newSeed()
            val generationSettings = NovelAiGenerationSettings.legacy(seed, model = novelAiImageModel).let { card.defaultImageGenerationSettings?.applyTo(it) ?: it }
            val imageInput = imageService.buildRequestBody(prompt, imageSize, generationSettings)
            var finalImage: ByteArray? = null
            var errorMessage: String? = null
            val imageOutput = StringBuilder()
            imageService.generate(token, prompt, imageSize, generationSettings).collect { event ->
                when (event) {
                    is NovelAiImageEvent.Final -> {
                        finalImage = event.image
                        imageOutput.appendLine("final: ${event.image.size} bytes")
                    }
                    is NovelAiImageEvent.Error -> {
                        errorMessage = event.message
                        imageOutput.appendLine("error: ${event.message}")
                    }
                    is NovelAiImageEvent.Intermediate -> {
                        imageOutput.appendLine("intermediate step=${event.step}, progress=${event.progress}, bytes=${event.image.size}")
                    }
                }
            }
            val imageBytes = finalImage
            val imagePath = if (errorMessage == null && imageBytes != null) {
                imageStorage.save("moments_${card.id}", imageBytes)
                    .also { imageOutput.appendLine("savedPath: $it") }
            } else {
                null
            }
            exchanges += MomentDebugExchange(
                title = "NovelAI 生图",
                input = imageInput,
                output = imageOutput.toString().trim()
            )
            errorMessage?.let { error(it) }
            val savedImagePath = imagePath ?: error("NovelAI 未返回最终图片")
            val post = createPost(card, session, normalizedDraft, prompt, savedImagePath, imageSize, scheduledAt)
            MomentDebugGenerationResult(post = post, exchanges = exchanges)
        }.getOrElse { error ->
            MomentDebugGenerationResult(
                errorMessage = error.message ?: error.javaClass.simpleName,
                exchanges = exchanges
            )
        }
    }

    private suspend fun judgeMoment(
        session: ChatSession,
        latestPost: MomentPost?,
        latestMessage: ChatMessage?,
        model: ModelConfig,
        streamText: Boolean = false,
        onProgress: (MomentGenerationProgress) -> Unit = {}
    ): MomentPostDecision {
        val systemPrompt = PromptTemplates.momentJudgeSystemPrompt()
        val userPrompt = PromptTemplates.momentJudgeUserPrompt(session, latestPost, latestMessage)
        val raw = completeMomentText(
            taskContext = AiTaskContext(AiTaskKind.MOMENT_JUDGE, AiTaskStage.JUDGE),
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            model = model,
            streamText = streamText,
            phase = MomentGenerationProgressPhase.JUDGING,
            message = "正在判断是否适合发布",
            onProgress = onProgress
        )
        return parseDecision(raw)
            ?: error("朋友圈判断 AI 返回 JSON 无法解析: ${raw.take(500)}")
    }

    private suspend fun judgeMomentDebug(
        session: ChatSession,
        latestPost: MomentPost?,
        latestMessage: ChatMessage?,
        model: ModelConfig,
        exchanges: MutableList<MomentDebugExchange>
    ): MomentPostDecision {
        val systemPrompt = PromptTemplates.momentJudgeSystemPrompt()
        val userPrompt = PromptTemplates.momentJudgeUserPrompt(session, latestPost, latestMessage)
        val raw = chatService.completeText(
            taskContext = AiTaskContext(AiTaskKind.MOMENT_JUDGE, AiTaskStage.JUDGE),
            messages = listOf(
                ChatApiMessage.text("system", systemPrompt),
                ChatApiMessage.text("user", userPrompt)
            ),
            modelConfig = model
        )
        exchanges += MomentDebugExchange(
            title = "朋友圈判定",
            input = debugMessages(systemPrompt, userPrompt),
            output = raw
        )
        return parseDecision(raw)
            ?: error("朋友圈判断 AI 返回 JSON 无法解析: ${raw.take(500)}")
    }

    private suspend fun designMoment(
        card: CharacterCard,
        session: ChatSession,
        messages: List<ChatMessage>,
        latestPost: MomentPost?,
        model: ModelConfig,
        textOnlyPost: Boolean,
        streamText: Boolean = false,
        onProgress: (MomentGenerationProgress) -> Unit = {}
    ): MomentDraft {
        val systemPrompt = if (textOnlyPost) {
            PromptTemplates.momentGenerationTextSystemPrompt()
        } else {
            PromptTemplates.momentGenerationSystemPrompt()
        }
        val userPrompt = PromptTemplates.momentGenerationUserPrompt(card, session, messages, latestPost)
        val raw = completeMomentText(
            taskContext = AiTaskContext(AiTaskKind.MOMENT_GENERATION),
            systemPrompt = systemPrompt,
            userPrompt = userPrompt,
            model = model,
            streamText = streamText,
            phase = MomentGenerationProgressPhase.WRITING,
            message = "正在生成朋友圈文案",
            onProgress = onProgress
        )
        return parseDraft(raw)
            ?: error("朋友圈 AI 返回 JSON 无法解析: ${raw.take(500)}")
    }

    private suspend fun completeMomentText(
        taskContext: AiTaskContext,
        systemPrompt: String,
        userPrompt: String,
        model: ModelConfig,
        streamText: Boolean,
        phase: MomentGenerationProgressPhase,
        message: String,
        onProgress: (MomentGenerationProgress) -> Unit
    ): String {
        val request = listOf(
            ChatApiMessage.text("system", systemPrompt),
            ChatApiMessage.text("user", userPrompt)
        )
        if (!streamText) {
            return chatService.completeText(messages = request, modelConfig = model, taskContext = taskContext)
        }
        val streamed = StringBuilder()
        var errorMessage: Throwable? = null
        chatService.streamText(messages = request, modelConfig = model, taskContext = taskContext).collect { event ->
            when (event) {
                is StreamEvent.Delta -> {
                    streamed.append(event.text)
                    onProgress(
                        MomentGenerationProgress(
                            phase = phase,
                            message = message,
                            streamedText = streamed.toString()
                        )
                    )
                }
                is StreamEvent.Error -> errorMessage = event.asException()
                StreamEvent.Done,
                is StreamEvent.Usage,
                is StreamEvent.ReasoningDelta -> Unit
            }
        }
        errorMessage?.let { throw it }
        return streamed.toString()
    }

    private suspend fun designMomentDebug(
        card: CharacterCard,
        session: ChatSession,
        messages: List<ChatMessage>,
        latestPost: MomentPost?,
        model: ModelConfig,
        exchanges: MutableList<MomentDebugExchange>
    ): MomentDraft {
        val systemPrompt = PromptTemplates.momentGenerationSystemPrompt()
        val userPrompt = PromptTemplates.momentGenerationUserPrompt(card, session, messages, latestPost)
        val raw = chatService.completeText(
            taskContext = AiTaskContext(AiTaskKind.MOMENT_GENERATION),
            messages = listOf(
                ChatApiMessage.text("system", systemPrompt),
                ChatApiMessage.text("user", userPrompt)
            ),
            modelConfig = model
        )
        exchanges += MomentDebugExchange(
            title = "朋友圈生成",
            input = debugMessages(systemPrompt, userPrompt),
            output = raw
        )
        return parseDraft(raw)
            ?: error("朋友圈 AI 返回 JSON 无法解析: ${raw.take(500)}")
    }

    private fun parseDecision(raw: String): MomentPostDecision? {
        val candidate = raw.jsonCandidate()
        return runCatching { json.decodeFromString(MomentPostDecision.serializer(), candidate) }.getOrNull()
    }

    private fun parseDraft(raw: String): MomentDraft? {
        val candidate = raw.jsonCandidate()
        return runCatching { json.decodeFromString(MomentDraft.serializer(), candidate) }.getOrNull()
    }

    private fun String.jsonCandidate(): String =
        trim()
            .removePrefix("```json")
            .removePrefix("```")
            .removeSuffix("```")
            .trim()
            .let { text ->
                val start = text.indexOf('{')
                val end = text.lastIndexOf('}')
                if (start >= 0 && end > start) text.substring(start, end + 1) else text
            }

    private fun selectSender(card: CharacterCard, senderName: String): CharacterInfo? {
        val normalized = senderName.trim()
        return card.characters.firstOrNull { it.name.equals(normalized, ignoreCase = true) }
            ?: card.characters.firstOrNull { normalized.isNotBlank() && it.name.contains(normalized, ignoreCase = true) }
            ?: card.characters.firstOrNull()
    }

    private fun createPost(
        card: CharacterCard,
        session: ChatSession,
        draft: MomentDraft,
        prompt: NovelAiPromptPlan?,
        imagePath: String?,
        imageSize: NovelAiImageSize?,
        scheduledAt: Long
    ): MomentPost {
        val sender = selectSender(card, draft.senderName)
        val baseLikes = MomentPolicy.likeCount(
            tier = draft.likeTier,
            seed = "${card.id}:${scheduledAt}:${draft.senderName}:${draft.text}",
            isPrivate = draft.isPrivate
        )
        val now = System.currentTimeMillis()
        return MomentPost.create(
            characterCardId = card.id,
            sessionId = session.id,
            senderCharacterId = sender?.id,
            senderName = sender?.name?.takeIf(String::isNotBlank) ?: draft.senderName.ifBlank { card.name },
            senderAvatar = sender?.appearanceImage?.takeIf(String::isNotBlank)
                ?: card.avatar?.takeIf(String::isNotBlank),
            text = draft.text,
            imagePath = imagePath,
            imagePrompt = prompt?.baseCaption.orEmpty(),
            generatedImageMetadata = if (prompt != null && imagePath != null && imageSize != null) {
                prompt.toGeneratedImageMetadata(imagePath, imageSize)
            } else {
                null
            },
            imageBrief = draft.imageBrief,
            isPrivate = draft.isPrivate,
            baseLikeCount = baseLikes,
            generationReason = draft.reason,
            scheduledAt = scheduledAt,
            generatedAt = now
        )
    }

    private fun debugMessages(systemPrompt: String, userPrompt: String): String = buildString {
        appendLine("[system]")
        appendLine(systemPrompt)
        appendLine()
        appendLine("[user]")
        appendLine(userPrompt)
    }.trim()

    private companion object {
        const val IMAGE_GENERATION_ATTEMPTS = 2
        const val IMAGE_RETRY_DELAY_MS = 1500L
        const val IMAGE_MOMENT_MAX_LENGTH = 60
        const val TEXT_ONLY_MOMENT_MAX_LENGTH = 150
    }

    fun encodeCheckpoint(checkpoint: MomentGenerationCheckpoint): String =
        json.encodeToString(MomentGenerationCheckpoint.serializer(), checkpoint)

    fun decodeCheckpoint(value: String): MomentGenerationCheckpoint? =
        value.takeIf(String::isNotBlank)?.let {
            runCatching { json.decodeFromString(MomentGenerationCheckpoint.serializer(), it) }.getOrNull()
        }
}

private fun String.compactMomentText(maxLength: Int): String =
    replace(Regex("\\s+"), " ")
        .trim()
        .take(maxLength)

internal fun String.isTransientMomentImageFailure(): Boolean {
    val text = lowercase()
    return listOf(
        "timeout",
        "timed out",
        "超时",
        "断开",
        "eof",
        "reset",
        "无法连接",
        "网关",
        "暂不可用",
        "未返回最终图片"
    ).any { it in text }
}
