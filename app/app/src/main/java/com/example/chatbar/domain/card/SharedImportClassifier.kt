package com.example.chatbar.domain.card

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject

sealed interface SharedImportType {
    data object Character : SharedImportType
    data object Format : SharedImportType
    data object WorldBook : SharedImportType
    data object ModelTemplate : SharedImportType
    data object Unknown : SharedImportType
}

/**
 * 根据 JSON 顶层结构识别共享导入的文件类型。
 * 角色卡含 `card`，世界书含 `book`，格式卡含 `name`+`content`，模型模板含 `displayName`+`modelName`。
 * 识别不出时返回 [SharedImportType.Unknown]，由调用方按角色卡流程兜底（如 SillyTavern）。
 */
object SharedImportClassifier {

    private val probeJson = Json {
        ignoreUnknownKeys = true
        isLenient = true
    }

    fun classify(text: String): SharedImportType {
        val root = runCatching { probeJson.parseToJsonElement(text).jsonObject }.getOrNull()
            ?: return SharedImportType.Unknown
        return when {
            root.containsKey("book") -> SharedImportType.WorldBook
            root.containsKey("card") -> SharedImportType.Character
            root.containsKey("name") && root.containsKey("content") -> SharedImportType.Format
            root.containsKey("displayName") && root.containsKey("modelName") -> SharedImportType.ModelTemplate
            else -> SharedImportType.Unknown
        }
    }
}
