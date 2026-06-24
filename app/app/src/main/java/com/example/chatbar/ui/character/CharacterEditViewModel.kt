package com.example.chatbar.ui.character

import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterEditMode
import com.example.chatbar.data.local.entity.CharacterInfo
import com.example.chatbar.data.local.entity.ChunkSourceType
import com.example.chatbar.data.local.entity.DocumentInfo
import com.example.chatbar.data.local.entity.DocumentRagStatus
import com.example.chatbar.data.local.entity.RagIndexStatus
import com.example.chatbar.domain.card.NamePolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File

class CharacterEditViewModel(private val characterId: String?) : ViewModel() {
    private val characterRepository = ChatBarApp.instance.characterRepository
    private val modelRepository = ChatBarApp.instance.modelRepository
    private val settingsRepository = ChatBarApp.instance.settingsRepository
    private val ragManager = ChatBarApp.instance.ragManager
    private val modelResolver = ChatBarApp.instance.effectiveModelResolver
    private var indexingJob: Job? = null

    private val _characterCard = MutableStateFlow<CharacterCard?>(null)
    val characterCard: StateFlow<CharacterCard?> = _characterCard.asStateFlow()

    private val _isSaving = MutableStateFlow(false)
    val isSaving: StateFlow<Boolean> = _isSaving.asStateFlow()

    private val _indexingStatus = MutableStateFlow<String?>(null)
    val indexingStatus: StateFlow<String?> = _indexingStatus.asStateFlow()

    var name by mutableStateOf("")
    var greeting by mutableStateOf("")
    var avatar by mutableStateOf<String?>(null)
    var chatBackground by mutableStateOf<String?>(null)
    var editMode by mutableStateOf(CharacterEditMode.STRUCTURED)
    var basicSetting by mutableStateOf("")
    var freeformCharacterText by mutableStateOf("")
    var defaultImagePrompt by mutableStateOf("")
    val charactersList = mutableStateListOf<CharacterInfo>()
    val documentsList = mutableStateListOf<DocumentInfo>()

    init {
        loadCharacterCard()
    }

    private fun loadCharacterCard() {
        viewModelScope.launch {
            if (characterId != null) {
                characterRepository.getById(characterId)?.let { card ->
                    _characterCard.value = card
                    name = card.name
                    greeting = card.greeting
                    avatar = card.avatar
                    chatBackground = card.chatBackground
                    editMode = card.editMode
                    basicSetting = card.basicSetting
                    freeformCharacterText = card.freeformCharacterText
                    defaultImagePrompt = card.defaultImagePrompt
                    charactersList.clear()
                    charactersList.addAll(card.characters)
                    documentsList.clear()
                    documentsList.addAll(card.customDocuments)
                }
            } else {
                charactersList.add(CharacterInfo.create(""))
            }
        }
    }

    fun saveCharacterCard(onSuccess: () -> Unit) {
        if (validateForSave().isNotEmpty()) return
        _isSaving.value = true

        viewModelScope.launch {
            name = NamePolicy.normalize(name)
            val conflict = characterRepository.getAll().firstOrNull {
                it.id != characterId && NamePolicy.isSame(it.name, name)
            }
            if (conflict != null) {
                _indexingStatus.value = "角色卡名称与“${conflict.name}”冲突"
                _isSaving.value = false
                return@launch
            }
            val card = buildCurrentCard(markDirty = true)
            characterRepository.save(card)
            _characterCard.value = card
            _isSaving.value = false
            startBackgroundIndex(card)
            onSuccess()
        }
    }

    fun validateForSave(): List<String> {
        val errors = mutableListOf<String>()
        if (name.isBlank()) errors += "角色卡名称不能为空。"
        if (greeting.isBlank()) errors += "开场白不能为空。"
        return errors
    }

    fun switchEditMode(target: CharacterEditMode) {
        if (target == editMode) return
        editMode = target
    }

