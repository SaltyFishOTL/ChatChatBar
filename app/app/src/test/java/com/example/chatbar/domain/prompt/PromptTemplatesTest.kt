package com.example.chatbar.domain.prompt

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptTemplatesTest {
    @Test
    fun referenceImagePromptKeepsRequiredProtocolTokens() {
        val prompt = PromptTemplates.novelAiImagePromptReferenceImageUser()

        assertTrue(prompt.contains("NOVELAI_IMAGE_PROMPT_SYSTEM"))
        assertTrue(prompt.contains("NovelAI Diffusion V4.5 Full"))
        assertFalse(prompt.contains("{{"))
    }

    @Test
    fun replyLengthTailSystemPromptIncludesConfiguredLength() {
        val prompt = PromptTemplates.replyLengthTailSystemPrompt("500字")

        assertTrue(prompt.contains("500字"))
        assertFalse(prompt.contains("{{replyLength}}"))
    }

    @Test
    fun currentTurnOutputRequirementsIncludeInputsInOrderAndResolvePlaceholders() {
        val systemPrompt = PromptTemplates.currentTurnOutputRequirementsSystemPrompt(
            formatCardContent = "格式正文",
            replyLength = "500字中篇"
        )

        val formatIndex = systemPrompt.indexOf("格式正文")
        val lengthIndex = systemPrompt.indexOf("500字中篇")
        assertTrue(formatIndex >= 0)
        assertTrue(lengthIndex > formatIndex)
        assertFalse(systemPrompt.contains("{{replyLength}}"))
        assertFalse(systemPrompt.contains("{{formatCardContent}}"))
    }

    @Test
    fun currentTurnOutputRequirementsWithoutFormatCardStillResolveLength() {
        val systemPrompt = PromptTemplates.currentTurnOutputRequirementsSystemPrompt(
            formatCardContent = null,
            replyLength = "300字短篇"
        )

        assertTrue(systemPrompt.contains("300字短篇"))
        assertFalse(systemPrompt.contains("{{"))
        assertFalse(systemPrompt.contains("null", ignoreCase = true))
    }

    @Test
    fun memoryPromptsKeepInputsAndRequiredJsonProtocolTokens() {
        val episode = PromptTemplates.memoryEpisodePrompt("turns", 70)
        val compression = PromptTemplates.memoryCompressionPrompt(
            kind = "EPISODE_TO_ARC",
            children = "children"
        )

        assertTrue(episode.contains("turns"))
        assertTrue(episode.contains("70"))
        assertTrue(episode.contains("\"summary\""))
        assertFalse(episode.contains("sourceCoverage"))
        assertTrue(compression.contains("children"))
        assertTrue(compression.contains("childCoverage"))
    }

    @Test
    fun timelineContractKeepsRequiredProtocolAnchors() {
        val contract = PromptTemplates.MEMORY_TIMELINE_CONTRACT

        assertTrue(contract.contains("T"))
        assertTrue(contract.contains("Archive"))
        assertTrue(contract.contains("RAG"))
    }

    @Test
    fun unresolvedArchiveRangeKeepsLatestStableTurnAndAvoidsNullRange() {
        val label = PromptTemplates.memoryTimelineDirectLabel(
            archivePresent = true,
            archiveRangeUnverifiable = true,
            archiveLabel = "",
            archiveThroughT = null,
            hasGapAfterArchive = false,
            latestStableT = 9
        )

        assertTrue(label.contains("T9"))
        assertFalse(label.contains("Tnull"))
    }

    @Test
    fun archiveBodyWithoutDerivedRangeUsesStableTurnAndNeverBuildsNullRange() {
        val label = PromptTemplates.memoryTimelineDirectLabel(
            archivePresent = true,
            archiveRangeUnverifiable = false,
            archiveLabel = "Archive最大T Tnull",
            archiveThroughT = null,
            hasGapAfterArchive = false,
            latestStableT = 4
        )

        assertTrue(label.contains("T4"))
        assertFalse(label.contains("Tnull"))
    }

    @Test
    fun headPromptIncludesModeAndInputsInSourceOrder() {
        val prompt = PromptTemplates.memoryHeadPrompt(
            mode = "BACKFILL",
            throughT = 20,
            currentHead = "",
            archive = "[Episode T0-T18] 旧剧情",
            sourceTurns = "[T19] 基线剧情"
        )

        val archiveIndex = prompt.indexOf("[Episode T0-T18] 旧剧情")
        val sourceIndex = prompt.indexOf("[T19] 基线剧情")
        assertTrue(prompt.contains("BACKFILL"))
        assertTrue(prompt.contains("20"))
        assertTrue(archiveIndex >= 0)
        assertTrue(sourceIndex > archiveIndex)
    }
}
