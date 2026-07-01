package com.example.chatbar.domain.card

import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.DocumentRagStatus
import com.example.chatbar.data.local.entity.PresetEntry
import com.example.chatbar.data.local.entity.PresetImportState
import com.example.chatbar.data.local.entity.PresetManifest
import com.example.chatbar.data.local.entity.PresetType
import com.example.chatbar.data.local.entity.RagIndexStatus
import com.example.chatbar.domain.rag.RagRepository
import java.io.File
import kotlinx.serialization.json.Json
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

class PresetCatalogService(
    private val app: ChatBarApp,
    private val storage: JsonFileStorage,
    private val characterTransfers: CharacterCardTransferService,
    private val formatTransfers: FormatCardTransferService,
    private val worldBookTransfers: WorldBookTransferService,
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
        repairAllPresetCharacterResources()
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

    suspend fun worldBookPackage(entry: PresetEntry): WorldBookPackage {
        require(entry.type == PresetType.WORLD_BOOK)
        return worldBookTransfers.decode(readAsset(entry.file), entry.displayName)
    }

    suspend fun importEntryAsNew(entry: PresetEntry): String = when (entry.type) {
        PresetType.CHARACTER -> {
            val packageData = characterPackage(entry)
            val card = characterTransfers.importNew(
                packageData = packageData,
                presetKey = entry.presetKey,
                presetVersion = entry.version
            )
            val presetWorldBookIds = if (entry.worldBookPresetKeys.isEmpty()) {
                emptyList()
            } else {
                ChatBarApp.instance.worldBookRepository.getAll()
                    .filter { it.sourcePresetKey in entry.worldBookPresetKeys }
                    .map { it.id }
            }
            if (presetWorldBookIds.isNotEmpty()) {
                ChatBarApp.instance.characterRepository.save(
                    card.copy(worldBookIds = (card.worldBookIds + presetWorldBookIds).distinct())
                )
            }
            card.id
        }
        PresetType.FORMAT -> formatTransfers.importNew(formatPackage(entry), entry.presetKey, entry.version).id
        PresetType.WORLD_BOOK -> worldBookTransfers.importNew(worldBookPackage(entry), entry.displayName)
            .copy(sourcePresetKey = entry.presetKey, sourcePresetVersion = entry.version)
            .also { ChatBarApp.instance.worldBookRepository.save(it) }
            .id
        PresetType.MODEL_CATALOG -> entry.presetKey
    }

    suspend fun repairPresetCharacterResources(card: CharacterCard): CharacterCard =
        repairPresetCharacterResources(card, ensureInitialized = true)

    private suspend fun repairPresetCharacterResources(
        card: CharacterCard,
        ensureInitialized: Boolean
    ): CharacterCard {
        if (ensureInitialized && !initialized) initialize()
        val entry = manifest.entries.firstOrNull {
            it.type == PresetType.CHARACTER && it.presetKey == card.sourcePresetKey
        } ?: return card
        val missingDocs = card.customDocuments.filterNot { File(it.filePath).isFile }
        if (missingDocs.isEmpty()) return card

        val packageData = characterPackage(entry)
        val packagedByName = packageData.documents.associateBy { it.fileName }
        val docsDir = File(app.filesDir, "documents").also(File::mkdirs)
        val now = System.currentTimeMillis()
        var changed = false
        val repairedDocs = card.customDocuments.map { doc ->
            if (File(doc.filePath).isFile) return@map doc
            val packaged = packagedByName[doc.fileName] ?: return@map doc
            val file = File(docsDir, "card_${now}_${doc.id}_${safeFileName(packaged.fileName)}")
            file.writeText(packaged.content)
            changed = true
            doc.copy(
                filePath = file.absolutePath,
                fileType = packaged.fileType,
                contentHash = null,
                indexedHash = null,
                ragStatus = DocumentRagStatus.PENDING.name,
                ragChunkCount = 0,
                ragIndexedAt = null,
                ragError = null
            )
        }
        if (!changed) return card
        val repaired = card.copy(
            customDocuments = repairedDocs,
            ragIndexStatus = RagIndexStatus.NOT_INDEXED.name,
            ragIndexDone = 0,
            ragIndexTotal = repairedDocs.size,
            ragIndexMessage = "参考文档已修复，待建立索引",
            ragIndexedAt = null
        )
        app.characterRepository.save(repaired)
        return repaired
    }

    private suspend fun repairAllPresetCharacterResources() {
        app.characterRepository.getAll()
            .filter { it.sourcePresetKey != null }
            .forEach { repairPresetCharacterResources(it, ensureInitialized = false) }
    }

    private fun loadManifest(): PresetManifest =
        json.decodeFromString(PresetManifest.serializer(), readAsset(MANIFEST_PATH))

    private fun readAsset(path: String): String =
        app.assets.open(path).bufferedReader().use { it.readText() }

    private fun safeFileName(value: String): String =
        value.replace(Regex("[\\\\/:*?\"<>|]"), "_").ifBlank { "document.txt" }
}
