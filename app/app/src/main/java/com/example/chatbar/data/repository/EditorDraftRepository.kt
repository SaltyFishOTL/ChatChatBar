package com.example.chatbar.data.repository

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.EditorDraft
import com.example.chatbar.data.local.entity.EditorDraftMode
import com.example.chatbar.data.local.entity.EditorDraftType
import com.example.chatbar.data.local.entity.FormatCard
import com.example.chatbar.data.local.entity.WorldBook
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.KSerializer
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.security.MessageDigest

class EditorDraftRepository(
    private val storage: JsonFileStorage,
    private val json: Json = storage.json
) {
    companion object {
        private const val ENTITY_TYPE = "edit_drafts"

        fun draftId(type: EditorDraftType, targetId: String?): String {
            val suffix = targetId?.takeIf { it.isNotBlank() } ?: "new"
            return "${type.name.lowercase()}_$suffix"
        }
    }

    private val _drafts = MutableStateFlow<List<EditorDraft>>(emptyList())
    val drafts: Flow<List<EditorDraft>> = _drafts.asStateFlow()

    private var initialized = false

    suspend fun initialize() {
        if (initialized) return
        refreshCache()
        initialized = true
    }

    private suspend fun refreshCache() {
        _drafts.value = storage.loadAll(ENTITY_TYPE, EditorDraft.serializer())
            .sortedByDescending { it.updatedAt }
    }

    suspend fun getAll(): List<EditorDraft> {
        initialize()
        return _drafts.value
    }

    suspend fun getById(id: String): EditorDraft? {
        initialize()
        return storage.loadEntity(ENTITY_TYPE, id, EditorDraft.serializer())
    }

    suspend fun getForTarget(type: EditorDraftType, targetId: String?): EditorDraft? =
        getById(draftId(type, targetId))

    suspend fun getLatestNew(type: EditorDraftType): EditorDraft? =
        getAll().firstOrNull { it.entityType == type && it.isNew }

    suspend fun hasDraft(type: EditorDraftType, targetId: String): Boolean =
        getForTarget(type, targetId) != null

    suspend fun save(draft: EditorDraft): EditorDraft {
        val now = System.currentTimeMillis()
        val normalized = draft.copy(id = draftId(draft.entityType, draft.targetId), updatedAt = now)
        storage.saveEntity(ENTITY_TYPE, normalized.id, normalized, EditorDraft.serializer())
        refreshCache()
        return normalized
    }

    suspend fun delete(id: String) {
        storage.deleteEntity<EditorDraft>(ENTITY_TYPE, id)
        refreshCache()
    }

    suspend fun deleteForTarget(type: EditorDraftType, targetId: String?) {
        delete(draftId(type, targetId))
    }

    fun characterDraft(
        targetId: String?,
        draftSessionId: String,
        payload: CharacterCard,
        base: CharacterCard?,
        draftAssetPaths: List<String>,
        pendingDeletedAssets: List<String>,
        pendingDeletedDocumentIds: List<String>,
        openModalState: String? = null
    ): EditorDraft = EditorDraft(
        id = draftId(EditorDraftType.CHARACTER_CARD, targetId),
        entityType = EditorDraftType.CHARACTER_CARD,
        mode = if (targetId == null) EditorDraftMode.CREATE else EditorDraftMode.EDIT,
        targetId = targetId,
        draftSessionId = draftSessionId,
        baseUpdatedAt = base?.updatedAt,
        baseHash = base?.let { hash(it, CharacterCard.serializer()) },
        characterPayload = payload,
        draftAssetPaths = draftAssetPaths.distinct(),
        pendingDeletedAssets = pendingDeletedAssets.distinct(),
        pendingDeletedDocumentIds = pendingDeletedDocumentIds.distinct(),
        openModalState = openModalState
    )

    fun formatDraft(
        targetId: String?,
        draftSessionId: String,
        payload: FormatCard,
        base: FormatCard?,
        openModalState: String? = null
    ): EditorDraft = EditorDraft(
        id = draftId(EditorDraftType.FORMAT_CARD, targetId),
        entityType = EditorDraftType.FORMAT_CARD,
        mode = if (targetId == null) EditorDraftMode.CREATE else EditorDraftMode.EDIT,
        targetId = targetId,
        draftSessionId = draftSessionId,
        baseUpdatedAt = base?.createdAt,
        baseHash = base?.let { hash(it, FormatCard.serializer()) },
        formatPayload = payload,
        openModalState = openModalState
    )

    fun worldBookDraft(
        targetId: String?,
        draftSessionId: String,
        payload: WorldBook,
        base: WorldBook?,
        openModalState: String? = null
    ): EditorDraft = EditorDraft(
        id = draftId(EditorDraftType.WORLD_BOOK, targetId),
        entityType = EditorDraftType.WORLD_BOOK,
        mode = if (targetId == null) EditorDraftMode.CREATE else EditorDraftMode.EDIT,
        targetId = targetId,
        draftSessionId = draftSessionId,
        baseUpdatedAt = base?.updatedAt,
        baseHash = base?.let { hash(it, WorldBook.serializer()) },
        worldBookPayload = payload,
        openModalState = openModalState
    )

    fun isChanged(base: CharacterCard?, draft: EditorDraft): Boolean =
        base != null && draft.baseHash != null && hash(base, CharacterCard.serializer()) != draft.baseHash

    fun isChanged(base: FormatCard?, draft: EditorDraft): Boolean =
        base != null && draft.baseHash != null && hash(base, FormatCard.serializer()) != draft.baseHash

    fun isChanged(base: WorldBook?, draft: EditorDraft): Boolean =
        base != null && draft.baseHash != null && hash(base, WorldBook.serializer()) != draft.baseHash

    private fun <T> hash(value: T, serializer: KSerializer<T>): String {
        val bytes = json.encodeToString(serializer, value).toByteArray(Charsets.UTF_8)
        return MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { "%02x".format(it) }
    }
}
