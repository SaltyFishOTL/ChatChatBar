package com.example.chatbar.data.repository

import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.MemoryCommit
import com.example.chatbar.data.local.entity.MemoryCommitJournal
import com.example.chatbar.data.local.entity.MemoryCompressionTransaction
import com.example.chatbar.data.local.entity.MemoryNode
import com.example.chatbar.data.local.entity.MemoryPageState
import com.example.chatbar.data.local.entity.MemorySessionSnapshot
import com.example.chatbar.data.local.entity.MemorySessionState
import com.example.chatbar.data.local.entity.MemorySnapshot
import com.example.chatbar.data.local.entity.MemorySourceRepairStatus
import com.example.chatbar.data.local.entity.MemoryTier
import com.example.chatbar.data.local.entity.MemoryTierRevision

class MemoryRepository(private val storage: JsonFileStorage) {
    suspend fun getNode(id: String): MemoryNode? =
        storage.loadEntity(NODE_TYPE, id, MemoryNode.serializer())

    suspend fun getNodes(ids: List<String>): List<MemoryNode> = ids.mapNotNull { getNode(it) }

    suspend fun getReachableNodes(rootIds: List<String>): List<MemoryNode> {
        val result = linkedMapOf<String, MemoryNode>()
        val pending = ArrayDeque(rootIds.distinct())
        while (pending.isNotEmpty()) {
            val id = pending.removeFirst()
            if (id in result) continue
            val node = getNode(id) ?: continue
            result[id] = node
            node.childIds.forEach(pending::addLast)
        }
        return result.values.toList()
    }

    suspend fun getNodesForSession(sessionId: String): List<MemoryNode> =
        storage.query(NODE_TYPE, MemoryNode.serializer()) { it.sessionId == sessionId }

    suspend fun saveNodes(nodes: List<MemoryNode>) {
        if (nodes.isEmpty()) return
        storage.saveAll(NODE_TYPE, nodes.associateBy { it.id }, MemoryNode.serializer())
    }

    suspend fun getState(sessionId: String): MemorySessionState? {
        recoverJournals(sessionId)
        return storage.loadEntity(STATE_TYPE, sessionId, MemorySessionState.serializer())
    }

    suspend fun saveState(state: MemorySessionState) {
        storage.saveEntity(STATE_TYPE, state.sessionId, state, MemorySessionState.serializer())
    }

    /** Crash-safe multi-file commit. State pointer is always written last. */
    suspend fun commitStateLast(
        expectedStateRevision: Long,
        nextState: MemorySessionState,
        nodes: List<MemoryNode> = emptyList(),
        revisions: List<MemoryTierRevision> = emptyList(),
        transactions: List<MemoryCompressionTransaction> = emptyList()
    ) {
        val journal = MemoryCommitJournal(
            id = MemoryCommitJournal.newId(),
            sessionId = nextState.sessionId,
            expectedStateRevision = expectedStateRevision,
            nodes = nodes,
            revisions = revisions,
            transactions = transactions,
            nextState = nextState
        )
        storage.saveEntity(JOURNAL_TYPE, journal.id, journal, MemoryCommitJournal.serializer())
        applyJournal(journal)
        storage.deleteEntity<MemoryCommitJournal>(JOURNAL_TYPE, journal.id)
    }

    private suspend fun recoverJournals(sessionId: String) {
        val journals = storage.query(JOURNAL_TYPE, MemoryCommitJournal.serializer()) {
            it.sessionId == sessionId
        }.sortedBy { it.createdAt }
        for (journal in journals) {
            val current = storage.loadEntity(STATE_TYPE, sessionId, MemorySessionState.serializer())
            when (current?.revision) {
                journal.expectedStateRevision,
                journal.nextState.revision -> applyJournal(journal)
                else -> Unit
            }
            storage.deleteEntity<MemoryCommitJournal>(JOURNAL_TYPE, journal.id)
        }
    }

