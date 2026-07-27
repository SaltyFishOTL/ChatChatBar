package com.example.chatbar.domain.voice

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.chat.StreamEvent
import com.example.chatbar.domain.chat.StreamingChatService
import com.example.chatbar.domain.prompt.PromptTemplates
import kotlinx.coroutines.flow.collect
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

data class VoiceTagInput(
    val id: String,
    val text: String,
    val characterName: String,
    val speakingStyle: String
)

data class VoiceTagBatchResult(
    val taggedTextById: Map<String, String>,
    val confirmationRequiredById: Map<String, String>,
    val errorsById: Map<String, String>,
    val rawOutput: String
)

data class VoiceTranslationBatchResult(
    val translatedTextById: Map<String, String>,
    val errorsById: Map<String, String>,
    val rawOutput: String
)

class FishAudioTagService(
    private val chatService: StreamingChatService,
    private val json: Json = Json { ignoreUnknownKeys = true }
) {
    private val strictResponseJson = Json { ignoreUnknownKeys = false }

    suspend fun translate(
        modelConfig: ModelConfig,
        targetLanguage: String,
        previousUserMessage: String,
        assistantResponse: String,
        inputs: List<VoiceTagInput>,
        onDelta: (String) -> Unit = {}
    ): VoiceTranslationBatchResult {
        require(targetLanguage.isNotBlank()) { "语音使用语言不能为空" }
        require(inputs.isNotEmpty()) { "没有待翻译的语音段落" }
        val segmentsJson = json.encodeToString(
            inputs.map {
                VoiceTranslationPromptItem(
                    id = it.id,
                    text = it.text,
                    characterName = it.characterName
                )
            }
        )
        val userInput = PromptTemplates.fishAudioTranslationUserInput(
            targetLanguage = targetLanguage,
            previousUserMessage = previousUserMessage,
            assistantResponse = assistantResponse,
            segmentsJson = segmentsJson
        )
        val raw = StringBuilder()
        var failure: String? = null
        chatService.streamText(
            messages = listOf(
                ChatApiMessage.text("system", PromptTemplates.FISH_AUDIO_TRANSLATION_SYSTEM.trim()),
                ChatApiMessage.text("user", userInput)
            ),
            modelConfig = modelConfig,
            maxTokens = 2_000,
            disableThinking = true
        ).collect { event ->
            when (event) {
                is StreamEvent.Delta -> {
                    raw.append(event.text)
                    onDelta(event.text)
                }
                is StreamEvent.Error -> failure = event.message
                else -> Unit
            }
        }
        failure?.let { error ->
            return VoiceTranslationBatchResult(
                translatedTextById = emptyMap(),
                errorsById = inputs.associate { it.id to error },
                rawOutput = raw.toString()
            )
        }
        return parseTranslation(raw.toString(), inputs)
    }

    suspend fun generate(
        modelConfig: ModelConfig,
        fishModelId: String,
        previousUserMessage: String,
        assistantResponse: String,
        inputs: List<VoiceTagInput>,
        onDelta: (String) -> Unit = {}
    ): VoiceTagBatchResult {
        require(inputs.isNotEmpty()) { "没有待添加标签的语音段落" }
        val mode = FishAudioTagPolicy.markerMode(fishModelId)
        val segmentsJson = json.encodeToString(
            inputs.map {
                VoiceTagPromptItem(
                    id = it.id,
                    text = it.text,
                    characterName = it.characterName,
                    speakingStyle = it.speakingStyle
                )
            }
        )
        val userInput = PromptTemplates.fishAudioVoiceTagUserInput(
            fishModelId = fishModelId,
            markerMode = mode.description,
            tagPolicy = FishAudioTagPolicy.promptPolicy(fishModelId),
            previousUserMessage = previousUserMessage,
            assistantResponse = assistantResponse,
            segmentsJson = segmentsJson
        )
        val raw = StringBuilder()
        var failure: String? = null
        chatService.streamText(
            messages = listOf(
                ChatApiMessage.text("system", PromptTemplates.FISH_AUDIO_VOICE_TAG_SYSTEM.trim()),
                ChatApiMessage.text("user", userInput)
            ),
            modelConfig = modelConfig,
            maxTokens = 2_000,
            disableThinking = true
        ).collect { event ->
            when (event) {
                is StreamEvent.Delta -> {
                    raw.append(event.text)
                    onDelta(event.text)
                }
                is StreamEvent.Error -> failure = event.message
                else -> Unit
            }
        }
        failure?.let { error ->
            return VoiceTagBatchResult(
                taggedTextById = emptyMap(),
                confirmationRequiredById = emptyMap(),
                errorsById = inputs.associate { it.id to error },
                rawOutput = raw.toString()
            )
        }
        return parseAndValidate(raw.toString(), inputs, mode)
    }

    fun parseTranslation(
        rawOutput: String,
        inputs: List<VoiceTagInput>
    ): VoiceTranslationBatchResult {
        val parsed = runCatching {
            strictResponseJson.decodeFromString(
                VoiceTranslationResponse.serializer(),
                rawOutput.trim()
            )
        }.getOrElse { error ->
            return VoiceTranslationBatchResult(
                translatedTextById = emptyMap(),
                errorsById = inputs.associate {
                    it.id to "翻译模型 JSON 解析失败：${error.message}"
                },
                rawOutput = rawOutput
            )
        }
        val duplicateIds = parsed.segments.groupingBy(VoiceTranslationResponseItem::id)
            .eachCount()
            .filterValues { it != 1 }
            .keys
        val inputById = inputs.associateBy(VoiceTagInput::id)
        val translated = linkedMapOf<String, String>()
        val errors = linkedMapOf<String, String>()
        inputs.forEach { input ->
            if (input.id in duplicateIds) {
                errors[input.id] = "翻译模型重复返回段落 ID"
                return@forEach
            }
            val result = parsed.segments.singleOrNull { it.id == input.id }
            if (result == null) {
                errors[input.id] = "翻译模型缺少段落结果"
                return@forEach
            }
            val text = result.translatedText.trim()
            if (text.isEmpty()) {
                errors[input.id] = "翻译模型返回空译文"
            } else {
                translated[input.id] = text
            }
        }
        parsed.segments.filterNot { it.id in inputById }.forEach {
            errors[it.id] = "翻译模型返回未知段落 ID"
        }
        return VoiceTranslationBatchResult(translated, errors, rawOutput)
    }

    fun parseAndValidate(
        rawOutput: String,
        inputs: List<VoiceTagInput>,
        mode: FishAudioMarkerMode
    ): VoiceTagBatchResult {
        val normalizedJson = rawOutput.trim()
        val parsed = runCatching {
            strictResponseJson.decodeFromString(VoiceTagResponse.serializer(), normalizedJson)
        }.getOrElse { error ->
            return VoiceTagBatchResult(
                taggedTextById = emptyMap(),
                confirmationRequiredById = emptyMap(),
                errorsById = inputs.associate { it.id to "标签模型 JSON 解析失败：${error.message}" },
                rawOutput = rawOutput
            )
        }
        val duplicateIds = parsed.segments.groupingBy(VoiceTagResponseItem::id).eachCount()
            .filterValues { it != 1 }
            .keys
        val inputById = inputs.associateBy(VoiceTagInput::id)
        val valid = linkedMapOf<String, String>()
        val confirmationRequired = linkedMapOf<String, String>()
        val errors = linkedMapOf<String, String>()
        inputs.forEach { input ->
            if (input.id in duplicateIds) {
                errors[input.id] = "标签模型重复返回段落 ID"
                return@forEach
            }
            val result = parsed.segments.singleOrNull { it.id == input.id }
            if (result == null) {
                errors[input.id] = "标签模型缺少段落结果"
                return@forEach
            }
            FishAudioTagPolicy.analyze(input.text, result.ttsText, mode)
                .onSuccess { analysis ->
                    if (analysis.spokenTextMatches) {
                        valid[input.id] = result.ttsText.trim()
                    } else {
                        confirmationRequired[input.id] = result.ttsText.trim()
                    }
                }
                .onFailure { errors[input.id] = it.message ?: "标签文本校验失败" }
        }
        parsed.segments.filterNot { it.id in inputById }.forEach {
            errors[it.id] = "标签模型返回未知段落 ID"
        }
        return VoiceTagBatchResult(valid, confirmationRequired, errors, rawOutput)
    }

    @Serializable
    private data class VoiceTagPromptItem(
        val id: String,
        val text: String,
        @SerialName("character_name")
        val characterName: String,
        @SerialName("speaking_style")
        val speakingStyle: String
    )

    @Serializable
    private data class VoiceTranslationPromptItem(
        val id: String,
        val text: String,
        @SerialName("character_name")
        val characterName: String
    )

    @Serializable
    private data class VoiceTranslationResponse(
        val segments: List<VoiceTranslationResponseItem> = emptyList()
    )

    @Serializable
    private data class VoiceTranslationResponseItem(
        val id: String,
        val translatedText: String
    )

    @Serializable
    private data class VoiceTagResponse(
        val segments: List<VoiceTagResponseItem> = emptyList()
    )

    @Serializable
    private data class VoiceTagResponseItem(
        val id: String,
        val ttsText: String
    )
}

