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
        val prompt = PromptTemplates.replyLengthTailSystemPrompt(500)

        assertTrue(prompt.contains("500字"))
        assertFalse(prompt.contains("{{replyLength}}"))
    }

    @Test
    fun currentTurnOutputRequirementsIncludeInputsInOrderAndResolvePlaceholders() {
        val systemPrompt = PromptTemplates.currentTurnOutputRequirementsSystemPrompt(
            formatCardContent = "格式正文",
            replyLength = 500
        )

        val formatIndex = systemPrompt.indexOf("格式正文")
        val lengthIndex = systemPrompt.indexOf("500字")
        assertTrue(formatIndex >= 0)
        assertTrue(lengthIndex > formatIndex)
        assertFalse(systemPrompt.contains("{{replyLength}}"))
        assertFalse(systemPrompt.contains("{{formatCardContent}}"))
    }

    @Test
    fun currentTurnOutputRequirementsWithoutFormatCardStillResolveLength() {
        val systemPrompt = PromptTemplates.currentTurnOutputRequirementsSystemPrompt(
            formatCardContent = null,
            replyLength = 300
        )

        assertTrue(systemPrompt.contains("[300字]"))
        assertFalse(systemPrompt.contains("短篇"))
        assertFalse(systemPrompt.contains("{{"))
        assertFalse(systemPrompt.contains("null", ignoreCase = true))
    }

    @Test
    fun formatContinuityNoticeIsIncludedOnlyWhenRequestedWithFormatCard() {
        val included = PromptTemplates.currentTurnOutputRequirementsSystemPrompt(
            formatCardContent = "格式正文",
            replyLength = 300,
            includeFormatHistoryContinuityNotice = true
        )
        val notRequested = PromptTemplates.currentTurnOutputRequirementsSystemPrompt(
            formatCardContent = "格式正文",
            replyLength = 300,
            includeFormatHistoryContinuityNotice = false
        )
        val noFormatCard = PromptTemplates.currentTurnOutputRequirementsSystemPrompt(
            formatCardContent = null,
            replyLength = 300,
            includeFormatHistoryContinuityNotice = true
        )

        assertTrue(included.contains(PromptTemplates.FORMAT_HISTORY_CONTINUITY_NOTICE))
        assertFalse(notRequested.contains(PromptTemplates.FORMAT_HISTORY_CONTINUITY_NOTICE))
        assertFalse(noFormatCard.contains(PromptTemplates.FORMAT_HISTORY_CONTINUITY_NOTICE))
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

    @Test
    fun fishAudioTranslationPromptIncludesLanguageContextAndSegmentsInOrder() {
        val prompt = PromptTemplates.fishAudioTranslationUserInput(
            targetLanguage = "日语",
            previousUserMessage = "上一条",
            assistantResponse = "完整回复",
            segmentsJson = """[{"id":"segment-1","text":"你好"}]"""
        )

        val languageIndex = prompt.indexOf("日语")
        val previousIndex = prompt.indexOf("上一条")
        val responseIndex = prompt.indexOf("完整回复")
        val segmentIndex = prompt.indexOf("segment-1")
        assertTrue(languageIndex >= 0)
        assertTrue(previousIndex > languageIndex)
        assertTrue(responseIndex > previousIndex)
        assertTrue(segmentIndex > responseIndex)
        assertTrue(PromptTemplates.FISH_AUDIO_TRANSLATION_SYSTEM.contains("translatedText"))
    }

    @Test
    fun fishAudioTagPromptUsesSynthesisTextLanguageForNaturalCues() {
        val policy = PromptTemplates.fishAudioVoiceTagPolicy(
            isS1 = false,
            s1FixedTags = emptyList(),
            s2RecommendedTags = listOf("happy", "sad")
        )
        val prompt = PromptTemplates.fishAudioVoiceTagUserInput(
            fishModelId = "s2",
            markerMode = "方括号 [tag]",
            tagPolicy = policy,
            previousUserMessage = "上一条",
            assistantResponse = "完整回复",
            segmentsJson = """[{"id":"segment-1","text":"你好"}]"""
        )

        assertTrue(policy.contains("与其控制的 text 使用相同语言"))
        assertTrue(prompt.contains("标签语言："))
        assertTrue(prompt.contains("segment-1"))
    }
}