    private fun startBackgroundIndex(card: CharacterCard) {
        indexingJob?.cancel()
        indexingJob = ChatBarApp.instance.applicationScope.launch {
            val total = card.customDocuments.size
            if (total == 0) {
                ChatBarApp.instance.ragRepository.deleteChunksBySource(ChunkSourceType.DOCUMENT, card.id)
                persistRagIndexState(card.id, RagIndexStatus.COMPLETE, 0, 0, "无参考文档，已跳过文档 RAG")
                return@launch
            }
            val embeddingConfig = modelResolver.embeddingModel(settingsRepository.getAppSettings())
            if (embeddingConfig == null) {
                persistRagIndexState(card.id, RagIndexStatus.FAILED, 0, total, "未配置全局嵌入模型，无法建立 RAG 索引")
                return@launch
            }

            try {
                persistRagIndexState(card.id, RagIndexStatus.INDEXING, 0, total, "正在检查文档索引状态")
                val existingChunks = ChatBarApp.instance.ragRepository
                    .getAllChunksForCharacter(card.id)
                    .filter { it.sourceType == ChunkSourceType.DOCUMENT }
                    .groupBy { it.metadata["originalDocId"] }

                val initialDocs = card.customDocuments.map { doc ->
                    val file = File(doc.filePath)
                    if (!file.exists()) {
                        doc.copy(ragStatus = DocumentRagStatus.FAILED.name, ragError = "文件不存在")
                    } else {
                        val contentHash = ragManager.hashContent(file.readText())
                        val indexedHash = existingChunks[doc.id]?.firstOrNull()?.metadata?.get("contentHash")
                            ?: doc.indexedHash
                        val indexedEmbeddingKey = existingChunks[doc.id]?.firstOrNull()?.metadata?.get("embeddingKey")
                        val currentEmbeddingKey = ragManager.embeddingKey(embeddingConfig)
                        if (indexedHash == contentHash && indexedEmbeddingKey == currentEmbeddingKey && !existingChunks[doc.id].isNullOrEmpty()) {
                            doc.copy(
                                contentHash = contentHash,
                                indexedHash = contentHash,
                                ragStatus = DocumentRagStatus.INDEXED.name,
                                ragChunkCount = existingChunks[doc.id]?.size ?: doc.ragChunkCount,
                                ragError = null
                            )
                        } else {
                            doc.copy(
                                contentHash = contentHash,
                                ragStatus = DocumentRagStatus.PENDING.name,
                                ragError = null
                            )
                        }
                    }
                }

                val docsById = initialDocs.associateBy { it.id }.toMutableMap()
                val docsToIndex = initialDocs.filter { it.ragStatus != DocumentRagStatus.INDEXED.name }
                val skipped = total - docsToIndex.size
                val progressMutex = Mutex()
                val semaphore = Semaphore(2)
                var done = skipped
                var failed = initialDocs.count { it.ragStatus == DocumentRagStatus.FAILED.name }

                persistRagIndexSnapshot(
                    card.id,
                    docsById.values.toList(),
                    RagIndexStatus.INDEXING,
                    done,
                    total,
                    "增量索引：跳过 $skipped 个未变化文档，待重建 ${docsToIndex.size} 个"
                )

                docsToIndex.map { doc ->
                    async {
                        semaphore.withPermit {
                            val resultDoc = try {
                                val file = File(doc.filePath)
                                if (!file.exists()) {
                                    doc.copy(ragStatus = DocumentRagStatus.FAILED.name, ragError = "文件不存在")
                                } else {
                                    val result = ragManager.indexDocument(doc, file.readText(), card.id, embeddingConfig)
                                    doc.copy(
                                        contentHash = result.contentHash,
                                        indexedHash = result.contentHash,
                                        ragStatus = DocumentRagStatus.INDEXED.name,
                                        ragChunkCount = result.chunkCount,
                                        ragIndexedAt = System.currentTimeMillis(),
                                        ragError = null
                                    )
                                }
                            } catch (e: Exception) {
                                doc.copy(ragStatus = DocumentRagStatus.FAILED.name, ragError = e.message)
                            }

                            progressMutex.withLock {
                                docsById[resultDoc.id] = resultDoc
                                done++
                                if (resultDoc.ragStatus == DocumentRagStatus.FAILED.name) failed++
                                val status = if (failed > 0) RagIndexStatus.FAILED else RagIndexStatus.INDEXING
                                val message = "索引进度：$done/$total，跳过 $skipped，失败 $failed"
                                _indexingStatus.value = message
                                if (done % 3 == 0 || done == total || resultDoc.ragStatus == DocumentRagStatus.FAILED.name) {
                                    persistRagIndexSnapshot(card.id, docsById.values.toList(), status, done, total, message)
                                }
                            }
                        }
                    }
                }.awaitAll()

                val finalStatus = if (failed > 0) RagIndexStatus.FAILED else RagIndexStatus.COMPLETE
                persistRagIndexSnapshot(
                    card.id,
                    docsById.values.toList(),
                    finalStatus,
                    done,
                    total,
                    if (failed > 0) "RAG 索引完成但有 $failed 个文档失败" else "RAG 索引完成：跳过 $skipped 个未变化文档"
                )
            } catch (e: Exception) {
                persistRagIndexState(card.id, RagIndexStatus.FAILED, 0, total, "RAG 索引失败: ${e.message}")
            }
        }
    }