enum class FishAudioMarkerMode(val description: String) {
    SQUARE("方括号 [tag]"),
    PARENTHESIS("圆括号 (tag)")
}

object FishAudioTagPolicy {
    data class Analysis(
        val spokenTextMatches: Boolean
    )

    private val s1FixedTags = setOf(
        "happy", "sad", "angry", "excited", "calm", "nervous", "confident",
        "surprised", "satisfied", "delighted", "scared", "worried", "upset",
        "frustrated", "depressed", "empathetic", "embarrassed", "disgusted",
        "moved", "proud", "relaxed", "grateful", "curious", "sarcastic",
        "disdainful", "unhappy", "anxious", "hysterical", "indifferent",
        "uncertain", "doubtful", "confused", "disappointed", "regretful",
        "guilty", "ashamed", "jealous", "envious", "hopeful",
        "optimistic", "pessimistic", "nostalgic", "lonely", "bored",
        "contemptuous", "sympathetic", "compassionate", "determined", "resigned",
        "in a hurry tone", "shouting", "screaming", "whispering", "soft tone",
        "laughing", "chuckling", "sobbing", "crying loudly", "sighing",
        "groaning", "panting", "gasping", "yawning", "snoring",
        "audience laughing", "background laughter", "crowd laughing",
        "break", "long-break"
    )
    private val s1MarkerPattern = Regex(
        "\\((${s1FixedTags.sortedByDescending(String::length).joinToString("|") { Regex.escape(it) }})\\)",
        RegexOption.IGNORE_CASE
    )
    private val s2RecommendedTags = listOf(
        "happy", "sad", "angry", "excited", "calm", "nervous", "confident",
        "surprised", "empathetic", "curious", "mysterious", "whispering",
        "shouting", "sighing", "laughing", "narrator", "urgent", "encouraging"
    )

