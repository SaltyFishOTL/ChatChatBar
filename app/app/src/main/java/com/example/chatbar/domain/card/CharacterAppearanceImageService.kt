package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.chat.StreamingChatService
import com.example.chatbar.domain.model.EffectiveModelResolver
import com.example.chatbar.domain.model.hasConfiguredAuthentication
import com.example.chatbar.domain.prompt.PromptTemplates
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class CharacterAppearanceImageDraft(
    val appearance: String = "",
    val clothing: String = ""
)

data class CharacterAppearanceImageModelRoute(
    val currentModelLabel: String,
    val analysisModelLabel: String,
    val usesLinkedVisionModel: Boolean
)

data class CharacterAppearanceImageResult(
    val draft: CharacterAppearanceImageDraft,
    val modelRoute: CharacterAppearanceImageModelRoute
)

class CharacterAppearanceImageService(
    private val modelResolver: EffectiveModelResolver,
    private val chatService: StreamingChatService,
    private val settingsProvider: suspend () -> AppSettings,
    private val json: Json = Json {
        ignoreUnknownKeys = true
        isLenient = true
        coerceInputValues = true
        encodeDefaults = true
    }
) {
    suspend fun generate(
        imageBase64: String,
        characterName: String,
        onModelResolved: (CharacterAppearanceImageModelRoute) -> Unit = {}
    ): CharacterAppearanceImageResult = withContext(Dispatchers.IO) {
        require(imageBase64.isNotBlank()) { "上传图片不能为空" }
        val settings = settingsProvider()
        val currentModel = modelResolver.resolveChatModel(null, settings)
            ?: error("未配置可用的当前对话模型")
        val linkedVisionModel = if (currentModel.isMultimodal) {
            null
        } else {
            modelResolver.auxiliaryChatModel(currentModel.visionModelId, settings)
        }
        val analysisModel = selectCharacterAppearanceImageModel(currentModel, linkedVisionModel)
            ?: error("当前对话模型不支持多模态，且未配置可用的关联视觉模型")
        require(analysisModel.hasConfiguredAuthentication(settings)) {
            "${analysisModel.modelLabel()} 认证未配置"
        }
        val route = CharacterAppearanceImageModelRoute(
            currentModelLabel = currentModel.modelLabel(),
            analysisModelLabel = analysisModel.modelLabel(),
            usesLinkedVisionModel = analysisModel.id != currentModel.id
        )
        onModelResolved(route)

        val raw = chatService.completeText(
            messages = listOf(
                ChatApiMessage.text(
                    "system",
                    PromptTemplates.CHARACTER_APPEARANCE_IMAGE_SYSTEM_PROMPT
                ),
                ChatApiMessage.withImage(
                    "user",
                    PromptTemplates.characterAppearanceImageUserPrompt(characterName),
                    imageBase64
                )
            ),
            modelConfig = analysisModel,
            maxTokens = 1200,
            disableThinking = true,
            isolatedTaskParameters = true,
            responseFormatJson = true
        )
        val draft = parseDraft(raw, json)
            ?: error("图片识别结果不是可解析 JSON：${raw.take(500)}")
        CharacterAppearanceImageResult(draft = draft, modelRoute = route)
    }

    companion object {
        private val defaultJson = Json {
            ignoreUnknownKeys = true
            isLenient = true
            coerceInputValues = true
            encodeDefaults = true
        }

        fun parseDraft(
            raw: String,
            json: Json = defaultJson
        ): CharacterAppearanceImageDraft? =
            raw.extractJsonObjectCandidates()
                .mapIndexedNotNull { index, candidate ->
                    runCatching {
                        json.decodeFromString(
                            CharacterAppearanceImageDraft.serializer(),
                            candidate
                        ).normalized()
                    }.getOrNull()
                        ?.takeIf(CharacterAppearanceImageDraft::hasContent)
                        ?.let { index to it }
                }
                .maxWithOrNull(
                    compareBy<Pair<Int, CharacterAppearanceImageDraft>>(
                        { it.second.contentScore() },
                        { it.second.appearance.length + it.second.clothing.length },
                        { it.first }
                    )
                )
                ?.second
    }
}

internal fun selectCharacterAppearanceImageModel(
    currentModel: ModelConfig,
    linkedVisionModel: ModelConfig?
): ModelConfig? =
    if (currentModel.isMultimodal) {
        currentModel
    } else {
        linkedVisionModel?.takeIf { linked ->
            linked.isMultimodal && linked.id == currentModel.visionModelId
        }
    }

private fun CharacterAppearanceImageDraft.normalized(): CharacterAppearanceImageDraft =
    copy(
        appearance = appearance.trim(),
        clothing = clothing.trim()
    )

private fun CharacterAppearanceImageDraft.hasContent(): Boolean =
    appearance.isNotBlank() || clothing.isNotBlank()

private fun CharacterAppearanceImageDraft.contentScore(): Int =
    listOf(appearance, clothing).count(String::isNotBlank)

private fun ModelConfig.modelLabel(): String =
    displayName.ifBlank { modelName.ifBlank { id } }