    private suspend fun persistRagIndexState(
        cardId: String,
        status: RagIndexStatus,
        done: Int,
        total: Int,
        message: String
    ) {
        val current = characterRepository.getById(cardId) ?: return
        val updated = current.copy(
            ragIndexStatus = status.name,
            ragIndexDone = done,
            ragIndexTotal = total,
            ragIndexMessage = message,
            ragIndexedAt = if (status == RagIndexStatus.COMPLETE) System.currentTimeMillis() else current.ragIndexedAt
        )
        characterRepository.save(updated)
        _characterCard.value = updated
        _indexingStatus.value = message
    }

    private suspend fun persistRagIndexSnapshot(
        cardId: String,
        docs: List<DocumentInfo>,
        status: RagIndexStatus,
        done: Int,
        total: Int,
        message: String
    ) {
        val current = characterRepository.getById(cardId) ?: return
        val orderedDocs = current.customDocuments.map { old -> docs.firstOrNull { it.id == old.id } ?: old }
        val updated = current.copy(
            customDocuments = orderedDocs,
            ragIndexStatus = status.name,
            ragIndexDone = done,
            ragIndexTotal = total,
            ragIndexMessage = message,
            ragIndexedAt = if (status == RagIndexStatus.COMPLETE) System.currentTimeMillis() else current.ragIndexedAt
        )
        characterRepository.save(updated)
        _characterCard.value = updated
        _indexingStatus.value = message
        withContext(Dispatchers.Main) {
            if (_characterCard.value?.id == cardId) {
                documentsList.clear()
                documentsList.addAll(orderedDocs)
            }
        }
    }

    fun addDocument(fileName: String, content: String) {
        viewModelScope.launch {
            val docDir = File(ChatBarApp.instance.filesDir, "documents")
            if (!docDir.exists()) docDir.mkdirs()

            val localFile = File(docDir, "${System.currentTimeMillis()}_$fileName")
            localFile.writeText(content)

            documentsList.add(DocumentInfo.create(fileName, localFile.absolutePath, "txt"))
            saveCharacterCardCurrentState()
            markRagIndexDirty("文档已保存，保存角色卡后将重建 RAG 索引")
        }
    }

    private suspend fun saveCharacterCardCurrentState() {
        val card = buildCurrentCard(markDirty = true)
        characterRepository.save(card)
        _characterCard.value = card
    }

    private fun buildCurrentCard(markDirty: Boolean): CharacterCard {
        val now = System.currentTimeMillis()
        val base = _characterCard.value
        val dirtyStatus = if (markDirty) RagIndexStatus.NOT_INDEXED.name else base?.ragIndexStatus ?: RagIndexStatus.NOT_INDEXED.name
        val dirtyMessage = if (markDirty) "RAG 索引待重建" else base?.ragIndexMessage
        return base?.copy(
            name = name,
            greeting = greeting,
            avatar = avatar,
            chatBackground = chatBackground,
            editMode = editMode,
            basicSetting = basicSetting,
            freeformCharacterText = freeformCharacterText,
            defaultImagePrompt = defaultImagePrompt,
            characters = charactersList.toList(),
            customDocuments = documentsList.toList(),
            ragIndexStatus = dirtyStatus,
            ragIndexDone = if (markDirty) 0 else base.ragIndexDone,
            ragIndexTotal = documentsList.size,
            ragIndexMessage = dirtyMessage,
            ragIndexedAt = if (markDirty) null else base.ragIndexedAt,
            updatedAt = now
        ) ?: CharacterCard(
            id = characterId ?: java.util.UUID.randomUUID().toString(),
            name = name,
            greeting = greeting,
            avatar = avatar,
            chatBackground = chatBackground,
            editMode = editMode,
            basicSetting = basicSetting,
            freeformCharacterText = freeformCharacterText,
            defaultImagePrompt = defaultImagePrompt,
            characters = charactersList.toList(),
            customDocuments = documentsList.toList(),
            ragIndexStatus = dirtyStatus,
            ragIndexDone = 0,
            ragIndexTotal = documentsList.size,
            ragIndexMessage = dirtyMessage,
            ragIndexedAt = null,
            createdAt = now,
            updatedAt = now
        )
    }

    private suspend fun markRagIndexDirty(message: String) {
        val current = _characterCard.value ?: return
        val updated = current.copy(
            ragIndexStatus = RagIndexStatus.NOT_INDEXED.name,
            ragIndexDone = 0,
            ragIndexTotal = documentsList.size,
            ragIndexMessage = message,
            ragIndexedAt = null
        )
        characterRepository.save(updated)
        _characterCard.value = updated
        _indexingStatus.value = message
    }

    fun deleteDocument(doc: DocumentInfo) {
        viewModelScope.launch {
            File(doc.filePath).delete()
            documentsList.remove(doc)
            ChatBarApp.instance.ragRepository.deleteChunksByDocumentId(doc.id)
            saveCharacterCardCurrentState()
            markRagIndexDirty("文档已删除，保存角色卡后将重建 RAG 索引")
        }
    }

