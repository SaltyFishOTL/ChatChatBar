package com.example.chatbar.domain.memory

import com.example.chatbar.data.local.entity.MemoryCoverageUnit
import com.example.chatbar.data.local.entity.MemoryNode
import com.example.chatbar.data.local.entity.MemorySnapshot
import com.example.chatbar.data.local.entity.MemoryTier

/** 防止跨会话SaveSlot导入抢占全局不可变节点ID。 */
object MemorySnapshotImportPolicy {
    fun rebind(snapshot: MemorySnapshot, targetSessionId: String): MemorySnapshot {
        val mustClone = snapshot.nodes.any { it.sessionId != targetSessionId } ||
            snapshot.legacyCommit?.sessionId?.let { it != targetSessionId } == true
        if (!mustClone) return snapshot

        val idMap = snapshot.nodes.associate { it.id to MemoryNode.newId() }
        val oldById = snapshot.nodes.associateBy { it.id }
        val reboundByOldId = mutableMapOf<String, MemoryNode>()
        fun rebindNode(oldId: String): MemoryNode {
            reboundByOldId[oldId]?.let { return it }
            val node = oldById[oldId] ?: error("存档记忆树缺少child：$oldId")
            val children = node.childIds.map(::rebindNode)
            val units = if (node.childIds.isEmpty()) {
                node.coverageUnits
            } else {
                node.coverageUnits.map { unit ->
                    MemoryCoverageUnit(
                        sourceId = idMap[unit.sourceId]
                            ?: error("存档coverage引用未知child：${unit.sourceId}"),
                        text = unit.text
                    )
                }
            }
            val rebound = node.copy(
                id = idMap.getValue(node.id),
                sessionId = targetSessionId,
                childIds = children.map { it.id },
                coverageUnits = units,
                sourceHash = if (children.isEmpty()) node.sourceHash else MemoryHashes.text(
                    children.joinToString("\n") { "${it.id}:${it.sourceHash}" }
                ),
                coverageHash = if (children.isEmpty()) {
                    if (node.tier == MemoryTier.EPISODE && units.isEmpty()) {
                        MemoryHashes.episodeCoverage(
                            node.sourceTurnIds,
                            node.sourceHashes,
                            node.body
                        )
                    } else {
                        MemoryHashes.coverageUnits(units)
                    }
                } else {
                    MemoryHashes.parentCoverage(children, units)
                },
                childCoverage = node.childCoverage.map { proof ->
                    proof.copy(
                        childId = idMap[proof.childId]
                            ?: error("存档旧coverage引用未知child：${proof.childId}")
                    )
                }
            )
            reboundByOldId[oldId] = rebound
            return rebound
        }
        val reboundNodes = snapshot.nodes.map { rebindNode(it.id) }

        fun remap(ids: List<String>): List<String> = ids.map { id ->
            idMap[id] ?: error("存档活跃状态引用未知节点：$id")
        }
        val oldState = snapshot.state
        return snapshot.copy(
            state = oldState?.copy(
                episodeNodeIds = remap(oldState.episodeNodeIds),
                arcNodeIds = remap(oldState.arcNodeIds),
                eraNodeIds = remap(oldState.eraNodeIds),
                legacyReferenceNodeIds = remap(oldState.legacyReferenceNodeIds),
                staleSourcesByNodeId = oldState.staleSourcesByNodeId.mapKeys { (nodeId, _) ->
                    idMap[nodeId] ?: error("存档来源变化状态引用未知节点：$nodeId")
                },
                sourceRepair = oldState.sourceRepair.copy(
                    pendingRootNodeIds = remap(oldState.sourceRepair.pendingRootNodeIds)
                )
            ),
            nodes = reboundNodes,
            legacyCommit = snapshot.legacyCommit?.copy(
                sessionId = targetSessionId,
                activeEpisodeNodeIds = remap(snapshot.legacyCommit.activeEpisodeNodeIds),
                activeArcNodeIds = remap(snapshot.legacyCommit.activeArcNodeIds),
                activeEraNodeIds = remap(snapshot.legacyCommit.activeEraNodeIds),
                legacyReferenceNodeIds = remap(snapshot.legacyCommit.legacyReferenceNodeIds)
            )
        )
    }
}
