package com.example.chatbar.data.repository

import com.example.chatbar.data.local.JsonFileStorage
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.serialization.Serializable

class NovelAiPromptTranslationCacheRepository(
    private val storage: JsonFileStorage
) {
    private val mutex = Mutex()
    private val entries = LinkedHashMap<String, String>(128, 0.75f, true)
    private var loaded = false

    suspend fun getAll(keys: Collection<String>): Map<String, String> = mutex.withLock {
        ensureLoaded()
        buildMap {
            keys.distinct().forEach { key ->
                this@NovelAiPromptTranslationCacheRepository.entries[key]?.let { put(key, it) }
            }
        }
    }

    suspend fun putAll(values: Map<String, String>) = mutex.withLock {
        ensureLoaded()
        values.forEach { (key, value) ->
            if (key.isNotBlank() && value.isNotBlank()) entries[key] = value
        }
        while (entries.size > MAX_ENTRIES) entries.remove(entries.keys.first())
        storage.saveSingleton(
            CACHE_TYPE,
            NovelAiPromptTranslationCacheDocument(entries = entries.map { (key, value) ->
                NovelAiPromptTranslationCacheEntry(key, value)
            }),
            NovelAiPromptTranslationCacheDocument.serializer()
        )
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        val document = storage.loadSingleton(
            CACHE_TYPE,
            NovelAiPromptTranslationCacheDocument.serializer()
        )
        if (document?.version == CACHE_VERSION) {
            document.entries.takeLast(MAX_ENTRIES).forEach { entry ->
                if (entry.key.isNotBlank() && entry.translation.isNotBlank()) {
                    entries[entry.key] = entry.translation
                }
            }
        }
        loaded = true
    }

    private companion object {
        const val CACHE_TYPE = "novelai_prompt_translation_cache"
        const val CACHE_VERSION = 3
        const val MAX_ENTRIES = 10_000
    }
}

@Serializable
private data class NovelAiPromptTranslationCacheDocument(
    val version: Int = 3,
    val entries: List<NovelAiPromptTranslationCacheEntry> = emptyList()
)

@Serializable
private data class NovelAiPromptTranslationCacheEntry(
    val key: String,
    val translation: String
)
