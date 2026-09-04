package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.CharacterEditMode
import com.example.chatbar.data.local.entity.FishAudioVoiceBinding
import com.example.chatbar.data.local.entity.FormatCardUserToolConfig
import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.domain.image.NovelAiImageModel
import kotlinx.serialization.Serializable

@Serializable
data class CharacterCardPackage(
    val schemaVersion: Int = 8,
    val exportedAt: Long = System.currentTimeMillis(),
    val card: PackagedCharacterCard,
    val documents: List<PackagedDocument> = emptyList(),
    val images: Map<String, PackagedImage> = emptyMap(),
    val worldBooks: List<WorldBook> = emptyList()
)

@Serializable
data class PackagedCharacterCard(
    val name: String,
    val botName: String = "",
    val avatarResourceId: String? = null,
    val characters: List<PackagedCharacter> = emptyList(),
    val greeting: String = "",
    val alternateGreetings: List<String> = emptyList(),
    val chatBackgroundResourceId: String? = null,
    val editMode: CharacterEditMode = CharacterEditMode.STRUCTURED,
    val basicSetting: String = "",
    val freeformCharacterText: String = "",
    val defaultImagePrompt: String = "",
    val defaultImageNegativePrompt: String = "",
    val defaultNovelAiImageModel: NovelAiImageModel? = null,
    val systemPrompt: String = "",
    val postHistoryInstructions: String = "",
    val mesExample: String = "",
    val creatorNotes: String = "",
    val tags: List<String> = emptyList(),
    val creator: String = "",
    val characterVersion: String = "",
    val extensions: String = "",
    val characterBook: WorldBook? = null
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
    val imagePrompt: String = "",
    val fishAudioVoice: FishAudioVoiceBinding? = null
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
    require(schemaVersion in 3..8) { "不支持的角色卡 schemaVersion：$schemaVersion" }
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

internal fun CharacterCardPackage.withoutEmptyCharacterPlaceholders(): CharacterCardPackage {
    val filteredCharacters = card.characters.filterNot(CharacterPlaceholderPolicy::isEmpty)
    return if (filteredCharacters.size == card.characters.size) {
        this
    } else {
        copy(card = card.copy(characters = filteredCharacters))
    }
}

@Serializable
data class FormatCardPackage(
    val schemaVersion: Int = FORMAT_CARD_PACKAGE_SCHEMA_VERSION,
    val exportedAt: Long = System.currentTimeMillis(),
    val name: String,
    val content: String,
    val userTools: List<FormatCardUserToolConfig> = emptyList(),
    val sourcePresetKey: String? = null,
    val sourcePresetVersion: Int? = null
)

const val FORMAT_CARD_PACKAGE_SCHEMA_VERSION = 2

fun FormatCardPackage.validateForImport() {
    require(schemaVersion in 1..FORMAT_CARD_PACKAGE_SCHEMA_VERSION) {
        "不支持的格式卡 schemaVersion：$schemaVersion"
    }
    require(name.isNotBlank()) { "格式卡名称不能为空" }
    require(content.isNotBlank()) { "格式卡内容不能为空" }
    FormatCardUserToolPolicy.requireValid(userTools)
}

@Serializable
data class WorldBookPackage(
    val schemaVersion: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val book: WorldBook
)

fun WorldBookPackage.validateForImport() {
    require(schemaVersion == 1) { "不支持的世界书 schemaVersion：$schemaVersion" }
    require(book.name.isNotBlank()) { "世界书名称不能为空" }
}