    private suspend fun applyJournal(journal: MemoryCommitJournal) {
        saveNodes(journal.nodes)
        saveRevisions(journal.revisions)
        journal.transactions.forEach { saveTransaction(it) }
        storage.saveEntity(
            STATE_TYPE,
            journal.sessionId,
            journal.nextState,
            MemorySessionState.serializer()
        )
    }

    suspend fun getRevision(id: String): MemoryTierRevision? =
        storage.loadEntity(REVISION_TYPE, id, MemoryTierRevision.serializer())

    suspend fun saveRevision(revision: MemoryTierRevision) {
        storage.saveEntity(REVISION_TYPE, revision.id, revision, MemoryTierRevision.serializer())
    }

    suspend fun saveRevisions(revisions: List<MemoryTierRevision>) {
        if (revisions.isEmpty()) return
        storage.saveAll(
            REVISION_TYPE,
            revisions.associateBy { it.id },
            MemoryTierRevision.serializer()
        )
    }

    suspend fun history(
        sessionId: String,
        tier: MemoryTier,
        limit: Int = Int.MAX_VALUE
    ): List<MemoryTierRevision> {
        if (limit <= 0) return emptyList()
        val state = getState(sessionId) ?: return emptyList()
        var revisionId = state.page(tier).currentRevisionId
        val visited = mutableSetOf<String>()
        val result = mutableListOf<MemoryTierRevision>()
        while (revisionId != null && result.size < limit) {
            check(visited.add(revisionId)) { "记忆revision历史存在循环：$revisionId" }
            val revision = getRevision(revisionId) ?: break
            check(revision.sessionId == sessionId && revision.tier == tier) {
                "记忆revision历史跨会话或跨分页"
            }
            if (revision.visible) result += revision
            revisionId = revision.parentRevisionId
        }
        return result
    }

    suspend fun allRevisions(sessionId: String): List<MemoryTierRevision> =
        storage.query(REVISION_TYPE, MemoryTierRevision.serializer()) { it.sessionId == sessionId }
            .sortedBy { it.createdAt }

    suspend fun saveTransaction(transaction: MemoryCompressionTransaction) {
        storage.saveEntity(
            TRANSACTION_TYPE,
            transaction.id,
            transaction,
            MemoryCompressionTransaction.serializer()
        )
    }

    suspend fun getTransaction(id: String): MemoryCompressionTransaction? =
        storage.loadEntity(
            TRANSACTION_TYPE,
            id,
            MemoryCompressionTransaction.serializer()
        )

    /** 从最近物化点应用增删delta。 */
    suspend fun materializeRevision(revisionId: String): List<String> {
        val visiting = mutableSetOf<String>()
        suspend fun materialize(id: String): List<String> {
            check(visiting.add(id)) { "记忆revision存在循环：$id" }
            val revision = getRevision(id) ?: error("记忆revision不存在：$id")
            revision.snapshotNodeIds?.let { snapshot ->
                visiting.remove(id)
                return snapshot
            }
            val base = revision.parentRevisionId
                ?.let { materialize(it) }
                .orEmpty()
            val removed = revision.removedNodeIds.toSet()
            val result = (base.filterNot { it in removed } + revision.addedNodeIds).distinct()
            visiting.remove(id)
            return result
        }
        return materialize(revisionId)
    }

    suspend fun snapshot(sessionId: String): MemorySnapshot? {
        val state = getState(sessionId) ?: return null
        val reachable = getReachableNodes(state.activeNodeIds + state.legacyReferenceNodeIds)
        return MemorySnapshot(
            state = state.toSnapshot(),
            nodes = reachable
        )
    }

    /** v2草稿SaveSlot兼容。 */
    suspend fun snapshotLegacyCommit(commitId: String): MemorySnapshot? {
        val commit = getLegacyCommit(commitId) ?: return null
        val all = getNodesForSession(commit.sessionId).associateBy { it.id }
        val reachable = linkedMapOf<String, MemoryNode>()
        fun visit(id: String) {
            if (id in reachable) return
            val node = all[id] ?: return
            reachable[id] = node
            node.childIds.forEach(::visit)
        }
        (commit.activeNodeIds + commit.legacyReferenceNodeIds).forEach(::visit)
        return MemorySnapshot(legacyCommit = commit, nodes = reachable.values.toList())
    }

