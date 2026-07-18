package com.example.chatbar.domain.memory

import com.example.chatbar.data.local.entity.MemoryNode

/**
 * AI任务只绑定自己读取的来源和节点。整会话revision变化不代表目标证据变化。
 */
object MemoryTaskCommitPolicy {
    fun headEvidenceCurrent(
        expectedHeadVersion: Long,
        currentHeadVersion: Long,
        expectedSourceHash: String,
        currentSourceHash: String,
        expectedArchive: String? = null,
        currentArchive: String? = null
    ): Boolean = expectedHeadVersion == currentHeadVersion &&
        expectedSourceHash == currentSourceHash &&
        (expectedArchive == null || expectedArchive == currentArchive)

    fun requireEpisodeTargetCurrent(
        sourceTurnIds: List<String>,
        expectedSourceHash: String,
        currentSourceHash: String,
        pendingSourceTurnIds: List<String>,
        activeNodes: List<MemoryNode>,
        label: String
    ) {
        check(currentSourceHash == expectedSourceHash) { "${label}来源已变化" }
        check(sourceTurnIds.all { it in pendingSourceTurnIds }) {
            "${label}待处理目标已变化"
        }
        check(activeNodes.none { node -> node.sourceTurnIds.any(sourceTurnIds::contains) }) {
            "${label}来源已由其他记忆节点覆盖"
        }
    }

    fun requireNodeEvidenceCurrent(
        expectedNodes: List<MemoryNode>,
        currentNodesById: Map<String, MemoryNode>,
        activeNodeIds: Set<String>,
        staleNodeIds: Set<String> = emptySet(),
        label: String
    ) {
        expectedNodes.forEach { expected ->
            check(expected.id in activeNodeIds) { "${label}目标节点已不再活跃：${expected.id}" }
            check(currentNodesById[expected.id] == expected) { "${label}目标节点内容已变化：${expected.id}" }
            check(expected.id !in staleNodeIds) { "${label}目标节点来源已变化：${expected.id}" }
        }
    }
}
