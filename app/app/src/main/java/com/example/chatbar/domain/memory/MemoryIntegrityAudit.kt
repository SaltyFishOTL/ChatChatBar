package com.example.chatbar.domain.memory

import com.example.chatbar.data.local.entity.MemoryNode
import com.example.chatbar.data.local.entity.MemorySessionState

data class MemoryIntegrityWarning(
    val message: String,
    val affectedNodeIds: List<String>
)

/** 用户恢复可制造全局异常；只报告，不阻止保存或注入。 */
object MemoryIntegrityAudit {
    fun warnings(
        state: MemorySessionState,
        nodesById: Map<String, MemoryNode>
    ): List<MemoryIntegrityWarning> {
        val warnings = mutableListOf<MemoryIntegrityWarning>()
        val activeIds = state.activeNodeIds
        val missingIds = activeIds.filterNot(nodesById::containsKey)
        if (missingIds.isNotEmpty()) warnings += MemoryIntegrityWarning(
            message = "当前分页引用了${missingIds.size}个不存在的节点。",
            affectedNodeIds = missingIds
        )
        val duplicatedIds = activeIds.groupingBy { it }.eachCount()
            .filterValues { it > 1 }.keys.toList()
        if (duplicatedIds.isNotEmpty()) warnings += MemoryIntegrityWarning(
            message = "当前分页存在重复节点引用。",
            affectedNodeIds = duplicatedIds
        )
        val storedNodes = activeIds.mapNotNull(nodesById::get)
        val unverifiableNodes = storedNodes.filter {
            it.body.isNotBlank() && MemoryTimelinePolicy.verifiedRange(it, state.timeline) == null
        }
        if (unverifiableNodes.isNotEmpty()) warnings += MemoryIntegrityWarning(
            message = "当前Archive有${unverifiableNodes.size}个节点无法关联T时间线；正文仍会发送，请检查旧数据。",
            affectedNodeIds = unverifiableNodes.map { it.id }
        )
        val blankNodes = storedNodes.filter { it.body.isBlank() }
        if (blankNodes.isNotEmpty()) warnings += MemoryIntegrityWarning(
            message = "当前Archive有${blankNodes.size}个节点正文为空，无法发送。",
            affectedNodeIds = blankNodes.map { it.id }
        )
        val nodes = MemoryTimelinePolicy.sortNodes(storedNodes, state.timeline)
        listOf(state.episodePage, state.arcPage, state.eraPage).forEach { page ->
            val orderedIds = MemoryPageOrderPolicy.orderedNodeIdsOrNull(
                nodeIds = page.activeNodeIds,
                nodesById = nodesById,
                timeline = state.timeline
            )
            if (orderedIds != null && page.activeNodeIds != orderedIds) {
                warnings += MemoryIntegrityWarning(
                    message = "${page.tier}分页节点未按T时间线升序排列。",
                    affectedNodeIds = page.activeNodeIds
                )
            }
        }
        val firstRange = nodes.firstOrNull()?.let { MemoryTimelinePolicy.verifiedRange(it, state.timeline) }
        if (firstRange != null && firstRange.startT > 0) warnings += MemoryIntegrityWarning(
            message = "T0-T${firstRange.startT - 1}未生成长期记忆，推荐一键补录。",
            affectedNodeIds = listOf(nodes.first().id)
        )
        nodes.zipWithNext().forEach { (left, right) ->
            val leftRange = MemoryTimelinePolicy.verifiedRange(left, state.timeline) ?: return@forEach
            val rightRange = MemoryTimelinePolicy.verifiedRange(right, state.timeline) ?: return@forEach
            when {
                rightRange.startT <= leftRange.endT -> warnings += MemoryIntegrityWarning(
                    message = "T${rightRange.startT}-T${leftRange.endT}存在重叠，模型会同时收到两份记录。",
                    affectedNodeIds = listOf(left.id, right.id)
                )
                rightRange.startT > leftRange.endT + 1 -> warnings += MemoryIntegrityWarning(
                    message = "T${leftRange.endT + 1}-T${rightRange.startT - 1}未生成长期记忆，推荐一键补录。",
                    affectedNodeIds = listOf(left.id, right.id)
                )
            }
        }
        val gapIds = state.gaps.flatMapTo(mutableSetOf()) { it.sourceTurnIds }
        nodes.forEach { node ->
            val overlap = node.sourceTurnIds.filter { it in gapIds }
            if (overlap.isNotEmpty()) warnings += MemoryIntegrityWarning(
                message = "同一段剧情同时被标记为已有记忆和未生成记忆，请恢复其他历史版本或重新补录。",
                affectedNodeIds = listOf(node.id)
            )
        }
        return warnings
    }
}
