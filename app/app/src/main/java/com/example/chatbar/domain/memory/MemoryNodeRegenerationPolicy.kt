package com.example.chatbar.domain.memory

import com.example.chatbar.data.local.entity.MemoryCompressionKind
import com.example.chatbar.data.local.entity.MemoryNode
import com.example.chatbar.data.local.entity.MemoryTier

data class MemoryNodeRegenerationPlan(
    val node: MemoryNode,
    val children: List<MemoryNode> = emptyList(),
    val compressionKind: MemoryCompressionKind? = null
)

object MemoryNodeRegenerationPolicy {
    fun requireStillCurrent(
        originalPlan: MemoryNodeRegenerationPlan,
        originalEvidenceHash: String,
        currentPlan: MemoryNodeRegenerationPlan,
        currentEvidenceHash: String
    ) {
        check(currentPlan.node == originalPlan.node) { "AI重新生成完成前目标节点已变化" }
        check(currentPlan.children == originalPlan.children) { "AI重新生成完成前直接子节点已变化" }
        check(currentEvidenceHash == originalEvidenceHash) { "AI重新生成完成前原始依据已变化" }
    }

    fun plan(
        node: MemoryNode,
        nodesById: Map<String, MemoryNode>
    ): MemoryNodeRegenerationPlan = when (node.tier) {
        MemoryTier.EPISODE -> {
            require(node.sourceTurnIds.isNotEmpty()) { "Episode缺少原始聊天轮" }
            require(node.childIds.isEmpty()) { "Episode不应包含子节点" }
            MemoryNodeRegenerationPlan(node)
        }

        MemoryTier.ARC -> parentPlan(
            node = node,
            nodesById = nodesById,
            expectedChildTier = MemoryTier.EPISODE,
            expectedCount = MemoryCompressionPolicy.LOWER_MIN_CONSUME..MemoryCompressionPolicy.LOWER_MAX_CONSUME,
            kind = MemoryCompressionKind.EPISODE_TO_ARC
        )

        MemoryTier.ERA -> {
            val children = orderedChildren(node, nodesById)
            val kind = when {
                children.all { it.tier == MemoryTier.ARC } -> {
                    require(children.size in MemoryCompressionPolicy.LOWER_MIN_CONSUME..
                        MemoryCompressionPolicy.LOWER_MAX_CONSUME) {
                        "Era的Arc子节点数量不合法"
                    }
                    MemoryCompressionKind.ARC_TO_ERA
                }

                children.all { it.tier == MemoryTier.ERA } -> {
                    require(children.size in MemoryCompressionPolicy.ERA_MIN_CONSUME..
                        MemoryCompressionPolicy.ERA_MAX_CONSUME) {
                        "Era的同层子节点数量不合法"
                    }
                    MemoryCompressionKind.ERA_TO_ERA
                }

                else -> error("Era子节点层级不一致")
            }
            require(children.flatMap { it.sourceTurnIds } == node.sourceTurnIds) {
                "Era子节点与来源覆盖不一致"
            }
            MemoryNodeRegenerationPlan(node, children, kind)
        }

        MemoryTier.LEGACY_REFERENCE -> error("Legacy Reference无法从可靠来源重新生成")
    }

    private fun parentPlan(
        node: MemoryNode,
        nodesById: Map<String, MemoryNode>,
        expectedChildTier: MemoryTier,
        expectedCount: IntRange,
        kind: MemoryCompressionKind
    ): MemoryNodeRegenerationPlan {
        val children = orderedChildren(node, nodesById)
        require(children.size in expectedCount) { "${tierLabel(node.tier)}子节点数量不合法" }
        require(children.all { it.tier == expectedChildTier }) {
            "${tierLabel(node.tier)}子节点层级不合法"
        }
        require(children.flatMap { it.sourceTurnIds } == node.sourceTurnIds) {
            "${tierLabel(node.tier)}子节点与来源覆盖不一致"
        }
        return MemoryNodeRegenerationPlan(node, children, kind)
    }

    private fun orderedChildren(
        node: MemoryNode,
        nodesById: Map<String, MemoryNode>
    ): List<MemoryNode> {
        require(node.childIds.isNotEmpty()) { "${tierLabel(node.tier)}缺少原始子节点" }
        require(node.childIds.distinct().size == node.childIds.size) {
            "${tierLabel(node.tier)}子节点重复"
        }
        return node.childIds.map { childId ->
            requireNotNull(nodesById[childId]) { "原始子节点不存在：$childId" }
        }
    }

    private fun tierLabel(tier: MemoryTier): String = when (tier) {
        MemoryTier.EPISODE -> "Episode"
        MemoryTier.ARC -> "Arc"
        MemoryTier.ERA -> "Era"
        MemoryTier.LEGACY_REFERENCE -> "Legacy Reference"
    }
}
