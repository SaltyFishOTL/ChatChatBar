package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.model.EffectiveModelResolver

data class ImageUnderstandingResult(
    val directImageBase64s: List<String> = emptyList(),
    val descriptions: List<String> = emptyList(),
    val unavailableReason: String? = null
) {
    val hasSourceImages: Boolean
        get() = directImageBase64s.isNotEmpty() || descriptions.isNotEmpty()
}

class ImageUnderstandingService(
    private val modelResolver: EffectiveModelResolver,
    private val chatService: StreamingChatService
) {
    suspend fun prepare(
        imageBase64s: List<String>,
        generationModel: ModelConfig,
        requireUnderstanding: Boolean,
        announceDirect: Boolean = false,
        onStatus: suspend (String) -> Unit = {},
        onDescriptionText: (Int, String) -> Unit = { _, _ -> }
    ): ImageUnderstandingResult {
        val images = imageBase64s.filter(String::isNotBlank)
        if (images.isEmpty()) return ImageUnderstandingResult()

        if (generationModel.isMultimodal) {
            if (announceDirect) onStatus("当前模型支持多模态，直接使用上传图片")
            return ImageUnderstandingResult(directImageBase64s = images)
        }

        val visionModel = modelResolver.auxiliaryChatModel(generationModel.visionModelId)
            ?.takeIf { it.isMultimodal && it.apiKey.isNotBlank() }
        if (visionModel == null) {
            val reason = "当前模型不支持多模态，且未配置可用的视觉模型"
            if (requireUnderstanding) error("$reason，无法基于图片生成")
            return ImageUnderstandingResult(unavailableReason = reason)
        }

        onStatus("正在使用视觉模型 ${visionModel.displayName.ifBlank { visionModel.modelName }} 解析图片")
        val descriptions = mutableListOf<String>()
        images.forEachIndexed { index, imageBase64 ->
            val description = runCatching {
                val visibleText = StringBuilder()
                chatService.describeImageStreaming(imageBase64, visionModel) { chunk ->
                    visibleText.append(chunk)
                    onDescriptionText(index, visibleText.toString())
                }
            }.getOrElse { error ->
                val reason = "图片解析失败: ${error.message ?: error::class.java.simpleName}"
                if (requireUnderstanding) throw RuntimeException(reason, error)
                return ImageUnderstandingResult(unavailableReason = reason)
            }.trim()
            if (description.isNotBlank()) {
                onDescriptionText(index, description)
                descriptions += if (images.size == 1) description else "图片 ${index + 1}: $description"
            }
        }

        if (descriptions.isEmpty()) {
            val reason = "视觉模型未返回图片描述"
            if (requireUnderstanding) error("$reason，无法基于图片生成")
            return ImageUnderstandingResult(unavailableReason = reason)
        }
        onStatus("图片内容已解析")
        return ImageUnderstandingResult(descriptions = descriptions)
    }
}
