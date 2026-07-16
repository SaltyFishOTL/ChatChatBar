package com.example.chatbar.domain.memory

import com.example.chatbar.data.local.entity.MemoryAuthor
import com.example.chatbar.data.local.entity.MemoryCoverageUnit
import com.example.chatbar.data.local.entity.MemoryNode
import com.example.chatbar.data.local.entity.MemoryTier
import org.junit.Assert.assertEquals
import org.junit.Test

class MemoryNodeContentTest {
    @Test
    fun formalContentHidesPerSourceCoverageProof() {
        val node = MemoryNode(
            id = "episode",
            sessionId = "session",
            tier = MemoryTier.EPISODE,
            sourceTurnIds = listOf("s0", "s1"),
            coverageUnits = listOf(
                MemoryCoverageUnit("s0", "逐轮证明一"),
                MemoryCoverageUnit("s1", "逐轮证明二")
            ),
            content = "两轮合并后的一段近期流程",
            author = MemoryAuthor.AI
        )

        assertEquals("两轮合并后的一段近期流程", node.body)
    }

    @Test
    fun existingAiNodeUsesAggregateOverviewButOldUserEditKeepsFullCoverageText() {
        val coverage = listOf(
            MemoryCoverageUnit("s0", "旧正文一"),
            MemoryCoverageUnit("s1", "旧正文二")
        )
        val existingAi = MemoryNode(
            id = "ai",
            sessionId = "session",
            tier = MemoryTier.EPISODE,
            coverageUnits = coverage,
            overview = "旧节点已保存的整体摘要",
            author = MemoryAuthor.AI
        )
        val existingUserEdit = existingAi.copy(
            id = "user",
            overview = "旧版曾截断",
            author = MemoryAuthor.USER
        )

        assertEquals("旧节点已保存的整体摘要", existingAi.body)
        assertEquals("旧正文一\n旧正文二", existingUserEdit.body)
    }

    @Test
    fun streamingSummaryParserReadsIncompleteJsonAndEscapes() {
        assertEquals(
            "两轮发生了\n新事件",
            extractStreamingJsonString("```json\n{\"summary\":\"两轮发生了\\n新事件", "summary")
        )
    }
}