    fun markerMode(fishModelId: String): FishAudioMarkerMode =
        if (fishModelId == FishAudioTtsModels.S1) {
            FishAudioMarkerMode.PARENTHESIS
        } else {
            FishAudioMarkerMode.SQUARE
        }

    fun promptPolicy(fishModelId: String): String =
        if (fishModelId == FishAudioTtsModels.S1) {
            "S1 固定标签，只能从以下列表选择：${s1FixedTags.sorted().joinToString(",")}"
        } else {
            "S2 支持方括号内简短自然语言 cue；单个 cue 不超过 40 个字符，" +
                "每句最多 3 个。优先使用官方示例：${s2RecommendedTags.joinToString(",")}"
        }

    fun validate(
        originalText: String,
        taggedText: String,
        mode: FishAudioMarkerMode
    ): Result<Unit> = analyze(originalText, taggedText, mode).mapCatching { analysis ->
        require(analysis.spokenTextMatches) {
            "标签模型改写了口播文字"
        }
    }

    fun analyze(
        originalText: String,
        taggedText: String,
        mode: FishAudioMarkerMode
    ): Result<Analysis> = runCatching {
        val markerPattern = when (mode) {
            FishAudioMarkerMode.SQUARE -> Regex("\\[([^\\[\\]\\r\\n]{1,40})]")
            FishAudioMarkerMode.PARENTHESIS -> s1MarkerPattern
        }
        val markers = markerPattern.findAll(taggedText).toList()
        markers.forEach { match ->
            val tag = match.groupValues[1].trim().lowercase()
            require(tag.isNotEmpty()) { "Fish Audio 标签不能为空" }
            if (mode == FishAudioMarkerMode.PARENTHESIS) {
                require(tag in s1FixedTags) { "包含 S1 不支持的固定标签：$tag" }
            }
        }
        val stripped = markerPattern.replace(taggedText, "")
        Analysis(spokenTextMatches = normalize(stripped) == normalize(originalText))
    }

    private fun normalize(value: String): String =
        value.replace(Regex("\\s+"), " ").trim()
}
