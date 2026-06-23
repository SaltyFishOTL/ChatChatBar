package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.FormatCard
import com.example.chatbar.data.repository.FormatCardRepository
import java.util.UUID
import kotlinx.serialization.json.Json

class FormatCardTransferService(
    private val repository: FormatCardRepository,
    private val json: Json
) {
    suspend fun exportJson(id: String): String {
        val card = repository.getById(id) ?: error("格式卡不存在")
        return json.encodeToString(FormatCardPackage.serializer(), FormatCardPackage(name = card.name, content = card.content, sourcePresetKey = card.sourcePresetKey, sourcePresetVersion = card.sourcePresetVersion))
    }

    fun decode(rawJson: String): FormatCardPackage = json.decodeFromString(FormatCardPackage.serializer(), rawJson)

    suspend fun duplicate(id: String): FormatCard {
        val source = repository.getById(id) ?: error("格式卡不存在")
        val copy = source.copy(
            id = UUID.randomUUID().toString(),
            name = NamePolicy.nextCopyName(source.name, repository.getAll().map { it.name }),
            isDefault = false,
            sourcePresetKey = null,
            sourcePresetVersion = null,
            createdAt = System.currentTimeMillis()
        )
        repository.save(copy)
        return copy
    }

    suspend fun importNew(packageData: FormatCardPackage, presetKey: String? = null, presetVersion: Int? = null): FormatCard {
        val all = repository.getAll()
        val name = if (all.any { NamePolicy.isSame(it.name, packageData.name) }) {
            NamePolicy.nextCopyName(packageData.name, all.map { it.name })
        } else NamePolicy.normalize(packageData.name)
        return FormatCard(
            id = UUID.randomUUID().toString(),
            name = name,
            content = packageData.content,
            isDefault = false,
            sourcePresetKey = presetKey ?: packageData.sourcePresetKey,
            sourcePresetVersion = presetVersion ?: packageData.sourcePresetVersion,
            createdAt = System.currentTimeMillis()
        ).also { repository.save(it) }
    }

    suspend fun overwrite(existingId: String, packageData: FormatCardPackage, presetKey: String? = null, presetVersion: Int? = null): FormatCard {
        val existing = repository.getById(existingId) ?: error("待覆盖格式卡不存在")
        return existing.copy(
            name = NamePolicy.normalize(existing.name),
            content = packageData.content,
            sourcePresetKey = presetKey ?: packageData.sourcePresetKey,
            sourcePresetVersion = presetVersion ?: packageData.sourcePresetVersion
        ).also { repository.save(it) }
    }
}