    // ===== v2草稿兼容读取；迁移完成后不再创建 =====

    suspend fun getLegacyCommit(id: String): MemoryCommit? =
        storage.loadEntity(LEGACY_COMMIT_TYPE, id, MemoryCommit.serializer())

    suspend fun legacyHistory(sessionId: String): List<MemoryCommit> =
        storage.query(LEGACY_COMMIT_TYPE, MemoryCommit.serializer()) { it.sessionId == sessionId }
            .sortedBy { it.createdAt }

    suspend fun deleteForSession(sessionId: String) {
        storage.deleteWhere(NODE_TYPE, MemoryNode.serializer()) { it.sessionId == sessionId }
        storage.deleteWhere(STATE_TYPE, MemorySessionState.serializer()) { it.sessionId == sessionId }
        storage.deleteWhere(REVISION_TYPE, MemoryTierRevision.serializer()) { it.sessionId == sessionId }
        storage.deleteWhere(
            TRANSACTION_TYPE,
            MemoryCompressionTransaction.serializer()
        ) { it.sessionId == sessionId }
        storage.deleteWhere(LEGACY_COMMIT_TYPE, MemoryCommit.serializer()) { it.sessionId == sessionId }
        storage.deleteWhere(JOURNAL_TYPE, MemoryCommitJournal.serializer()) { it.sessionId == sessionId }
    }

    private fun MemorySessionState.toSnapshot(): MemorySessionSnapshot = MemorySessionSnapshot(
        episodeNodeIds = episodePage.activeNodeIds,
        arcNodeIds = arcPage.activeNodeIds,
        eraNodeIds = eraPage.activeNodeIds,
        legacyReferenceNodeIds = legacyReferenceNodeIds,
        head = head,
        timeline = timeline,
        gaps = gaps,
        staleSourcesByNodeId = staleSourcesByNodeId,
        pendingSourceTurnIds = pendingSourceTurnIds,
        episodeCompressionPromptDeclined = episodeCompressionPromptDeclined,
        arcCompressionPromptDeclined = arcCompressionPromptDeclined,
        eraCompressionPromptDeclined = eraCompressionPromptDeclined,
        eraCompressionsSincePrompt = eraCompressionsSincePrompt,
        pendingDecision = pendingDecision,
        memoryWasEnabled = memoryWasEnabled,
        disabledAfterSourceOrder = disabledAfterSourceOrder,
        recordingStartsAfterSourceOrder = recordingStartsAfterSourceOrder,
        gapRetentionVersion = gapRetentionVersion,
        backfill = backfill.copy(
            status = if (backfill.status == com.example.chatbar.data.local.entity.MemoryBackfillStatus.RUNNING) {
                com.example.chatbar.data.local.entity.MemoryBackfillStatus.PAUSED
            } else {
                backfill.status
            }
        ),
        sourceRepair = sourceRepair.copy(
            status = if (sourceRepair.status == MemorySourceRepairStatus.RUNNING) {
                MemorySourceRepairStatus.PAUSED
            } else {
                sourceRepair.status
            }
        ),
        archiveFailure = archiveFailure,
        headFailure = headFailure
    )

    companion object {
        private const val NODE_TYPE = "memory_nodes"
        private const val STATE_TYPE = "memory_states"
        private const val REVISION_TYPE = "memory_tier_revisions"
        private const val TRANSACTION_TYPE = "memory_compression_transactions"
        private const val LEGACY_COMMIT_TYPE = "memory_commits"
        private const val JOURNAL_TYPE = "memory_commit_journals"
        const val MATERIALIZED_SNAPSHOT_INTERVAL = 20
    }
}
