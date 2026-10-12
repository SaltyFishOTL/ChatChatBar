package com.example.chatbar.domain.backup

import java.util.UUID

/** Typed namespaces avoid confusing equal IDs belonging to different stores. */
internal class BackupMergeIdentity(private val copy: Boolean) {
    private val messagePrefix = UUID.randomUUID().toString()
    private val ids = mutableMapOf<Pair<String, String>, String>()
    fun bind(type: String, old: String, target: String) { ids[space(type) to old] = target }
    fun value(type: String, old: String): String {
        if (old.isBlank()) return old
        return ids[space(type) to old] ?: if (copy) ids.getOrPut(space(type) to old) { fresh(type, old) } else old
    }
    fun fresh(type: String, old: String): String = if (type == "chat_messages" && old.matches(Regex("[A-Za-z0-9._-]{1,120}")))
        "$messagePrefix-$old" else UUID.randomUUID().toString()
    fun space(type: String) = when (type) {
        "retrieval_model_config" -> "model_configs"
        "embedding_model_config" -> "embedding_configs"
        else -> type
    }
    fun fieldSpace(record: BackupMergeRecord, fields: List<String>): String? {
        val field = fields.lastOrNull() ?: return null
        if (fields.any { it in setOf("extensions", "customParams", "voice", "initialResearch", "research") }) return null
        if (field == "id") {
            if (fields.size == 1) return record.type.takeUnless { record.singleton && record.type !in setOf("retrieval_model_config", "embedding_model_config") }
            return when (fields.dropLast(1).lastOrNull()) {
                "characters" -> "characters".takeIf { record.type == "character_cards" || "characterPayload" in fields }
                "customDocuments" -> "documents"
                "entries" -> "world_entries".takeIf { record.type == "world_books" || "worldBookPayload" in fields || "characterBook" in fields }
                "messages" -> "chat_messages"
                "voiceMessages" -> "generated_voice_messages"
                "vectorChunks" -> "vector_chunks"
                "nodes" -> "memory_nodes"
                "revisions" -> "memory_tier_revisions"
                "transactions" -> "memory_compression_transactions"
                "commit" -> "memory_commits"
                "characterPayload" -> "character_cards"
                "formatPayload" -> "format_cards"
                "worldBookPayload", "characterBook" -> "world_books"
                else -> null
            }
        }
        return when (field) {
            "sessionId" -> "chat_sessions"
            "characterCardId", "importedCharacterCardId" -> "character_cards"
            "characterId", "senderCharacterId" -> "characters"
            "messageId", "messageIds", "generatedFromMessageId", "anchorMessageId", "longTermMemoryUpdatedThroughMessageId" -> "chat_messages"
            "modelId", "imageModelId", "defaultModelId", "defaultImageModelId", "visionModelId", "formatRepairModelId", "voiceTagModelId", "aiDesignModelId", "designModelId" -> "model_configs"
            "defaultEmbeddingId" -> "embedding_configs"
            "formatCardId", "defaultFormatCardId" -> "format_cards"
            "worldBookIds", "extraWorldBookIds", "boundWorldBookId" -> "world_books"
            "entryId" -> "world_entries"
            "postId" -> "moment_posts"
            "currentConversationId" -> "novelai_design_conversations"
            "targetId" -> when (record.text("entityType")) {
                "CHARACTER_CARD" -> "character_cards"; "FORMAT_CARD" -> "format_cards"; "WORLD_BOOK" -> "world_books"; else -> null
            }
            "draftSessionId" -> "draft_sessions"
            "pendingDeletedDocumentIds" -> "documents"
            "childId", "childIds", "activeNodeIds", "uncheckpointedAddedNodeIds", "legacyReferenceNodeIds", "pendingRootNodeIds",
            "addedNodeIds", "removedNodeIds", "snapshotNodeIds", "consumedNodeIds", "resultNodeId", "episodeNodeIds", "arcNodeIds", "eraNodeIds",
            "activeEpisodeNodeIds", "activeArcNodeIds", "activeEraNodeIds", "deleteNodeIds" -> "memory_nodes"
            "currentRevisionId", "parentRevisionId", "sourceRevisionId", "targetRevisionId", "deleteRevisionIds" -> "memory_tier_revisions"
            "transactionId", "deleteTransactionIds" -> "memory_compression_transactions"
            "memoryHeadCommitId", "parentCommitId" -> "memory_commits"
            "sourceId" -> when {
                fields.size == 1 && record.type == "vector_chunks" -> when (record.text("sourceType")) {
                    "CHAT_MEMORY" -> "chat_sessions"; "CHARACTER_SETTING" -> "character_cards"; "DOCUMENT" -> "documents"; else -> null
                }
                "vectorChunks" in fields -> "chat_sessions"
                else -> null // Coverage references are handled by the memory proof rewriter.
            }
            else -> null // Source-turn, alternative-version and voice-anchor IDs remain session scoped.
        }
    }
    fun string(record: BackupMergeRecord, text: String, fields: List<String>): String {
        val type = fieldSpace(record, fields) ?: return text
        if (fields == listOf("metadata", "messageIds")) return text.split(',').joinToString(",") { value(type, it) }
        return value(type, text)
    }
    fun key(text: String, fields: List<String>): String = when (fields.lastOrNull()) {
        "timedWorldInfo" -> value("world_entries", text)
        "staleSourcesByNodeId" -> value("memory_nodes", text)
        else -> text
    }
    companion object {
        val fields = setOf("id", "sessionId", "characterCardId", "importedCharacterCardId", "characterId", "senderCharacterId", "messageId", "messageIds",
            "generatedFromMessageId", "anchorMessageId", "longTermMemoryUpdatedThroughMessageId", "modelId", "imageModelId", "defaultModelId",
            "defaultImageModelId", "visionModelId", "formatRepairModelId", "voiceTagModelId", "aiDesignModelId", "designModelId", "defaultEmbeddingId",
            "formatCardId", "defaultFormatCardId", "worldBookIds", "extraWorldBookIds", "boundWorldBookId", "entryId", "postId", "currentConversationId",
            "targetId", "draftSessionId", "pendingDeletedDocumentIds", "childId", "childIds", "activeNodeIds", "uncheckpointedAddedNodeIds",
            "legacyReferenceNodeIds", "pendingRootNodeIds", "addedNodeIds", "removedNodeIds", "snapshotNodeIds", "consumedNodeIds", "resultNodeId",
            "episodeNodeIds", "arcNodeIds", "eraNodeIds", "activeEpisodeNodeIds", "activeArcNodeIds", "activeEraNodeIds", "deleteNodeIds", "currentRevisionId",
            "parentRevisionId", "sourceRevisionId", "targetRevisionId", "deleteRevisionIds", "transactionId", "deleteTransactionIds", "memoryHeadCommitId",
            "parentCommitId", "sourceId")
    }
}
