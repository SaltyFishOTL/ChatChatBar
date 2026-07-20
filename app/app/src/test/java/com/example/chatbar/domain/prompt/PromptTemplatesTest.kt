package com.example.chatbar.domain.prompt

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptTemplatesTest {
    @Test
    fun referenceImagePromptRequiresVisibleContentReverseEngineeringUnderSharedRules() {
        val prompt = PromptTemplates.novelAiImagePromptReferenceImageUser()

        assertTrue(prompt.contains("NOVELAI_IMAGE_PROMPT_SYSTEM"))
        assertTrue(prompt.contains("逆向"))
        assertTrue(prompt.contains("可见"))
        assertTrue(prompt.contains("NovelAI Diffusion V4.5 Full"))
    }

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

        assertTrue(episode.contains("turns"))
        assertTrue(episode.contains("逐 T 复述，禁止这样写"))
        assertTrue(episode.contains("错误输出"))
        assertTrue(episode.contains("正确示例"))
        assertTrue(episode.contains("summary 最多只能写 70 字"))
        assertTrue(episode.contains("\"summary\""))
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

    @Test
    fun unresolvedArchiveRangeIsNotReportedAsEmptyArchive() {
        val label = PromptTemplates.memoryTimelineDirectLabel(
            archivePresent = true,
            archiveRangeUnverifiable = true,
            archiveLabel = "",
            archiveThroughT = null,
            hasGapAfterArchive = false,
            latestStableT = 9
        )

        assertTrue(label.contains("待修复"))
        assertTrue(label.contains("T9"))
        assertFalse(label.contains("Archive为空"))
    }

    @Test
    fun archiveBodyWithoutAnyDerivedRangeNeverBuildsNullRangeLabel() {
        val label = PromptTemplates.memoryTimelineDirectLabel(
            archivePresent = true,
            archiveRangeUnverifiable = false,
            archiveLabel = "Archive最大T Tnull",
            archiveThroughT = null,
            hasGapAfterArchive = false,
            latestStableT = 4
        )

        assertTrue(label.contains("待修复"))
        assertFalse(label.contains("Tnull"))
    }

    @Test
    fun headPromptDeclaresModeAndKeepsInputsSeparated() {
        val prompt = PromptTemplates.memoryHeadPrompt(
            mode = "BACKFILL",
            throughT = 20,
            currentHead = "",
            archive = "[Episode T0-T18] 旧剧情",
            sourceTurns = "[T19] 基线剧情"
        )

        assertTrue(prompt.contains("程序模式：BACKFILL"))
        assertTrue(prompt.contains("只根据程序提供的 Archive"))
        assertTrue(prompt.contains("Archive：\n[Episode T0-T18] 旧剧情"))
        assertTrue(prompt.contains("程序指定剧情组：\n[T19] 基线剧情"))
        assertTrue(prompt.contains("不继承旧 HEAD"))
    }
}
