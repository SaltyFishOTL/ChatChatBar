package com.example.chatbar.domain.memory

import com.example.chatbar.data.local.entity.MemoryNode
import com.example.chatbar.data.local.entity.MemoryHead
import com.example.chatbar.data.local.entity.MemorySourceTurnRef
import com.example.chatbar.data.local.entity.MemoryTier
import com.example.chatbar.data.local.entity.MemoryTimelineEntry

data class MemoryDeletionProjection(
    val activeNodeIdsByTier: Map<MemoryTier, List<String>>,
    val nodesById: Map<String, MemoryNode>,
    val createdNodes: List<MemoryNode>,
    val changed: Boolean
)

/**
 * Removes deleted source identities from the immutable memory DAG without rewriting formal text.
 * Unchanged subtrees retain their IDs; every structurally changed node receives a new ID.
 */
object MemoryDeletionProjectionPolicy {
    fun projectHead(
        head: MemoryHead,
        deletedSourceTurnIds: Set<String>,
        timeline: List<MemoryTimelineEntry>
    ): MemoryHead {
        val evidenceIds = buildSet {
            addAll(head.sourceHashes.keys)
            addAll(head.sourceFingerprints.keys)
            head.throughSourceTurnId?.let(::add)
        }
        return if (evidenceIds.any(deletedSourceTurnIds::contains)) {
            MemoryHead(version = head.version + 1)
        } else {
            head.copy(
                throughT = MemoryTimelinePolicy.displayT(head.throughSourceTurnId, timeline)
            )
        }
    }

    fun project(
        activeNodeIdsByTier: Map<MemoryTier, List<String>>,
        nodesById: Map<String, MemoryNode>,
        deletedSourceTurnIds: Set<String>,
        sourceRefsById: Map<String, MemorySourceTurnRef>,
        timeline: List<MemoryTimelineEntry>,
        newNodeId: () -> String = MemoryNode::newId
    ): MemoryDeletionProjection {
        if (deletedSourceTurnIds.isEmpty()) {
            return MemoryDeletionProjection(
                activeNodeIdsByTier = activeNodeIdsByTier,
                nodesById = nodesById,
                createdNodes = emptyList(),
                changed = false
            )
        }

        val projectedByOldId = mutableMapOf<String, MemoryNode?>()
        val visiting = mutableSetOf<String>()
        val created = linkedMapOf<String, MemoryNode>()

        fun projectNode(nodeId: String): MemoryNode? {
            if (projectedByOldId.containsKey(nodeId)) return projectedByOldId[nodeId]
            check(visiting.add(nodeId)) { "删除整理发现记忆节点循环：$nodeId" }
            val node = nodesById[nodeId] ?: error("删除整理缺少记忆节点：$nodeId")
            val projected = when (node.tier) {
                MemoryTier.LEGACY_REFERENCE -> node
                MemoryTier.EPISODE -> {
                    val remainingSourceIds = node.sourceTurnIds
                        .filterNot(deletedSourceTurnIds::contains)
                    when {
                        remainingSourceIds == node.sourceTurnIds -> node
                        remainingSourceIds.isEmpty() -> null
                        else -> {
                            val refs = remainingSourceIds.map { sourceId ->
                                sourceRefsById[sourceId]
                                    ?: error("删除整理缺少现存来源：$sourceId")
                            }
                            val refsById = refs.associateBy { it.sourceTurnId }
                            val hashes = remainingSourceIds.associateWith { sourceId ->
                                node.sourceHashes[sourceId]
                                    ?: refsById.getValue(sourceId).sourceHash
                            }
                            val fingerprints = if (node.sourceFingerprints.isEmpty()) {
                                emptyMap()
                            } else {
                                remainingSourceIds.associateWith { sourceId ->
                                    node.sourceFingerprints[sourceId]
                                        ?: node.sourceHashes[sourceId]
                                        ?: refsById.getValue(sourceId).sourceFingerprint
                                }
                            }
                            val coverageUnits = node.coverageUnits.filter {
                                it.sourceId in remainingSourceIds
                            }
                            node.copy(
                                id = newNodeId(),
                                sourceTurnIds = remainingSourceIds,
                                coverageUnits = coverageUnits,
                                sourceHash = MemoryHashes.sourceIds(
                                    remainingSourceIds,
                                    remainingSourceIds.map(hashes::getValue)
                                ),
                                sourceHashes = hashes,
                                sourceFingerprints = fingerprints,
                                coverageHash = if (coverageUnits.isEmpty()) {
                                    MemoryHashes.episodeCoverage(
                                        remainingSourceIds,
                                        hashes,
                                        node.body
                                    )
                                } else {
                                    MemoryHashes.coverageUnits(coverageUnits)
                                },
                                staleSourceTurnIds = node.staleSourceTurnIds
                                    .filterTo(mutableSetOf()) { it in remainingSourceIds },
                                startT = null,
                                endT = null,
                                sourceTurns = emptyList(),
                                childCoverage = emptyList()
                            )
                        }
                    }
                }

                MemoryTier.ARC,
                MemoryTier.ERA -> {
                    val children = node.childIds.mapNotNull(::projectNode)
                    val childrenChanged = children.map { it.id } != node.childIds
                    when {
                        !childrenChanged -> node
                        children.isEmpty() -> null
                        else -> {
                            val coverageUnits = MemoryHashes.parentCoverageUnits(children)
                            node.copy(
                                id = newNodeId(),
                                sourceTurnIds = children.flatMap { it.sourceTurnIds },
                                childIds = children.map { it.id },
                                coverageUnits = coverageUnits,
                                sourceHash = MemoryHashes.text(
                                    children.joinToString("\n") {
                                        "${it.id}:${it.sourceHash}"
                                    }
                                ),
                                sourceHashes = children
                                    .flatMap { it.sourceHashes.entries }
                                    .associate { it.key to it.value },
                                sourceFingerprints = children
                                    .flatMap { it.sourceFingerprints.entries }
                                    .associate { it.key to it.value },
                                coverageHash = MemoryHashes.parentCoverage(children, coverageUnits),
                                staleSourceTurnIds = children
                                    .flatMapTo(mutableSetOf()) { it.staleSourceTurnIds },
                                startT = null,
                                endT = null,
                                sourceTurns = emptyList(),
                                childCoverage = emptyList()
                            )
                        }
                    }
                }
            }
            visiting.remove(nodeId)
            projectedByOldId[nodeId] = projected
            if (projected != null && projected.id != nodeId) created[projected.id] = projected
            return projected
        }

        val projectedActive = activeNodeIdsByTier.mapValues { (_, nodeIds) ->
            MemoryTimelinePolicy.sortNodes(
                nodeIds.mapNotNull(::projectNode).distinctBy { it.id },
                timeline
            ).map { it.id }
        }
        val changed = projectedActive != activeNodeIdsByTier || created.isNotEmpty()
        val combinedNodes = nodesById.toMutableMap().apply { putAll(created) }
        return MemoryDeletionProjection(
            activeNodeIdsByTier = projectedActive,
            nodesById = combinedNodes,
            createdNodes = created.values.toList(),
            changed = changed
        )
    }
}
