package com.example.chatbar.ui.community

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.DocumentRagStatus
import com.example.chatbar.data.local.entity.RagIndexStatus
import com.example.chatbar.domain.card.CharacterCardImportRequest
import com.example.chatbar.domain.card.NamePolicy
import com.example.chatbar.domain.community.CommunityImportPayload
import com.example.chatbar.domain.community.CommunityItem
import com.example.chatbar.domain.community.CommunityItemType
import com.example.chatbar.domain.community.CommunityPendingImport
import com.example.chatbar.domain.community.CommunitySession
import com.example.chatbar.domain.community.CommunityUploadCandidate
import com.example.chatbar.domain.service.AiBackgroundWorkManager
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class CommunityUiState(
    val configured: Boolean = false,
    val items: List<CommunityItem> = emptyList(),
    val loading: Boolean = false,
    val busy: Boolean = false,
    val query: String = "",
    val selectedType: CommunityItemType? = null,
    val uploadOpen: Boolean = false,
    val uploadType: CommunityItemType = CommunityItemType.CHARACTER,
    val selectedLocalId: String? = null,
    val uploadTitle: String = "",
    val uploadDescription: String = "",
    val uploadTags: String = "",
    val pendingImport: CommunityPendingImport? = null,
    val message: String? = null
)

class CommunityViewModel : ViewModel() {
    private val app = ChatBarApp.instance
    private val service = app.communityService
    private val characterRepository = app.characterRepository
    private val formatCardRepository = app.formatCardRepository
    private val worldBookRepository = app.worldBookRepository
    private val characterTransfers = app.characterCardTransferService
    private val formatTransfers = app.formatCardTransferService
    private val worldBookTransfers = app.worldBookTransferService
    private val settingsRepository = app.settingsRepository
    private val modelResolver = app.effectiveModelResolver

    private val _state = MutableStateFlow(CommunityUiState(configured = service.configured))
    val state: StateFlow<CommunityUiState> = _state

    private val _candidates = MutableStateFlow<List<CommunityUploadCandidate>>(emptyList())
    val candidates: StateFlow<List<CommunityUploadCandidate>> = _candidates

