package com.example.chatbar.data.local.entity

import kotlinx.serialization.Serializable
import kotlin.uuid.ExperimentalUuidApi
import kotlin.uuid.Uuid

/**
 * 格式卡片 - prompt模板
 */
@Serializable
data class FormatCard(
    val id: String,
    val name: String,
    val content: String, // prompt模板文本
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
