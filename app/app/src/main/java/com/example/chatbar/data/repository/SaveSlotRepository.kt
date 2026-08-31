package com.example.chatbar.data.repository

import android.util.JsonReader
import android.util.JsonToken
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.SaveSlot
import com.example.chatbar.data.local.entity.SaveSlotImagePolicy
import com.example.chatbar.data.local.entity.SaveSlotSummary
import com.example.chatbar.data.local.entity.toSummary
import com.example.chatbar.domain.chat.SaveSlotPackageStorage
import java.io.InputStream
import java.io.InputStreamReader
import java.io.OutputStream
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.map

/**
 * 存档槽位仓库
 */
class SaveSlotRepository(
    private val storage: JsonFileStorage,
    private val packageStorage: SaveSlotPackageStorage
) {

    companion object {
        private const val ENTITY_TYPE = "save_slots"
    }

    private val _saveSlots = MutableStateFlow<List<SaveSlotSummary>>(emptyList())
    val saveSlots: Flow<List<SaveSlotSummary>> = _saveSlots.asStateFlow()

    private var initialized = false

    suspend fun initialize() {
        if (initialized) return
        refreshCache()
        initialized = true
    }

    private suspend fun refreshCache() {
        _saveSlots.value = storage.mapRawFilesUncached(ENTITY_TYPE, ::readSummary)
            .sortedByDescending { it.createdAt }
    }

    suspend fun getAll(): List<SaveSlotSummary> {
        initialize()
        return _saveSlots.value
    }

    /** 获取某会话的所有存档 */
    suspend fun getBySessionId(sessionId: String): List<SaveSlotSummary> {
        return getAll().filter { it.sessionId == sessionId }
    }

    suspend fun getSummaryById(id: String): SaveSlotSummary? =
        getAll().firstOrNull { it.id == id }

    fun observeBySessionId(sessionId: String): Flow<List<SaveSlotSummary>> {
        return _saveSlots.map { list ->
            list.filter { it.sessionId == sessionId }
                .sortedByDescending { it.createdAt }
        }
    }

    suspend fun getById(id: String): SaveSlot? {
        return storage.loadEntity(ENTITY_TYPE, id, SaveSlot.serializer())
    }

    suspend fun exportLegacyRaw(id: String, output: OutputStream) {
        storage.copyEntityRawUncached(ENTITY_TYPE, id, output)
    }

    suspend fun save(slot: SaveSlot) {
        storage.saveEntityUncached(ENTITY_TYPE, slot.id, slot, SaveSlot.serializer())
        _saveSlots.value = (_saveSlots.value.filterNot { it.id == slot.id } + slot.toSummary())
            .sortedByDescending { it.createdAt }
    }

    suspend fun delete(id: String) {
        packageStorage.deleteBySlotId(id)
        storage.deleteEntityUncached(ENTITY_TYPE, id)
        _saveSlots.value = _saveSlots.value.filterNot { it.id == id }
    }

    /** 删除某会话的所有存档 */
    suspend fun deleteBySessionId(sessionId: String) {
        getAll().filter { it.sessionId == sessionId }.forEach { summary ->
            packageStorage.deleteBySlotId(summary.id)
            storage.deleteEntityUncached(ENTITY_TYPE, summary.id)
        }
        _saveSlots.value = _saveSlots.value.filterNot { it.sessionId == sessionId }
    }

    private fun readSummary(storageId: String, input: InputStream): SaveSlotSummary? {
        var id = storageId
        var sessionId = ""
        var name = ""
        var description: String? = null
        var schemaVersion = 1
        var createdAt = 0L
        var inlineMessageCount = 0
        var inlineImageCount = 0
        var inlineAudioCount = 0
        var packageMessageCount: Int? = null
        var packageImageCount: Int? = null
        var packageAudioCount: Int? = null
        var imagePolicy = SaveSlotImagePolicy.ORIGINAL
        var includeAudio = true
        JsonReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "id" -> id = reader.nextString()
                    "sessionId" -> sessionId = reader.nextString()
                    "name" -> name = reader.nextString()
                    "description" -> description = reader.nextNullableString()
                    "schemaVersion" -> schemaVersion = reader.nextInt()
                    "createdAt" -> createdAt = reader.nextLong()
                    "imagePolicy" -> imagePolicy = runCatching {
                        SaveSlotImagePolicy.valueOf(reader.nextString())
                    }.getOrDefault(SaveSlotImagePolicy.ORIGINAL)
                    "includeAudio" -> includeAudio = reader.nextBoolean()
                    "messages" -> inlineMessageCount = reader.countArrayValues()
                    "imageResources" -> inlineImageCount = reader.countObjectValues()
                    "audioResources" -> inlineAudioCount = reader.countObjectValues()
                    "packageRef" -> {
                        if (reader.peek() == JsonToken.NULL) {
                            reader.nextNull()
                        } else {
                            reader.beginObject()
                            while (reader.hasNext()) {
                                when (reader.nextName()) {
                                    "messageCount" -> packageMessageCount = reader.nextInt()
                                    "imageCount" -> packageImageCount = reader.nextInt()
                                    "audioCount" -> packageAudioCount = reader.nextInt()
                                    else -> reader.skipValue()
                                }
                            }
                            reader.endObject()
                        }
                    }
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
        }
        if (sessionId.isBlank() || name.isBlank()) return null
        return SaveSlotSummary(
            id = id,
            sessionId = sessionId,
            name = name,
            description = description,
            messageCount = packageMessageCount ?: inlineMessageCount,
            createdAt = createdAt,
            schemaVersion = schemaVersion,
            imagePolicy = imagePolicy,
            imageCount = packageImageCount ?: inlineImageCount,
            includeAudio = includeAudio,
            audioCount = packageAudioCount ?: inlineAudioCount
        )
    }
}

private fun JsonReader.nextNullableString(): String? = if (peek() == JsonToken.NULL) {
    nextNull()
    null
} else {
    nextString()
}

private fun JsonReader.countArrayValues(): Int {
    var count = 0
    beginArray()
    while (hasNext()) {
        skipValue()
        count++
    }
    endArray()
    return count
}

private fun JsonReader.countObjectValues(): Int {
    var count = 0
    beginObject()
    while (hasNext()) {
        nextName()
        skipValue()
        count++
    }
    endObject()
    return count
}