    val session: StateFlow<CommunitySession?> = service.session
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), service.session.value)

    init {
        viewModelScope.launch {
            characterRepository.initialize()
            formatCardRepository.initialize()
            worldBookRepository.initialize()
            loadCandidates()
            refresh()
        }
    }

    fun loginUrl(): String = service.discordLoginUrl()

    fun signOut() {
        service.signOut()
        _state.update { it.copy(message = "已退出 Discord") }
    }

    fun refresh() {
        if (!service.configured) {
            _state.update { it.copy(loading = false, message = "请先配置 Supabase") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(loading = true, message = null) }
            runCatching { service.listItems() }.fold(
                onSuccess = { items ->
                    _state.update { it.copy(items = items, loading = false) }
                },
                onFailure = { error ->
                    _state.update { it.copy(loading = false, message = error.message ?: "社区列表加载失败") }
                }
            )
        }
    }

    fun setQuery(value: String) {
        _state.update { it.copy(query = value) }
    }

    fun setTypeFilter(type: CommunityItemType?) {
        _state.update { it.copy(selectedType = type) }
    }

    fun openUpload() {
        if (!service.configured) {
            _state.update { it.copy(message = "请先配置 Supabase") }
            return
        }
        if (service.session.value == null) {
            _state.update { it.copy(message = "Discord 登录后才能上传") }
            return
        }
        _state.update { it.copy(uploadOpen = true, selectedLocalId = null, uploadTitle = "", uploadDescription = "", uploadTags = "") }
        loadCandidates()
    }

    fun closeUpload() {
        _state.update { it.copy(uploadOpen = false) }
    }

    fun setUploadType(type: CommunityItemType) {
        _state.update {
            it.copy(uploadType = type, selectedLocalId = null, uploadTitle = "", uploadDescription = "", uploadTags = "")
        }
        loadCandidates(type)
    }

    fun selectCandidate(candidate: CommunityUploadCandidate) {
        _state.update { it.copy(selectedLocalId = candidate.id, uploadTitle = candidate.title) }
    }

    fun setUploadTitle(value: String) {
        _state.update { it.copy(uploadTitle = value) }
    }

    fun setUploadDescription(value: String) {
        _state.update { it.copy(uploadDescription = value) }
    }

    fun setUploadTags(value: String) {
        _state.update { it.copy(uploadTags = value) }
    }

    fun submitUpload() {
        val snapshot = _state.value
        val localId = snapshot.selectedLocalId
        if (localId == null) {
            _state.update { it.copy(message = "请选择 APP 内已有条目") }
            return
        }
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            runCatching {
                val draft = service.buildDraft(
                    type = snapshot.uploadType,
                    localId = localId,
                    title = snapshot.uploadTitle,
                    description = snapshot.uploadDescription,
                    tags = parseTags(snapshot.uploadTags)
                )
                service.submitDraft(draft)
            }.fold(
                onSuccess = { item ->
                    _state.update {
                        it.copy(
                            busy = false,
                            uploadOpen = false,
                            items = listOf(item) + it.items.filterNot { existing -> existing.id == item.id },
                            message = "已发布：${item.title}"
                        )
                    }
                    loadCandidates(snapshot.uploadType)
                },
                onFailure = { error ->
                    _state.update { it.copy(busy = false, message = error.message ?: "上传失败") }
                }
            )
        }
    }

    fun prepareImport(item: CommunityItem) {
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            runCatching {
                val raw = service.downloadPackage(item)
                when (item.type) {
                    CommunityItemType.CHARACTER -> {
                        val data = characterTransfers.decode(raw)
                        val conflict = characterRepository.getAll().firstOrNull {
                            NamePolicy.isSame(it.name, data.card.name)
                        }
                        CommunityPendingImport(
                            item = item,
                            displayName = data.card.name,
                            conflictId = conflict?.id,
                            conflictName = conflict?.name,
                            payload = CommunityImportPayload.Character(data)
                        )
                    }

                    CommunityItemType.FORMAT -> {
                        val data = formatTransfers.decode(raw)
                        val conflict = formatCardRepository.getAll().firstOrNull {
                            NamePolicy.isSame(it.name, data.name)
                        }
                        CommunityPendingImport(
                            item = item,
                            displayName = data.name,
                            conflictId = conflict?.id,
                            conflictName = conflict?.name,
                            payload = CommunityImportPayload.Format(data)
                        )
                    }

                    CommunityItemType.WORLD_BOOK -> {
                        val data = worldBookTransfers.decode(raw, item.title)
                        val conflict = worldBookRepository.getAll().firstOrNull {
                            NamePolicy.isSame(it.name, data.book.name)
                        }
                        CommunityPendingImport(
                            item = item,
                            displayName = data.book.name,
                            conflictId = conflict?.id,
                            conflictName = conflict?.name,
                            payload = CommunityImportPayload.WorldBook(data)
                        )
                    }
                }
            }.fold(
                onSuccess = { pending ->
                    _state.update { it.copy(busy = false, pendingImport = pending) }
                },
                onFailure = { error ->
                    _state.update { it.copy(busy = false, message = error.message ?: "下载失败") }
                }
            )
        }
    }

    fun dismissImport() {
        _state.update { it.copy(pendingImport = null) }
    }

    fun importPending(asOverwrite: Boolean) {
        val pending = _state.value.pendingImport ?: return
        viewModelScope.launch {
            _state.update { it.copy(busy = true, message = null) }
            runCatching {
                when (val payload = pending.payload) {
                    is CommunityImportPayload.Character -> {
                        val request = CharacterCardImportRequest(payload.data)
                        val card = if (asOverwrite && pending.conflictId != null) {
                            characterTransfers.overwrite(pending.conflictId, request.packageData)
                        } else {
                            characterTransfers.importNew(request.packageData)
                        }
                        startCharacterRagWork(card)
                        card.name
                    }

                    is CommunityImportPayload.Format -> {
                        val card = if (asOverwrite && pending.conflictId != null) {
                            formatTransfers.overwrite(pending.conflictId, payload.data)
                        } else {
                            formatTransfers.importNew(payload.data)
                        }
                        card.name
                    }

                    is CommunityImportPayload.WorldBook -> {
                        val book = if (asOverwrite && pending.conflictId != null) {
                            worldBookTransfers.overwrite(pending.conflictId, payload.data)
                        } else {
                            worldBookTransfers.importNew(payload.data)
                        }
                        book.name
                    }
                }
            }.fold(
                onSuccess = { name ->
                    _state.update {
                        it.copy(
                            busy = false,
                            pendingImport = null,
                            message = "已导入：$name"
                        )
                    }
                    loadCandidates()
                },
                onFailure = { error ->
                    _state.update { it.copy(busy = false, message = error.message ?: "导入失败") }
                }
            )
        }
    }

    fun clearMessage() {
        _state.update { it.copy(message = null) }
    }

    private fun loadCandidates(type: CommunityItemType = _state.value.uploadType) {
        viewModelScope.launch {
            runCatching { service.localCandidates(type) }.onSuccess { _candidates.value = it }
        }
    }

    private fun startCharacterRagWork(card: CharacterCard) {
        if (card.customDocuments.isEmpty()) {
            viewModelScope.launch {
                characterRepository.save(
                    card.copy(
                        ragIndexStatus = RagIndexStatus.COMPLETE.name,
                        ragIndexDone = 0,
                        ragIndexTotal = 0,
                        ragIndexMessage = "无参考文档",
                        ragIndexedAt = System.currentTimeMillis()
                    )
                )
            }
            return
        }
        ChatBarApp.instance.applicationScope.launch {
            try {
                AiBackgroundWorkManager.run(card.id) {
                    val settings = settingsRepository.getAppSettings()
                    val embedding = modelResolver.embeddingModel(settings)
                    if (embedding == null) {
                        characterRepository.save(
                            card.copy(
                                ragIndexStatus = RagIndexStatus.NOT_INDEXED.name,
                                ragIndexMessage = "未配置嵌入模型，参考文档待建立索引"
                            )
                        )
                        _state.update { it.copy(message = "${card.name} 已导入，参考文档待建立索引") }
                        return@run
                    }
                    val indexed = mutableListOf<com.example.chatbar.data.local.entity.DocumentInfo>()
                    val total = card.customDocuments.size
                    for ((index, doc) in card.customDocuments.withIndex()) {
                        val result = runCatching {
                            val rag = ChatBarApp.instance.ragManager.indexDocument(
                                doc,
                                File(doc.filePath).readText(),
                                card.id,
                                embedding
                            )
                            doc.copy(
                                contentHash = rag.contentHash,
                                indexedHash = rag.contentHash,
                                ragStatus = DocumentRagStatus.INDEXED.name,
                                ragChunkCount = rag.chunkCount,
                                ragIndexedAt = System.currentTimeMillis(),
                                ragError = null
                            )
                        }.getOrElse { error ->
                            doc.copy(ragStatus = DocumentRagStatus.FAILED.name, ragError = error.message)
                        }
                        indexed += result
                        val current = characterRepository.getById(card.id)
                        if (current != null) {
                            val failed = indexed.count { it.ragStatus == DocumentRagStatus.FAILED.name }
                            val mergedDocs = current.customDocuments.map { old ->
                                indexed.firstOrNull { it.id == old.id } ?: old
                            }
                            characterRepository.save(
                                current.copy(
                                    customDocuments = mergedDocs,
                                    ragIndexStatus = if (failed > 0) RagIndexStatus.FAILED.name else RagIndexStatus.INDEXING.name,
                                    ragIndexDone = index + 1,
                                    ragIndexTotal = total,
                                    ragIndexMessage = "索引进度：${index + 1}/$total",
                                    ragIndexedAt = null
                                )
                            )
                        }
                    }
                    val updated = characterRepository.getById(card.id) ?: return@run
                    val failed = indexed.count { it.ragStatus == DocumentRagStatus.FAILED.name }
                    val finalDocs = updated.customDocuments.map { old ->
                        indexed.firstOrNull { it.id == old.id } ?: old
                    }
                    characterRepository.save(
                        updated.copy(
                            customDocuments = finalDocs,
                            ragIndexStatus = if (failed == 0) RagIndexStatus.COMPLETE.name else RagIndexStatus.FAILED.name,
                            ragIndexDone = total,
                            ragIndexTotal = total,
                            ragIndexMessage = if (failed == 0) "参考文档索引完成" else "$failed 份文档索引失败",
                            ragIndexedAt = System.currentTimeMillis()
                        )
                    )
                    if (failed > 0) _state.update { it.copy(message = "${card.name}：$failed 份文档索引失败") }
                }
            } catch (error: Exception) {
                _state.update { it.copy(message = "${card.name}：索引失败 - ${error.message}") }
            }
        }
    }

    private fun parseTags(raw: String): List<String> =
        raw.split(Regex("[,，#\\s]+"))
            .map(String::trim)
            .filter(String::isNotBlank)
            .distinct()
            .take(8)
}