    fun clearAllDocuments() {
        viewModelScope.launch {
            indexingJob?.cancel()
            val docs = documentsList.toList()
            documentsList.clear()
            val now = System.currentTimeMillis()
            val current = _characterCard.value
            if (current != null) {
                val updated = current.copy(
                    customDocuments = emptyList(),
                    ragIndexStatus = RagIndexStatus.COMPLETE.name,
                    ragIndexDone = 0,
                    ragIndexTotal = 0,
                    ragIndexMessage = "已清空当前角色卡文档，正在后台清理 RAG 索引",
                    ragIndexedAt = now,
                    updatedAt = now
                )
                characterRepository.save(updated)
                _characterCard.value = updated
            } else {
                saveCharacterCardCurrentState()
            }
            _indexingStatus.value = "已清空当前角色卡文档，正在后台清理 RAG 索引"

            ChatBarApp.instance.applicationScope.launch {
                for (doc in docs) {
                    File(doc.filePath).delete()
                    ChatBarApp.instance.ragRepository.deleteChunksByDocumentId(doc.id)
                }
                _indexingStatus.value = "当前角色卡文档和对应 RAG 索引已清空"
            }
        }
    }

    fun importDocumentsFromFolder(treeUri: Uri) {
        viewModelScope.launch {
            _isSaving.value = true
            try {
                val context = ChatBarApp.instance
                val contentResolver = context.contentResolver
                val docId = DocumentsContract.getTreeDocumentId(treeUri)
                val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
                val projection = arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                )
                val filesToProcess = mutableListOf<Pair<Uri, String>>()

                contentResolver.query(childrenUri, projection, null, null, null)?.use {
                    val idCol = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DOCUMENT_ID)
                    val nameCol = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                    val mimeCol = it.getColumnIndexOrThrow(DocumentsContract.Document.COLUMN_MIME_TYPE)
                    while (it.moveToNext()) {
                        val mimeType = it.getString(mimeCol)
                        if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) continue
                        val displayName = it.getString(nameCol) ?: "unknown"
                        val lowerName = displayName.lowercase()
                        if (lowerName.endsWith(".txt") || lowerName.endsWith(".md") || lowerName.endsWith(".json")) {
                            val childId = it.getString(idCol)
                            filesToProcess.add(DocumentsContract.buildDocumentUriUsingTree(treeUri, childId) to displayName)
                        }
                    }
                }

                if (filesToProcess.isNotEmpty()) {
                    val docDir = File(context.filesDir, "documents")
                    if (!docDir.exists()) docDir.mkdirs()

                    filesToProcess.forEachIndexed { index, (fileUri, displayName) ->
                        try {
                            _indexingStatus.value = "正在导入 ${index + 1}/${filesToProcess.size}: $displayName"
                            val content = contentResolver.openInputStream(fileUri)?.bufferedReader()?.use { it.readText() }
                            if (!content.isNullOrBlank()) {
                                val localFile = File(docDir, "${System.currentTimeMillis()}_$displayName")
                                localFile.writeText(content)
                                val extension = displayName.substringAfterLast('.', "txt")
                                documentsList.add(DocumentInfo.create(displayName, localFile.absolutePath, extension))
                            }
                        } catch (e: Exception) {
                            _indexingStatus.value = "读取文档 [$displayName] 失败: ${e.message}"
                        }
                    }

                    saveCharacterCardCurrentState()
                    markRagIndexDirty("已导入 ${filesToProcess.size} 个文档，保存角色卡后将重建 RAG 索引")
                }
            } catch (e: Exception) {
                _indexingStatus.value = "批量导入失败: ${e.message}"
            } finally {
                _isSaving.value = false
            }
        }
    }

    fun copyUriToLocalFile(uri: Uri, onSuccess: (String) -> Unit) {
        viewModelScope.launch {
            _isSaving.value = true
            try {
                val context = ChatBarApp.instance
                val contentResolver = context.contentResolver
                val imagesDir = File(context.filesDir, "images")
                if (!imagesDir.exists()) imagesDir.mkdirs()

                val extension = when (contentResolver.getType(uri)) {
                    "image/png" -> "png"
                    "image/gif" -> "gif"
                    "image/webp" -> "webp"
                    else -> "jpg"
                }

                val localFile = File(imagesDir, "img_${System.currentTimeMillis()}.$extension")
                contentResolver.openInputStream(uri)?.use { input ->
                    localFile.outputStream().use { output -> input.copyTo(output) }
                }
                onSuccess(localFile.absolutePath)
            } catch (_: Exception) {
            } finally {
                _isSaving.value = false
            }
        }
    }
}
