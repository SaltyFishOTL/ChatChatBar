package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.PresetEntry
import com.example.chatbar.data.local.entity.PresetType

internal suspend fun resolvePresetCharacterDependencies(
    packaged: CharacterCardPackage,
    entry: PresetEntry,
    entries: List<PresetEntry>,
    loadWorldBook: suspend (PresetEntry) -> WorldBookPackage,
    loadFormat: suspend (PresetEntry) -> FormatCardPackage
): CharacterCardPackage {
    val dependencies = entry.worldBookPresetKeys.map { key ->
        val preset = requireNotNull(entries.firstOrNull { it.type == PresetType.WORLD_BOOK && it.presetKey == key }) {
            "预制角色卡关联的世界书不存在：$key"
        }
        loadWorldBook(preset).book.copy(sourcePresetKey = key, sourcePresetVersion = preset.version)
    }
    val defaultFormat = packaged.defaultFormatCard ?: entry.defaultFormatPresetKey?.let { key ->
        val preset = requireNotNull(entries.firstOrNull { it.type == PresetType.FORMAT && it.presetKey == key }) {
            "预制角色卡关联的格式卡不存在：$key"
        }
        loadFormat(preset).copy(sourcePresetKey = key, sourcePresetVersion = preset.version)
    }
    return packaged.copy(
        worldBooks = (packaged.worldBooks + dependencies).distinctBy { it.sourcePresetKey ?: it.id },
        defaultFormatCard = defaultFormat
    )
}
