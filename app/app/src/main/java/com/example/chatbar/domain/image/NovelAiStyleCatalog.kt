package com.example.chatbar.domain.image

import android.content.Context
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json

const val NOVEL_AI_STYLE_CATALOG_ASSET_PATH =
    "presets/image_styles/default-image-styles.json"
const val NOVEL_AI_STYLE_PREVIEW_ASSET_ROOT = "presets/image_styles/previews"

private const val SUPPORTED_NOVEL_AI_STYLE_SCHEMA_VERSION = 1

@Serializable
data class NovelAiStyleCatalog(
    val schemaVersion: Int,
    val styles: List<NovelAiStylePreset>
)

@Serializable
data class NovelAiStylePreset(
    val styleKey: String = "",
    val displayName: String = "",
    val description: String = "",
    val prompt: String = "",
    val previewImage: String = "",
    @Transient val previewAvailable: Boolean = true
) {
    val previewAssetPath: String
        get() = "$NOVEL_AI_STYLE_PREVIEW_ASSET_ROOT/$previewImage"
}

data class NovelAiStyleCatalogLoadResult(
    val styles: List<NovelAiStylePreset> = emptyList(),
    val errors: List<String> = emptyList(),
    val fatalError: String? = null,
    val isLoading: Boolean = false
)

class NovelAiStyleCatalogParser(
    private val json: Json
) {
    fun parse(
        rawJson: String,
        assetExists: (String) -> Boolean = { true }
    ): NovelAiStyleCatalogLoadResult {
        val catalog = try {
            json.decodeFromString<NovelAiStyleCatalog>(rawJson)
        } catch (error: Exception) {
            return NovelAiStyleCatalogLoadResult(
                fatalError = "画风配置读取失败：${error.message ?: error::class.simpleName.orEmpty()}"
            )
        }

        if (catalog.schemaVersion != SUPPORTED_NOVEL_AI_STYLE_SCHEMA_VERSION) {
            return NovelAiStyleCatalogLoadResult(
                fatalError = "不支持的画风配置版本：${catalog.schemaVersion}"
            )
        }

        val seenKeys = mutableSetOf<String>()
        val styles = mutableListOf<NovelAiStylePreset>()
        val errors = mutableListOf<String>()

        catalog.styles.forEachIndexed { index, source ->
            val position = index + 1
            val styleKey = source.styleKey.trim()
            val displayName = source.displayName.trim()
            val description = source.description.trim()
            val previewImage = source.previewImage.trim()

            val blankFields = buildList {
                if (styleKey.isBlank()) add("styleKey")
                if (displayName.isBlank()) add("displayName")
                if (source.prompt.isBlank()) add("prompt")
                if (previewImage.isBlank()) add("previewImage")
            }
            if (blankFields.isNotEmpty()) {
                errors += "画风条目 $position 缺少字段：${blankFields.joinToString()}"
                return@forEachIndexed
            }
            if (!isSafePreviewFileName(previewImage)) {
                errors += "画风条目 $position 的 previewImage 必须是安全文件名：$previewImage"
                return@forEachIndexed
            }
            if (!seenKeys.add(styleKey)) {
                errors += "画风条目 $position 的 styleKey 重复，已保留首项：$styleKey"
                return@forEachIndexed
            }

            val previewAssetPath = "$NOVEL_AI_STYLE_PREVIEW_ASSET_ROOT/$previewImage"
            val previewAvailable = runCatching { assetExists(previewAssetPath) }.getOrDefault(false)
            if (!previewAvailable) {
                errors += "$displayName：例图缺失（$previewImage）"
            }
            styles += source.copy(
                styleKey = styleKey,
                displayName = displayName,
                description = description,
                previewImage = previewImage,
                previewAvailable = previewAvailable
            )
        }

        return NovelAiStyleCatalogLoadResult(
            styles = styles,
            errors = errors
        )
    }

    private fun isSafePreviewFileName(fileName: String): Boolean =
        fileName != "." &&
            fileName != ".." &&
            !fileName.contains("..") &&
            !fileName.contains('/') &&
            !fileName.contains('\\')
}

class NovelAiStyleCatalogService(
    private val context: Context,
    json: Json
) {
    private val parser = NovelAiStyleCatalogParser(json)

    fun load(): NovelAiStyleCatalogLoadResult {
        val rawJson = try {
            context.assets.open(NOVEL_AI_STYLE_CATALOG_ASSET_PATH)
                .bufferedReader()
                .use { it.readText() }
        } catch (error: Exception) {
            return NovelAiStyleCatalogLoadResult(
                fatalError = "画风配置加载失败：${error.message ?: error::class.simpleName.orEmpty()}"
            )
        }

        return parser.parse(rawJson) { assetPath ->
            runCatching {
                context.assets.open(assetPath).use { }
            }.isSuccess
        }
    }
}

data class NovelAiStylePromptUndo(
    val previousPrompt: String,
    val appliedPrompt: String,
    val displayName: String
)

data class NovelAiStylePromptFillState(
    val value: String = "",
    val undo: NovelAiStylePromptUndo? = null
) {
    fun apply(preset: NovelAiStylePreset): NovelAiStylePromptFillState {
        if (value == preset.prompt) return this
        return NovelAiStylePromptFillState(
            value = preset.prompt,
            undo = NovelAiStylePromptUndo(
                previousPrompt = value,
                appliedPrompt = preset.prompt,
                displayName = preset.displayName
            )
        )
    }

    fun edit(newValue: String): NovelAiStylePromptFillState =
        NovelAiStylePromptFillState(value = newValue)

    fun undoLastFill(): NovelAiStylePromptFillState {
        val currentUndo = undo ?: return this
        if (value != currentUndo.appliedPrompt) return copy(undo = null)
        return NovelAiStylePromptFillState(value = currentUndo.previousPrompt)
    }
}
