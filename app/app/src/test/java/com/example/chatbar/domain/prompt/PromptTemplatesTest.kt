package com.example.chatbar.domain.prompt

import com.example.chatbar.domain.image.NovelAiImageModel
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PromptTemplatesTest {
    @Test
    fun novelAiPromptSystemUsesVersionSpecificBudgetsWithoutChangingV45Contract() {
        val v45 = PromptTemplates.NOVELAI_IMAGE_PROMPT_SYSTEM
        val v5 = PromptTemplates.NOVELAI_IMAGE_PROMPT_SYSTEM_V5

        assertTrue(v45.contains("NovelAI Diffusion V4.5 Full"))
        assertTrue(v45.contains("总token<=250，单角色<=50，角色部分尽量简洁"))
        assertFalse(v45.contains("总token<=1000"))
        assertTrue(v5.contains("NovelAI Diffusion V5 Full"))
        assertTrue(v5.contains("总token<=1000，单角色<=150"))
        assertTrue(v5.contains("总 token <=1000，每个角色<=150"))
        assertFalse(v5.contains("简洁"))
        assertTrue(
            PromptTemplates.novelAiImagePromptCoreSystem(
                targetImageModel = NovelAiImageModel.V4_5_FULL
            ).contains("总token<=250")
        )
        assertTrue(
            PromptTemplates.novelAiImagePromptCoreSystem(
                targetImageModel = NovelAiImageModel.V5_FULL
            ).contains("总token<=1000")
        )
    }

    @Test
    fun novelAiRevisionPromptKeepsRequestPreferenceAndTargetModelInOrder() {
        val prompt = PromptTemplates.novelAiImagePromptRevisionUser(
            modificationRequest = "把雨伞改成透明伞",
            finalPromptRequirement = "保持电影感",
            targetImageModel = NovelAiImageModel.V5_FULL
        )

        val requestIndex = prompt.indexOf("把雨伞改成透明伞")
        val preferenceIndex = prompt.indexOf("保持电影感")
        val modelIndex = prompt.indexOf(NovelAiImageModel.V5_FULL.displayName)
        assertTrue(requestIndex >= 0)
        assertTrue(preferenceIndex > requestIndex)
        assertTrue(modelIndex > preferenceIndex)
    }

    @Test
    fun naturalLanguageV5SystemKeepsStructuredPartitionsTagsInteractionsWeightsAndBudgets() {
        val prompt = PromptTemplates.novelAiImageNaturalLanguagePromptCoreSystem()

        assertTrue(prompt.contains("NovelAI Diffusion V5 Full"))
        assertTrue(prompt.contains("baseCaption"))
        assertTrue(prompt.contains("characters"))
        assertTrue(prompt.contains("英文 NovelAI/Danbooru Tag"))
        assertTrue(prompt.contains("source#"))
        assertTrue(prompt.contains("target#"))
        assertTrue(prompt.contains("mutual#"))
        assertTrue(prompt.contains("y::内容::"))
        assertTrue(prompt.contains("1000"))
        assertTrue(prompt.contains("150"))
        assertFalse(prompt.contains("V4.5"))
    }

    @Test
    fun naturalLanguageRevisionKeepsMinimalChangeAndGlobalRequirement() {
        val prompt = PromptTemplates.novelAiImageNaturalLanguagePromptRevisionUser(
            modificationRequest = "把伞改成透明伞",
            finalPromptRequirement = "保持低机位"
        )

        val requestIndex = prompt.indexOf("把伞改成透明伞")
        val requirementIndex = prompt.indexOf("保持低机位")
        assertTrue(prompt.contains("不要大幅重构"))
        assertTrue(prompt.contains("baseCaption 与 characters"))
        assertTrue(prompt.contains("NovelAI Diffusion V5 Full"))
        assertTrue(requestIndex >= 0)
        assertTrue(requirementIndex > requestIndex)
    }

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
    fun continueGenerationUserPromptIsRenderableAndNotBareContinue() {
        val prompt = PromptTemplates.continueGenerationUserPrompt()

        assertTrue(prompt.isNotBlank())
        assertTrue(prompt.contains("\$username"))
        assertFalse(prompt.contains("{{"))
        assertFalse(prompt.startsWith("\n"))
        assertFalse(prompt.endsWith("\n"))
    }

    @Test
    fun userToolSuffixBuildersKeepRequiredProtocolTextAndBraces() {
        assertTrue(PromptTemplates.randomNumberUserToolSuffix(listOf(7)).contains("下一轮使用随机数：7"))
        assertTrue(
            PromptTemplates.randomNumberUserToolSuffix(listOf(7, 9))
                .contains("下一轮按顺序使用随机数：7；9；")
        )
        assertTrue(
            PromptTemplates.appendUserToolSuffixBlock("消息", listOf("尾缀")) ==
                "消息\n{\n尾缀\n}"
        )
    }

    @Test
    fun memoryPromptsKeepInputsAndRequiredJsonProtocolTokens() {
        val episode = PromptTemplates.memoryEpisodePrompt("turns", 70)
        val compression = PromptTemplates.memoryCompressionPrompt(
            kind = "EPISODE_TO_ARC",
            compressionPlan = "保留信任破裂及其后果",
            children = "children"
        )

        assertTrue(episode.contains("turns"))
        assertTrue(episode.contains("70"))
        assertTrue(episode.contains("\"summary\""))
        assertFalse(episode.contains("sourceCoverage"))
        assertTrue(compression.contains("children"))
        assertTrue(compression.contains("consumedChildIds"))
        assertTrue(compression.contains("60 至 300"))
        assertTrue(compression.contains("保留信任破裂及其后果"))
        assertTrue(compression.contains("禁止逐 child 复述"))
        assertTrue(compression.contains("禁止华丽修辞"))
        assertFalse(compression.contains("childCoverage"))
    }

    @Test
    fun memoryCompressionPlannerRequestsShortSelectionWithoutReasoning() {
        val planner = PromptTemplates.memoryCompressionPlannerPrompt(
            kind = "EPISODE_TO_ARC",
            children = "children"
        )

        assertTrue(planner.contains("children"))
        assertTrue(planner.contains("50 字以内"))
        assertTrue(planner.contains("禁止逐 child 列举"))
        assertTrue(planner.contains("禁止解释理由、分析步骤、候选比较或思考过程"))
    }

    @Test
    fun memoryCompressionPromptsExposeNewConsumptionBounds() {
        val episodeToArc = PromptTemplates.memoryCompressionPrompt(
            "EPISODE_TO_ARC",
            compressionPlan = "保留主因果线",
            children = "children"
        )
        val arcToEra = PromptTemplates.memoryCompressionPrompt(
            "ARC_TO_ERA",
            compressionPlan = "保留主因果线",
            children = "children"
        )
        val eraToEra = PromptTemplates.memoryCompressionPrompt(
            kind = "ERA_TO_ERA",
            forcedConsumedChildIds = listOf("era-1", "era-2"),
            compressionPlan = "保留主因果线",
            children = "children"
        )

        assertTrue(episodeToArc.contains("最少3条，最多10条"))
        assertTrue(episodeToArc.contains("第 11 至 15 条"))
        assertTrue(episodeToArc.contains("60 至 300"))
        assertTrue(arcToEra.contains("最少3条，最多10条"))
        assertTrue(arcToEra.contains("第 11 至 15 条"))
        assertTrue(arcToEra.contains("60 至 300"))
        assertTrue(eraToEra.contains("最少2条，最多5条"))
        assertTrue(eraToEra.contains("60 至 300"))
        assertTrue(eraToEra.contains("era-1,era-2"))
        assertFalse(episodeToArc.contains("childCoverage"))
        assertFalse(arcToEra.contains("childCoverage"))
        assertFalse(eraToEra.contains("childCoverage"))
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
