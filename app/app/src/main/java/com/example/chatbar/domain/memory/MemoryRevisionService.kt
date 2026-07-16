package com.example.chatbar.domain.memory

import com.example.chatbar.data.local.entity.MemoryAuthor
import com.example.chatbar.data.local.entity.MemoryPageState
import com.example.chatbar.data.local.entity.MemoryRevisionOperation
import com.example.chatbar.data.local.entity.MemorySessionState
import com.example.chatbar.data.local.entity.MemoryTier
import com.example.chatbar.data.local.entity.MemoryTierRevision
import com.example.chatbar.data.repository.MemoryRepository

data class MemoryCheckpointResult(
    val state: MemorySessionState,
    val revision: MemoryTierRevision
)

class MemoryRevisionService(private val repository: MemoryRepository) {
    fun appendPure(
        state: MemorySessionState,
        tier: MemoryTier,
        nodeId: String
    ): MemorySessionState {
        val page = state.page(tier)
        check(nodeId !in page.activeNodeIds) { "节点已经处于活跃分页" }
        return state.replacePage(
            page.copy(
                activeNodeIds = page.activeNodeIds + nodeId,
                uncheckpointedAddedNodeIds = page.uncheckpointedAddedNodeIds + nodeId
            )
        ).bump()
    }

    suspend fun syncPureAdditions(
        state: MemorySessionState,
        tier: MemoryTier
    ): MemorySessionState {
        val page = state.page(tier)
        if (page.uncheckpointedAddedNodeIds.isEmpty()) return state
        val pendingSet = page.uncheckpointedAddedNodeIds.toSet()
        val expectedParentNodeIds = page.activeNodeIds.filterNot { it in pendingSet }
        val materializedParentNodeIds = page.currentRevisionId
            ?.let { repository.materializeRevision(it) }
            .orEmpty()
        val requiresSnapshot = materializedParentNodeIds != expectedParentNodeIds
        val revision = MemoryTierRevision(
            id = MemoryTierRevision.newId(),
            sessionId = state.sessionId,
            tier = tier,
            parentRevisionId = page.currentRevisionId,
            operation = MemoryRevisionOperation.PURE_APPEND_SYNC,
            author = MemoryAuthor.AI,
            addedNodeIds = if (requiresSnapshot) emptyList() else page.uncheckpointedAddedNodeIds,
            snapshotNodeIds = page.activeNodeIds.takeIf { requiresSnapshot },
            visible = false
        )
        repository.saveRevision(revision)
        return state.replacePage(
            page.copy(
                currentRevisionId = revision.id,
                uncheckpointedAddedNodeIds = emptyList(),
                revisionSequence = page.revisionSequence + 1
            )
        ).bump()
    }

    suspend fun checkpoint(
        initialState: MemorySessionState,
        tier: MemoryTier,
        afterNodeIds: List<String>,
        operation: MemoryRevisionOperation,
        author: MemoryAuthor,
        modelId: String? = null,
        transactionId: String? = null,
        affectedSourceTurnIds: List<String> = emptyList(),
        visible: Boolean = true
    ): MemoryCheckpointResult {
        var state = syncPureAdditions(initialState, tier)
        var page = state.page(tier)
        if (page.currentRevisionId == null) {
            val baseline = MemoryTierRevision(
                id = MemoryTierRevision.newId(),
                sessionId = state.sessionId,
                tier = tier,
                operation = MemoryRevisionOperation.PURE_APPEND_SYNC,
                author = author,
                snapshotNodeIds = page.activeNodeIds,
                visible = false
            )
            repository.saveRevision(baseline)
            state = state.replacePage(
                page.copy(
                    currentRevisionId = baseline.id,
                    revisionSequence = page.revisionSequence + 1
                )
            ).bump()
            page = state.page(tier)
        }
        val before = page.activeNodeIds
        val materialize = (page.revisionSequence + 1) %
            MemoryRepository.MATERIALIZED_SNAPSHOT_INTERVAL == 0L
        val addedNodeIds = afterNodeIds.filterNot { it in before }
        val removedNodeIds = before.filterNot { it in afterNodeIds }
        val deltaResult = (before.filterNot { it in removedNodeIds.toSet() } + addedNodeIds)
            .distinct()
        val requiresOrderSnapshot = deltaResult != afterNodeIds
        val materializedParentNodeIds = requireNotNull(page.currentRevisionId)
            .let { repository.materializeRevision(it) }
        val storeSnapshot = materialize ||
            requiresOrderSnapshot ||
            materializedParentNodeIds != before
        val revision = MemoryTierRevision(
            id = MemoryTierRevision.newId(),
            sessionId = state.sessionId,
            tier = tier,
            parentRevisionId = page.currentRevisionId,
            operation = operation,
            author = author,
            modelId = modelId,
            transactionId = transactionId,
            addedNodeIds = if (storeSnapshot) emptyList() else addedNodeIds,
            removedNodeIds = if (storeSnapshot) emptyList() else removedNodeIds,
            snapshotNodeIds = afterNodeIds.takeIf { storeSnapshot },
            affectedSourceTurnIds = affectedSourceTurnIds,
            visible = visible
        )
        repository.saveRevision(revision)
        val nextState = state.replacePage(
            MemoryPageState(
                tier = tier,
                activeNodeIds = afterNodeIds,
                currentRevisionId = revision.id,
                uncheckpointedAddedNodeIds = emptyList(),
                revisionSequence = page.revisionSequence + 1
            )
        ).bump()
        return MemoryCheckpointResult(nextState, revision)
    }

    suspend fun restore(
        state: MemorySessionState,
        revisionId: String,
        modelId: String? = null
    ): MemoryCheckpointResult {
        val historical = repository.getRevision(revisionId) ?: error("历史版本不存在")
        check(historical.sessionId == state.sessionId) { "历史版本不属于当前会话" }
        val materializedTarget = repository.materializeRevision(revisionId)
        val targetNodes = repository.getNodes(materializedTarget).associateBy { it.id }
        val target = MemoryPageOrderPolicy.orderedNodeIdsOrNull(
            nodeIds = materializedTarget,
            nodesById = targetNodes,
            timeline = state.timeline
        ) ?: materializedTarget
        val synced = syncPureAdditions(state, historical.tier)
        val safety = checkpoint(
            initialState = synced,
            tier = historical.tier,
            afterNodeIds = synced.page(historical.tier).activeNodeIds,
            operation = MemoryRevisionOperation.PRE_RESTORE_CHECKPOINT,
            author = MemoryAuthor.RESTORE,
            modelId = modelId,
            visible = true
        )
        return checkpoint(
            initialState = safety.state,
            tier = historical.tier,
            afterNodeIds = target,
            operation = MemoryRevisionOperation.RESTORE,
            author = MemoryAuthor.RESTORE,
            modelId = modelId,
            affectedSourceTurnIds = historical.affectedSourceTurnIds
        )
    }

    private fun MemorySessionState.bump(): MemorySessionState = copy(
        revision = revision + 1,
        updatedAt = System.currentTimeMillis()
    )
}
