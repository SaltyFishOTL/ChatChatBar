package com.example.chatbar.data.local.entity

import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

@Serializable
enum class FormatCardUserToolType {
    RANDOM_NUMBER,
    STRONG_PROMPT_SUFFIX
}

/**
 * 格式卡用户工具配置。随机数边界保留原始输入，确保未完成配置可进入编辑草稿。
 */
@Serializable
data class FormatCardUserToolConfig(
    val type: FormatCardUserToolType,
    val minimum: String = "",
    val maximum: String = "",
    val text: String = ""
) {
    companion object {
        fun randomNumber(): FormatCardUserToolConfig = FormatCardUserToolConfig(
            type = FormatCardUserToolType.RANDOM_NUMBER,
            minimum = "1",
            maximum = "100"
        )

        fun strongPromptSuffix(): FormatCardUserToolConfig = FormatCardUserToolConfig(
            type = FormatCardUserToolType.STRONG_PROMPT_SUFFIX
        )
    }
}

/**
 * 格式卡片 - prompt模板
 */
@Serializable
data class FormatCard(
    val id: String,
    val name: String,
    val content: String, // prompt模板文本
    val userTools: List<FormatCardUserToolConfig> = emptyList(),
    val isDefault: Boolean = false,
    val sourcePresetKey: String? = null,
    val sourcePresetVersion: Int? = null,
    val createdAt: Long
) {
    companion object {
        @OptIn(ExperimentalUuidApi::class)
        fun create(
            name: String,
            content: String,
            isDefault: Boolean = false
        ): FormatCard = FormatCard(
            id = Uuid.random().toString(),
            name = name,
            content = content,
            isDefault = isDefault,
            createdAt = System.currentTimeMillis()
        )
    }
}
