package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.CharacterEditMode
import kotlinx.serialization.Serializable

@Serializable
data class CharacterCardPackage(
    val schemaVersion: Int = 3,
    val exportedAt: Long = System.currentTimeMillis(),
    val card: PackagedCharacterCard,
    val documents: List<PackagedDocument> = emptyList(),
    val images: Map<String, PackagedImage> = emptyMap()
)

@Serializable
data class PackagedCharacterCard(
    val name: String,
    val avatarResourceId: String? = null,
    val characters: List<PackagedCharacter> = emptyList(),
    val greeting: String = "",
    val chatBackgroundResourceId: String? = null,
    val editMode: CharacterEditMode = CharacterEditMode.STRUCTURED,
    val basicSetting: String = "",
    val freeformCharacterText: String = "",
    val defaultImagePrompt: String = ""
)

@Serializable
data class PackagedCharacter(
    val name: String,
    val profile: String = "",
    val appearance: String = "",
    val appearanceImageResourceId: String? = null,
    val clothing: String = "",
    val abilities: String = "",
    val habits: String = "",
    val background: String = "",
    val relationships: String = "",
    val speakingStyle: String = "",
    val imagePrompt: String = ""
)

@Serializable
data class PackagedDocument(
    val fileName: String,
    val fileType: String,
    val content: String
)

@Serializable
data class PackagedImage(
    val fileName: String,
    val data: String
)

data class CharacterCardImportRequest(
    val packageData: CharacterCardPackage,
    val presetKey: String? = null,
    val presetVersion: Int? = null
)

internal fun CharacterCardPackage.validateForImport() {
    require(schemaVersion == 3) { "不支持的角色卡 schemaVersion：$schemaVersion" }
    require(card.name.isNotBlank()) { "角色卡名称不能为空" }
    require(card.characters.all { it.name.isNotBlank() }) { "人物名称不能为空" }
    require(documents.all { it.fileName.isNotBlank() && it.fileType.isNotBlank() }) { "文档名称和类型不能为空" }
    require(images.all { (id, image) -> id.isNotBlank() && image.fileName.isNotBlank() && image.data.isNotBlank() }) {
        "图片资源 ID、文件名和数据不能为空"
    }
    val references = buildList {
        card.avatarResourceId?.let(::add)
        card.chatBackgroundResourceId?.let(::add)
        card.characters.mapNotNullTo(this) { it.appearanceImageResourceId }
    }
    val missing = references.filterNot(images::containsKey).distinct()
    require(missing.isEmpty()) { "缺少图片资源：${missing.joinToString()}" }
}

@Serializable
data class FormatCardPackage(
    val schemaVersion: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val name: String,
    val content: String,
    val sourcePresetKey: String? = null,
    val sourcePresetVersion: Int? = null
)
