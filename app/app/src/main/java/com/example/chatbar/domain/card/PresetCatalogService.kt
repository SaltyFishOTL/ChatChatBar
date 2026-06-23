package com.example.chatbar.domain.card

import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.PresetEntry
import com.example.chatbar.data.local.entity.PresetImportState
import com.example.chatbar.data.local.entity.PresetManifest
import com.example.chatbar.data.local.entity.PresetType
import com.example.chatbar.domain.rag.RagRepository
import kotlinx.serialization.json.Json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PresetCatalogService(
    private val app: ChatBarApp,
    private val storage: JsonFileStorage,
    private val characterTransfers: CharacterCardTransferService,
    private val formatTransfers: FormatCardTransferService,
    private val ragRepository: RagRepository,
    private val json: Json
) {
    companion object {
        private const val STATE_TYPE = "preset_import_state"
        private const val MANIFEST_PATH = "presets/manifest.json"
    }

    @Volatile private var manifest = PresetManifest()
    @Volatile private var initialized = false
    private val initializeMutex = Mutex()

    suspend fun initialize() = initializeMutex.withLock {
        if (initialized) return@withLock
        manifest = loadManifest()
        val current = storage.loadSingleton(STATE_TYPE, PresetImportState.serializer()) ?: PresetImportState()
        val seen = current.seenVersions.toMutableMap()
        manifest.entries.forEach { entry ->
            if (entry.presetKey !in seen) {
                if (runCatching { importEntryAsNew(entry) }.isSuccess) {
                    seen[entry.presetKey] = entry.version
                }
            } else if (entry.version > (seen[entry.presetKey] ?: 0)) {
                seen[entry.presetKey] = entry.version
            }
        }
        storage.saveSingleton(STATE_TYPE, PresetImportState(seen), PresetImportState.serializer())
        ragRepository.deleteAllChunksBySourceType(com.example.chatbar.data.local.entity.ChunkSourceType.CHARACTER_SETTING)
        initialized = true
    }

    fun entries(type: PresetType): List<PresetEntry> = manifest.entries.filter { it.type == type }

    fun latestVersion(presetKey: String?): Int? =
        presetKey?.let { key -> manifest.entries.filter { it.presetKey == key }.maxOfOrNull { it.version } }

    fun hasUpdate(presetKey: String?, version: Int?): Boolean {
        val latest = latestVersion(presetKey) ?: return false
        return version != null && latest > version
    }

    suspend fun characterPackage(entry: PresetEntry): CharacterCardPackage {
        require(entry.type == PresetType.CHARACTER)
        return characterTransfers.decode(readAsset(entry.file))
    }

    suspend fun formatPackage(entry: PresetEntry): FormatCardPackage {
        require(entry.type == PresetType.FORMAT)
        return formatTransfers.decode(readAsset(entry.file))
    }

    suspend fun importEntryAsNew(entry: PresetEntry): String = when (entry.type) {
        PresetType.CHARACTER -> {
            val packageData = characterPackage(entry)
            characterTransfers.importNew(
                packageData = packageData,
                presetKey = entry.presetKey,
                presetVersion = entry.version
            ).id
        }
        PresetType.FORMAT -> formatTransfers.importNew(formatPackage(entry), entry.presetKey, entry.version).id
        PresetType.MODEL_CATALOG -> entry.presetKey
    }

    private fun loadManifest(): PresetManifest =
        json.decodeFromString(PresetManifest.serializer(), readAsset(MANIFEST_PATH))

    private fun readAsset(path: String): String =
        app.assets.open(path).bufferedReader().use { it.readText() }
}
