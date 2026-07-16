package com.example.chatbar.domain.memory

import com.example.chatbar.data.local.entity.MemoryGap
import com.example.chatbar.data.local.entity.MemoryNode
import com.example.chatbar.data.local.entity.MemoryTier
import com.example.chatbar.data.local.entity.MemoryTimelineEntry

data class MemoryValidationResult(
    val valid: Boolean,
    val error: String? = null
) {
    companion object {
        val Valid = MemoryValidationResult(true)
        fun invalid(error: String) = MemoryValidationResult(false, error)
    }
}

/** 自动节点的递归覆盖证明。用户跨分页恢复异常由单独审计，不污染节点自身。 */
object MemoryContinuityPolicy {
    fun validateNode(
        root: MemoryNode,
        nodesById: Map<String, MemoryNode>,
        timeline: List<MemoryTimelineEntry>,
        gaps: List<MemoryGap> = emptyList()
    ): MemoryValidationResult = validateNodeInternal(
        root = root,
        nodesById = nodesById,
        timeline = timeline,
        gaps = gaps,
        path = mutableSetOf()
    )

    private fun validateNodeInternal(
        root: MemoryNode,
        nodesById: Map<String, MemoryNode>,
        timeline: List<MemoryTimelineEntry>,
        gaps: List<MemoryGap>,
        path: MutableSet<String>
    ): MemoryValidationResult {
        if (!path.add(root.id)) return MemoryValidationResult.invalid("节点${root.id}存在循环引用")
        if (root.tier == MemoryTier.LEGACY_REFERENCE) return MemoryValidationResult.Valid
        if (!MemoryTimelinePolicy.isContinuous(root.sourceTurnIds, timeline, gaps)) {
            return MemoryValidationResult.invalid("节点${root.id}来源不连续或跨越Gap")
        }
        if (root.coverageUnits.any { it.text.isBlank() }) {
            return MemoryValidationResult.invalid("节点${root.id}正文单元为空")
        }

        if (root.tier == MemoryTier.EPISODE) {
            if (root.childIds.isNotEmpty()) {
                return MemoryValidationResult.invalid("Episode不得引用child")
            }
            if (root.coverageUnits.isEmpty()) {
                if (root.body.isBlank()) {
                    return MemoryValidationResult.invalid("Episode summary为空")
                }
                if (root.sourceHashes.keys != root.sourceTurnIds.toSet()) {
                    return MemoryValidationResult.invalid("Episode来源哈希不完整")
                }
                if (root.coverageHash != MemoryHashes.episodeCoverage(
                        root.sourceTurnIds,
                        root.sourceHashes,
                        root.body
                    )
                ) {
                    return MemoryValidationResult.invalid("Episode结构覆盖哈希不匹配")
                }
            } else {
                // 兼容旧节点：旧协议曾让AI逐source turn生成coverage文本。
                if (root.coverageUnits.map { it.sourceId } != root.sourceTurnIds) {
                    return MemoryValidationResult.invalid("Episode未逐source turn覆盖")
                }
                if (root.coverageHash != MemoryHashes.coverageUnits(root.coverageUnits)) {
                    return MemoryValidationResult.invalid("Episode coverageHash不匹配")
                }
            }
            path.remove(root.id)
            return MemoryValidationResult.Valid
        }

        if (root.childIds.isEmpty() || root.childIds.distinct().size != root.childIds.size) {
            return MemoryValidationResult.invalid("父节点child为空或重复")
        }
        val children = root.childIds.map { id ->
            nodesById[id] ?: return MemoryValidationResult.invalid("父节点缺少child $id")
        }
        val legalTier = when (root.tier) {
            MemoryTier.ARC -> children.all { it.tier == MemoryTier.EPISODE }
            MemoryTier.ERA -> {
                val childTier = children.first().tier
                (childTier == MemoryTier.ARC || childTier == MemoryTier.ERA) &&
                    children.all { it.tier == childTier }
            }
            MemoryTier.EPISODE,
            MemoryTier.LEGACY_REFERENCE -> false
        }
        if (!legalTier) return MemoryValidationResult.invalid("父子层级非法")
        if (children.flatMap { it.sourceTurnIds } != root.sourceTurnIds) {
            return MemoryValidationResult.invalid("父节点未完整拼接全部child来源")
        }
        if (root.coverageUnits.map { it.sourceId } != root.childIds) {
            return MemoryValidationResult.invalid("父节点未逐child覆盖")
        }
        if (root.coverageHash != MemoryHashes.parentCoverage(children, root.coverageUnits)) {
            return MemoryValidationResult.invalid("父节点coverageHash不匹配")
        }
        val expectedSourceHash = MemoryHashes.text(
            children.joinToString("\n") { "${it.id}:${it.sourceHash}" }
        )
        if (root.sourceHash != expectedSourceHash) {
            return MemoryValidationResult.invalid("父节点sourceHash不匹配")
        }
        children.forEach { child ->
            val nested = validateNodeInternal(child, nodesById, timeline, gaps, path)
            if (!nested.valid) return nested
            path.remove(child.id)
        }
        path.remove(root.id)
        return MemoryValidationResult.Valid
    }
}
