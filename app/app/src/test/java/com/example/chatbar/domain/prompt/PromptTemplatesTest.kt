package com.example.chatbar.domain.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptTemplatesTest {
    @Test
    fun replyLengthTailSystemPrompt_repeatsConfiguredLengthAtPromptTail() {
        assertEquals(
            "严格按照格式要求，输出【500字】篇幅的回复。",
            PromptTemplates.replyLengthTailSystemPrompt("500字")
        )
    }

    @Test
    fun memoryPromptsRequireContinuousCoverageAndProgramRanges() {
        val episode = PromptTemplates.memoryEpisodePrompt("turns", 70)
        val compression = PromptTemplates.memoryCompressionPrompt(
            kind = "EPISODE_TO_ARC",
            children = "children"
        )

        assertTrue(episode.contains("直接压缩为一个 Episode"))
        assertTrue(episode.contains("逐 T 复述，禁止这样写"))
        assertTrue(episode.contains("错误输出"))
        assertTrue(episode.contains("正确示例"))
        assertTrue(episode.contains("summary 最多只能写 70 字"))
        assertFalse(episode.contains("sourceCoverage"))
        assertTrue(compression.contains("只能消费其最老连续前缀"))
        assertTrue(compression.contains("4 至 20"))
        assertTrue(compression.contains("childCoverage"))
        assertFalse(compression.contains("返回整份替代记忆"))
    }

    @Test
    fun timelineContractMakesTOnlyChronologyAuthority() {
        val contract = PromptTemplates.MEMORY_TIMELINE_CONTRACT

        assertTrue(contract.contains("T是唯一剧情顺序"))
        assertTrue(contract.contains("禁止把后置Archive或RAG理解成最新剧情"))
        assertTrue(contract.contains("冲突时以更大的T为准"))
        assertTrue(contract.contains("必须从当前最大T继续扮演"))
    }
}
