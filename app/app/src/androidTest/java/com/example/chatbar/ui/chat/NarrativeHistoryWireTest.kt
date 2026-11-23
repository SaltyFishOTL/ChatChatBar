package com.example.chatbar.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.example.chatbar.BuildConfig
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatNarrativeMode
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.FormatCard
import com.example.chatbar.data.local.entity.FormatCardUserToolConfig
import com.example.chatbar.data.local.entity.MessageFormatRepairNoticeKind
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.domain.chat.MessageFormatRepairPolicy
import com.example.chatbar.domain.prompt.PromptTemplates
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.ChatBarTheme
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class NarrativeHistoryWireTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private enum class Transport {
        ORDINARY, EXPERIMENTAL_TEXT, EXPERIMENTAL_TOOL;

        val experimental: Boolean get() = this != ORDINARY
    }

    private enum class LiteralCase {
        OPENING, INLINE_CLOSE, INLINE_CODE_CLOSE, SAME_LINE_INLINE_CODE_CLOSE, ADJACENT_AUTHORED,
        SAME_LINE_DIRECTIVE_MULTILINE, TOOL_SPLIT_CAPTION, CARD_ORPHAN_NO_HISTORY, STRONG_SUFFIX_OPEN,
        PADDED_OPENER, AUTHORED_CLOSE_EXTRA, HISTORICAL_TEMPLATE_PROHIBITED,
        LAZY_LIST_CLOSE, CAPTIONED_CLOSE, LAZY_QUOTE_CLOSE, ORDINARY_CAPTIONED_CLOSE, CHARACTER_POST_HISTORY,
        CHARACTER_TAIL_OPEN, CHARACTER_TAIL_CLOSE, CHARACTER_CORE, USER_COMPACT_TEMPLATE, STRONG_SUFFIX_DENIAL,
        DOCUMENTARY_FORMAT, BOUNDED_LOG_QUOTATION,
    }
    private enum class RepairSecondCall { COMPLETE, INCOMPLETE, MALFORMED, GAP }
    private enum class RepairOrphan {
        FIRST, SECOND_AFTER_FULL_FRAME, SPLIT_CAPTION, VISIBLE_INPUT_LITERAL, VISIBLE_INPUT_BLOCKQUOTE, AUTHORED_CLOSE_EXTRA,
        EXPLICIT_STANDALONE_PROHIBITION,
    }

    @Before fun prepareSyntheticApplication() = runBlocking {
        assumeTrue("Use the repository-supported isolated Android test build", BuildConfig.ISOLATED_ANDROID_TEST)
        val app = ChatBarApp.instance
        check(app.packageName == BuildConfig.APPLICATION_ID && app.packageName.startsWith("com.example.chatbar."))
        withTimeout(30_000) {
            app.dependenciesReady.first { it }
            app.settingsRepository.isInitialized.first { it }
        }
        rule.setContent { ChatBarTheme { CbText("Synthetic narrative wire verification") } }
    }

    @Test fun ordinaryPreviewStopAndCompletionKeepOnlyIntendedTags() = runBlocking {
        verifyTransport(Transport.ORDINARY)
    }

    @Test fun experimentalTextPreviewStopAndCompletionKeepOnlyIntendedTags() = runBlocking {
        verifyTransport(Transport.EXPERIMENTAL_TEXT)
    }

    @Test fun experimentalToolPreviewStopAndCompletionKeepOnlyIntendedTags() = runBlocking {
        verifyTransport(Transport.EXPERIMENTAL_TOOL)
    }

    @Test fun experimentalToolCleansAnEmbeddedClosingCallBeforeOrdinaryContinuation() = runBlocking {
        verifyReply(
            Transport.EXPERIMENTAL_TOOL, stop = false, continuation = "She notices the rain has stopped.",
        )
    }

    @Test fun currentUserLiteralOpenerSurvivesStreamingStopAndCompletion() = runBlocking {
        verifyReply(Transport.ORDINARY, stop = true, literal = LiteralCase.OPENING)
        verifyReply(Transport.EXPERIMENTAL_TEXT, stop = false, literal = LiteralCase.OPENING)
    }

    @Test fun exactMultilineCopyWithInlineClosingSurvivesStopAndCompletion() = runBlocking {
        verifyReply(Transport.ORDINARY, stop = true, literal = LiteralCase.INLINE_CLOSE)
        verifyReply(Transport.EXPERIMENTAL_TEXT, stop = false, literal = LiteralCase.INLINE_CLOSE)
    }

    @Test fun exactMultilineCopyWithInlineCodeCloserSurvivesPreviewStopAndColdRead() = runBlocking {
        verifyReply(Transport.ORDINARY, stop = true, literal = LiteralCase.INLINE_CODE_CLOSE)
        verifyReply(Transport.ORDINARY, stop = false, literal = LiteralCase.INLINE_CODE_CLOSE)
    }

    @Test fun sameLineExactCopyWithInlineCodeCloserSurvivesPreviewStopAndColdRead() = runBlocking {
        verifyReply(Transport.ORDINARY, stop = true, literal = LiteralCase.SAME_LINE_INLINE_CODE_CLOSE)
        verifyReply(Transport.ORDINARY, stop = false, literal = LiteralCase.SAME_LINE_INLINE_CODE_CLOSE)
    }

    @Test fun adjacentTransportAndAuthoredPairsKeepTheirOwnBoundariesOnDisk() = runBlocking {
        verifyReply(Transport.ORDINARY, stop = true, literal = LiteralCase.ADJACENT_AUTHORED)
        verifyReply(Transport.ORDINARY, stop = false, literal = LiteralCase.ADJACENT_AUTHORED)
    }

    @Test fun sameLineDirectiveAndMultilineCopyKeepTheExactRequestedPairOnDisk() = runBlocking {
        verifyReply(Transport.ORDINARY, stop = true, literal = LiteralCase.SAME_LINE_DIRECTIVE_MULTILINE)
        verifyReply(Transport.ORDINARY, stop = false, literal = LiteralCase.SAME_LINE_DIRECTIVE_MULTILINE)
    }

    @Test fun selectedProhibitedCardCleansOrphanWithNoPriorAssistantHistory() = runBlocking {
        verifyReply(Transport.ORDINARY, stop = true, literal = LiteralCase.CARD_ORPHAN_NO_HISTORY)
        verifyReply(Transport.ORDINARY, stop = false, literal = LiteralCase.CARD_ORPHAN_NO_HISTORY)
    }

    @Test fun splitCaptionRetainsLiteralCloseOnChatAndRepairStop() = runBlocking {
        verifyReply(Transport.EXPERIMENTAL_TOOL, stop = true, literal = LiteralCase.TOOL_SPLIT_CAPTION)
        verifyRepair(Transport.EXPERIMENTAL_TOOL, stop = true, orphan = RepairOrphan.SPLIT_CAPTION)
        verifyRepair(Transport.EXPERIMENTAL_TOOL, stop = false, orphan = RepairOrphan.SPLIT_CAPTION)
    }

    @Test fun inTurnStrongSuffixProtectsItsLiteralOpenerThroughColdSave() = runBlocking {
        verifyReply(Transport.ORDINARY, stop = true, literal = LiteralCase.STRONG_SUFFIX_OPEN)
        verifyReply(Transport.ORDINARY, stop = false, literal = LiteralCase.STRONG_SUFFIX_OPEN)
    }

    @Test fun repairPreservesOriginalMarkerAsLiteralInsideTheFreshOuterPair() = runBlocking {
        verifyRepair(Transport.ORDINARY, stop = true, orphan = RepairOrphan.VISIBLE_INPUT_LITERAL)
        verifyRepair(Transport.EXPERIMENTAL_TOOL, stop = false, orphan = RepairOrphan.VISIBLE_INPUT_LITERAL)
    }

    @Test fun repairPreservesBlockquotedSourceCloserThroughStopAndColdCompletion() = runBlocking {
        verifyRepair(Transport.ORDINARY, stop = true, orphan = RepairOrphan.VISIBLE_INPUT_BLOCKQUOTE, repairMode = ChatNarrativeMode.FOCUS)
        verifyRepair(Transport.EXPERIMENTAL_TOOL, stop = false, orphan = RepairOrphan.VISIBLE_INPUT_BLOCKQUOTE, repairMode = ChatNarrativeMode.ADVANCE)
    }

    @Test fun paddedOwnLineOpenerStaysOutOfExplicitStopAndLengthColdSave() = runBlocking {
        verifyReply(Transport.ORDINARY, stop = true, literal = LiteralCase.PADDED_OPENER)
        verifyReply(Transport.EXPERIMENTAL_TOOL, stop = false, literal = LiteralCase.PADDED_OPENER)
    }

    @Test fun authoredClosingOccurrenceSurvivesWhileTheExtraOrphanIsRemovedOnDisk() = runBlocking {
        verifyReply(Transport.ORDINARY, stop = false, literal = LiteralCase.AUTHORED_CLOSE_EXTRA)
        verifyRepair(Transport.EXPERIMENTAL_TOOL, stop = true, orphan = RepairOrphan.AUTHORED_CLOSE_EXTRA)
    }

    @Test fun currentProhibitionOverridesTheActualHistoricalUserTemplateOnDisk() = runBlocking {
        verifyReply(Transport.ORDINARY, stop = true, literal = LiteralCase.HISTORICAL_TEMPLATE_PROHIBITED)
        verifyReply(Transport.EXPERIMENTAL_TEXT, stop = false, literal = LiteralCase.HISTORICAL_TEMPLATE_PROHIBITED)
    }

    @Test fun disconnectedTerminalToolIndexesNeverPersistASuccessfulChatOrRepair() = runBlocking {
        for (indexes in listOf(listOf(0, 2), listOf(2))) {
            verifyReply(Transport.EXPERIMENTAL_TOOL, stop = false, toolIndexes = indexes)
            verifyRepair(Transport.EXPERIMENTAL_TOOL, stop = false, toolIndexes = indexes)
        }
    }

    @Test fun lazyListClosingExampleSurvivesStopAndGenuineOuterClosing() = runBlocking {
        verifyReply(Transport.ORDINARY, stop = true, literal = LiteralCase.LAZY_LIST_CLOSE)
        verifyReply(Transport.EXPERIMENTAL_TOOL, stop = false, literal = LiteralCase.LAZY_LIST_CLOSE)
    }

    @Test fun requestedCaptionedCloserSurvivesNormalAndExperimentalStopAndDone() = runBlocking {
        verifyReply(Transport.ORDINARY, stop = true, literal = LiteralCase.CAPTIONED_CLOSE)
        verifyReply(Transport.ORDINARY, stop = false, literal = LiteralCase.CAPTIONED_CLOSE)
        verifyReply(Transport.EXPERIMENTAL_TEXT, stop = true, literal = LiteralCase.CAPTIONED_CLOSE)
        verifyReply(Transport.EXPERIMENTAL_TEXT, stop = false, literal = LiteralCase.CAPTIONED_CLOSE)
        verifyReply(Transport.EXPERIMENTAL_TOOL, stop = true, literal = LiteralCase.CAPTIONED_CLOSE)
        verifyReply(Transport.EXPERIMENTAL_TOOL, stop = false, literal = LiteralCase.CAPTIONED_CLOSE)
    }

    @Test fun captionedLazyQuoteSurvivesActualStopAndAnImmediatelyFollowingOuterClose() = runBlocking {
        for (transport in Transport.entries) {
            verifyReply(transport, stop = true, literal = LiteralCase.LAZY_QUOTE_CLOSE)
            verifyReply(transport, stop = false, literal = LiteralCase.LAZY_QUOTE_CLOSE)
        }
    }

    @Test fun clearlyCaptionedCloserSurvivesActualStopAndGenuineOuterDone() = runBlocking {
        for (transport in Transport.entries) {
            verifyReply(transport, stop = true, literal = LiteralCase.ORDINARY_CAPTIONED_CLOSE)
            verifyReply(transport, stop = false, literal = LiteralCase.ORDINARY_CAPTIONED_CLOSE)
        }
    }

    @Test fun renderedCharacterPostHistoryPreservesTheAuthoredReplyOnStopAndDone() = runBlocking {
        for (transport in Transport.entries) {
            verifyReply(transport, stop = true, literal = LiteralCase.CHARACTER_POST_HISTORY)
            verifyReply(transport, stop = false, literal = LiteralCase.CHARACTER_POST_HISTORY)
        }
    }

    @Test fun explicitlyForbiddenStandaloneCloserStaysOutOfRepairPreviewAndColdStorage() = runBlocking {
        verifyRepair(Transport.ORDINARY, stop = true, orphan = RepairOrphan.EXPLICIT_STANDALONE_PROHIBITION)
        verifyRepair(Transport.EXPERIMENTAL_TOOL, stop = false, orphan = RepairOrphan.EXPLICIT_STANDALONE_PROHIBITION,
            repairMode = ChatNarrativeMode.ADVANCE)
    }

    @Test fun explicitCharacterCoreAndStandaloneTailSurviveRealStopAndDone() = runBlocking {
        for (literal in listOf(LiteralCase.CHARACTER_CORE, LiteralCase.CHARACTER_TAIL_OPEN, LiteralCase.CHARACTER_TAIL_CLOSE)) {
            verifyReply(Transport.ORDINARY, stop = true, literal = literal)
            verifyReply(Transport.EXPERIMENTAL_TEXT, stop = false, literal = literal)
        }
    }

    @Test fun compactUserTemplateAndLaterExactStrongDenialReachColdStorage() = runBlocking {
        for (literal in listOf(LiteralCase.USER_COMPACT_TEMPLATE, LiteralCase.STRONG_SUFFIX_DENIAL, LiteralCase.DOCUMENTARY_FORMAT)) {
            verifyReply(Transport.ORDINARY, stop = true, literal = literal)
            verifyReply(Transport.EXPERIMENTAL_TOOL, stop = false, literal = literal)
        }
    }

    @Test fun boundedLogQuotationSurvivesActualMultipartStopAndDone() = runBlocking {
        verifyReply(Transport.EXPERIMENTAL_TOOL, stop = true, literal = LiteralCase.BOUNDED_LOG_QUOTATION)
        verifyReply(Transport.EXPERIMENTAL_TOOL, stop = false, literal = LiteralCase.BOUNDED_LOG_QUOTATION)
    }

    @Test fun stopPersistsTwoCompleteToolCallsWhileTheProviderTerminalIsHeld() = runBlocking {
        verifyReply(Transport.EXPERIMENTAL_TOOL, stop = true, stopAfterCompleteToolCalls = true)
    }

    @Test fun ordinaryPreviewAndColdSaveCleanNeighboringLegacyAndModernFramesInEitherOrder() = runBlocking {
        verifyReply(Transport.ORDINARY, stop = false, legacyFirst = true)
        verifyReply(Transport.ORDINARY, stop = false, legacyFirst = false)
    }

    @Test fun actualRepairStopPersistsTwoCompleteCallsFromOneFrameWithNoTerminal() = runBlocking {
        verifyRepair(Transport.EXPERIMENTAL_TOOL, stop = true)
    }

    @Test fun actualRepairStopKeepsInvalidOrNoncontiguousSecondCallsOutOfColdStorage() = runBlocking {
        for (fixture in listOf(RepairSecondCall.INCOMPLETE, RepairSecondCall.MALFORMED, RepairSecondCall.GAP)) {
            verifyRepair(Transport.EXPERIMENTAL_TOOL, stop = true, secondCall = fixture)
        }
    }

    @Test fun repairOrphanCleanupUsesValidatedToolPartsOnStopAndDone() = runBlocking {
        verifyRepair(Transport.EXPERIMENTAL_TOOL, stop = true, orphan = RepairOrphan.FIRST)
        verifyRepair(Transport.EXPERIMENTAL_TOOL, stop = false, orphan = RepairOrphan.SECOND_AFTER_FULL_FRAME)
    }

    @Test fun ordinaryRepairStopAndCompletedToolRepairRetainTheirOwnNotices() = runBlocking {
        verifyRepair(Transport.ORDINARY, stop = true)
        verifyRepair(Transport.EXPERIMENTAL_TOOL, stop = false)
    }

    private suspend fun verifyTransport(transport: Transport) {
        verifyReply(transport, stop = true)
        verifyReply(transport, stop = false)
    }

    private suspend fun verifyReply(
        transport: Transport,
        stop: Boolean,
        continuation: String? = null,
        literal: LiteralCase? = null,
        stopAfterCompleteToolCalls: Boolean = false,
        legacyFirst: Boolean? = null,
        toolIndexes: List<Int>? = null,
    ) {
        val app = ChatBarApp.instance
        val settings = app.settingsRepository.getAppSettings()
        val cardName = "Synthetic narrative wire"
        val store = ViewModelStore()
        val mode = if (stop && legacyFirst == null) ChatNarrativeMode.FOCUS else ChatNarrativeMode.ADVANCE
        val prior = if (literal == LiteralCase.ADJACENT_AUTHORED) "旧文。" else "Previous synthetic scene."
        val includePrior = literal != LiteralCase.CARD_ORPHAN_NO_HISTORY
        val prose = when (literal) {
            LiteralCase.ADJACENT_AUTHORED -> prior
            LiteralCase.CHARACTER_POST_HISTORY -> "$cardName turned the page."
            else -> "She opens the door and walks into the courtyard."
        }
        val template = PromptTemplates.narrativeModeHistoryReply("[story]", mode).prependIndent("> ")
        val framedReply = PromptTemplates.narrativeModeHistoryReply(prose, mode)
        val opener = framedReply.substringBefore('\n')
        val closing = framedReply.substringAfterLast('\n')
        val exactInlineCopy = "$opener\n例子$closing"
        val exactCopyWithInlineCode = "$opener\nThe closing-tag token is `$closing`.\n$closing"
        val sameLineCopyWithInlineCode = "${opener}Use `$closing` here$closing"
        val adjacentAuthored = "$opener\n须保留。\n$closing"
        val multilineCopy = "$opener\nKeep this exactly.\n$closing"
        val splitCaption = "The literal closing tag is:"
        val bodyWithClosingLiteral = "屏幕显示 `$closing`。新句。"
        val historicalTemplate = PromptTemplates.narrativeModeHistoryReply("[story]", mode)
        val customTail = when (literal) {
            LiteralCase.CHARACTER_POST_HISTORY -> "Copy this text verbatim:\n" +
                PromptTemplates.narrativeModeHistoryReply("{{char}} turned the page.", mode)
            LiteralCase.CHARACTER_TAIL_OPEN -> "Please show the literal opening tag only on its own line, then explain it:\n$opener"
            LiteralCase.CHARACTER_TAIL_CLOSE -> "Please show the literal closing tag only on its own line:\n$closing"
            else -> ""
        }
        val customCore = "Required output template (keep both tags verbatim):\n$historicalTemplate"
            .takeIf { literal == LiteralCase.CHARACTER_CORE }.orEmpty()
        val card = CharacterCard.create(cardName).copy(systemPrompt = customCore, postHistoryInstructions = customTail)
        val historicalUser = when {
            literal == LiteralCase.HISTORICAL_TEMPLATE_PROHIBITED ->
                "Use exactly this XML shape, replace [story] with new prose and keep both tags:\n$historicalTemplate"
            literal == LiteralCase.CHARACTER_POST_HISTORY && !stop ->
                "The token `$closing` is printed in the manual."
            else -> null
        }
        val selectedFormatCard = when (literal) {
            LiteralCase.CARD_ORPHAN_NO_HISTORY -> {
                val sample = PromptTemplates.narrativeModeHistoryReply("wrong", mode)
                FormatCard.create("Synthetic prohibited history sample",
                    "The following is a counterexample:\n$sample\nDo not output these history tags in your reply.")
            }
            LiteralCase.DOCUMENTARY_FORMAT -> FormatCard.create(
                "Synthetic historical reference",
                "Reference only — the following tags record older history; never reproduce this sample in the answer:\n" +
                    PromptTemplates.narrativeModeHistoryReply("An older turn.", mode) + "\nWrite plain narration.",
            )
            LiteralCase.STRONG_SUFFIX_DENIAL -> FormatCard.create("Synthetic strong denial", "Continue in ordinary prose.").copy(
                userTools = listOf(FormatCardUserToolConfig.strongPromptSuffix().copy(
                    text = "Do not output the following history tags:\n$historicalTemplate",
                )),
            )
            LiteralCase.STRONG_SUFFIX_OPEN -> FormatCard.create("Synthetic strong suffix", "Continue in ordinary prose.").copy(
                userTools = listOf(FormatCardUserToolConfig.strongPromptSuffix().copy(
                    text = "Show the literal opening tag only on its own line, then explain it:\n$opener",
                )),
            )
            else -> null
        }
        val listExample = "- A literal closing-tag example:\n$closing"
        val captionedExample = "The closing tag example:\n$closing"
        val lazyQuoteExample = "> Closing tag example:\n$closing"
        val ordinaryCaption = "The screen shows this literal closing tag:\n$closing"
        val ordinaryCaptionedBody = if (stop) ordinaryCaption else "$ordinaryCaption\nShe turned the page."
        val quoteLead = "Below is a literal quotation copied from the history log."
        val quoteTail = "That was a verbatim quotation from the log."
        val listBody = if (stop) listExample else "$listExample\n\nThe explanation continues."
        val authorsTemplate = !stop && continuation == null && literal == null && legacyFirst == null && toolIndexes == null
        val input = when {
            literal == LiteralCase.OPENING -> "Please show the literal opening tag only on its own line, then explain it:\n$opener"
            literal == LiteralCase.INLINE_CLOSE -> "请逐字照抄以下文本并保留首尾标签：\n$exactInlineCopy"
            literal == LiteralCase.INLINE_CODE_CLOSE -> "Copy this text verbatim:\n$exactCopyWithInlineCode"
            literal == LiteralCase.SAME_LINE_INLINE_CODE_CLOSE -> "Copy this verbatim: $sameLineCopyWithInlineCode"
            literal == LiteralCase.ADJACENT_AUTHORED -> "Copy this verbatim:\n$adjacentAuthored"
            literal == LiteralCase.SAME_LINE_DIRECTIVE_MULTILINE -> "Copy the following text verbatim: $multilineCopy"
            literal == LiteralCase.CAPTIONED_CLOSE -> "Please show the literal closing tag only on its own line:\n$closing"
            literal == LiteralCase.AUTHORED_CLOSE_EXTRA -> "Please preserve the literal token `$closing`."
            literal == LiteralCase.HISTORICAL_TEMPLATE_PROHIBITED -> "Do not output the following history tags:\n$historicalTemplate"
            literal == LiteralCase.USER_COMPACT_TEMPLATE ->
                "Use exactly this XML template, replace [story] with new prose and keep both tags verbatim:\n" +
                    historicalTemplate.replace("\n", "")
            literal == LiteralCase.STRONG_SUFFIX_DENIAL -> "Required output template (keep both tags verbatim):\n$historicalTemplate"
            authorsTemplate -> "Use exactly this XML shape, replace [story] with new prose and keep both tags:\n$template"
            else -> "Continue the scene."
        }
        val legacyOpen = "<聚焦模式=\"沉浸\">"
        val legacyClose = "</聚焦模式>"
        val priorLegacy = "$legacyOpen\nPrevious legacy scene.\n$legacyClose".takeIf { legacyFirst != null }
        val legacyScene = "A legacy scene follows."
        val legacyReply = "$legacyOpen\n$legacyScene\n$legacyClose"
        val reply = when (literal) {
            LiteralCase.OPENING -> "$opener\nThis is the opening tag."
            LiteralCase.STRONG_SUFFIX_OPEN -> "$opener\nThis is the opening tag."
            LiteralCase.INLINE_CLOSE -> exactInlineCopy
            LiteralCase.INLINE_CODE_CLOSE -> exactCopyWithInlineCode
            LiteralCase.SAME_LINE_INLINE_CODE_CLOSE -> sameLineCopyWithInlineCode
            LiteralCase.ADJACENT_AUTHORED -> "$framedReply\n$adjacentAuthored"
            LiteralCase.SAME_LINE_DIRECTIVE_MULTILINE -> multilineCopy
            LiteralCase.TOOL_SPLIT_CAPTION -> "$splitCaption\n$closing"
            LiteralCase.CARD_ORPHAN_NO_HISTORY -> "$prose\n$closing"
            LiteralCase.PADDED_OPENER -> "$opener \t\r\n$prose"
            LiteralCase.AUTHORED_CLOSE_EXTRA -> "$bodyWithClosingLiteral\n$closing"
            LiteralCase.HISTORICAL_TEMPLATE_PROHIBITED -> framedReply
            LiteralCase.CAPTIONED_CLOSE -> if (stop) "$opener\n$captionedExample" else "$opener\n$captionedExample\n$closing"
            LiteralCase.LAZY_QUOTE_CLOSE -> if (stop) "$opener\n$lazyQuoteExample" else "$opener\n$lazyQuoteExample\n$closing"
            LiteralCase.LAZY_LIST_CLOSE -> if (stop) "$opener\n$listBody" else PromptTemplates.narrativeModeHistoryReply(listBody, mode)
            LiteralCase.ORDINARY_CAPTIONED_CLOSE -> if (stop) "$opener\n$ordinaryCaptionedBody"
                else PromptTemplates.narrativeModeHistoryReply(ordinaryCaptionedBody, mode)
            LiteralCase.CHARACTER_POST_HISTORY, LiteralCase.CHARACTER_CORE, LiteralCase.USER_COMPACT_TEMPLATE,
            LiteralCase.STRONG_SUFFIX_DENIAL, LiteralCase.DOCUMENTARY_FORMAT -> framedReply
            LiteralCase.CHARACTER_TAIL_OPEN -> "$opener\nThis is the opening tag."
            LiteralCase.CHARACTER_TAIL_CLOSE -> "$opener\n$captionedExample"
            LiteralCase.BOUNDED_LOG_QUOTATION -> "$quoteLead\n$framedReply\n$quoteTail"
            null -> when (legacyFirst) {
                true -> "$legacyReply\n$framedReply"
                false -> "$framedReply\n$legacyReply"
                null -> framedReply
            }
        }
        val secondScene = "A bird flies past the entrance."
        val disconnectedText = "This disconnected tool part must never be saved."
        val parts = when {
            toolIndexes != null -> toolIndexes.map { if (it == 0) prose else disconnectedText }
            literal == LiteralCase.TOOL_SPLIT_CAPTION -> listOf(splitCaption, closing)
            literal == LiteralCase.BOUNDED_LOG_QUOTATION -> listOf(quoteLead, framedReply, quoteTail)
            stopAfterCompleteToolCalls -> listOf("$opener\n$prose", "$secondScene\n$closing")
            continuation != null -> listOf(
                reply.substringBeforeLast('\n'), "$secondScene\n${reply.substringAfterLast('\n')}", continuation,
            )
            else -> listOf(reply)
        }
        val expected = when {
            stopAfterCompleteToolCalls -> "$prose\n$secondScene"
            continuation != null -> listOf(prose, secondScene, continuation).joinToString("\n")
            literal == LiteralCase.LAZY_LIST_CLOSE -> listBody
            literal == LiteralCase.CAPTIONED_CLOSE || literal == LiteralCase.CHARACTER_TAIL_CLOSE -> captionedExample
            literal == LiteralCase.LAZY_QUOTE_CLOSE -> lazyQuoteExample
            literal == LiteralCase.ORDINARY_CAPTIONED_CLOSE -> ordinaryCaptionedBody
            literal == LiteralCase.ADJACENT_AUTHORED -> "$prose\n$adjacentAuthored"
            literal == LiteralCase.CARD_ORPHAN_NO_HISTORY -> prose
            literal == LiteralCase.PADDED_OPENER || literal == LiteralCase.HISTORICAL_TEMPLATE_PROHIBITED ||
                literal == LiteralCase.STRONG_SUFFIX_DENIAL || literal == LiteralCase.DOCUMENTARY_FORMAT -> prose
            literal == LiteralCase.AUTHORED_CLOSE_EXTRA -> bodyWithClosingLiteral
            legacyFirst == true -> "$legacyScene\n$prose"
            legacyFirst == false -> "$prose\n$legacyScene"
            authorsTemplate || literal != null -> reply
            else -> prose
        }
        val modelName = "Synthetic narrative wire ${transport.name}"
        val reasoningWitness = "Provider has sent every synthetic call.".takeIf {
            stopAfterCompleteToolCalls || literal == LiteralCase.TOOL_SPLIT_CAPTION || literal == LiteralCase.BOUNDED_LOG_QUOTATION
        }
        HeldNarrativeReplyServer(parts, tool = transport == Transport.EXPERIMENTAL_TOOL, reasoningWitness = reasoningWitness,
            toolIndexes = toolIndexes, finishReason = "length".takeIf { literal == LiteralCase.PADDED_OPENER }).use { server ->
            val model = ModelConfig.create(modelName, server.baseUrl, "fixture-key", "fixture-model")
            val session = ChatSession.create(card.id, modelName).copy(
                modelId = model.id, narrativeMode = mode, longTermMemoryEnabled = false,
                automaticImageGenerationEnabled = false, audiobookModeEnabled = false,
                formatCardId = selectedFormatCard?.id,
            )
            var editor: ChatViewModel? = null
            try {
                app.modelRepository.saveModel(model)
                app.characterRepository.save(card)
                selectedFormatCard?.let { app.formatCardRepository.save(it) }
                app.chatRepository.createSession(session)
                historicalUser?.let { app.chatRepository.addMessage(ChatMessage.create(session.id, MessageRole.USER, it)) }
                priorLegacy?.let {
                    app.chatRepository.addMessage(ChatMessage.create(session.id, MessageRole.ASSISTANT, it).copy(narrativeMode = ChatNarrativeMode.FOCUS))
                }
                if (includePrior) {
                    app.chatRepository.addMessage(ChatMessage.create(session.id, MessageRole.ASSISTANT, prior).copy(narrativeMode = mode))
                }
                app.settingsRepository.saveAppSettings(settings.copy(
                    allowCleartextModelApi = true, defaultEmbeddingId = null, defaultFormatCardId = null,
                    automaticFormatCheckEnabled = false, momentsEnabled = false, disableNarrativeModes = false,
                    experimentalApiModeEnabled = transport.experimental,
                ))
                val vm = rule.runOnIdle {
                    ViewModelProvider(store, ChatViewModelFactory(session.id))[ChatViewModel::class.java]
                }.also { editor = it }
                val priorBodies = listOfNotNull(priorLegacy, historicalUser, prior.takeIf { includePrior })
                rule.waitUntil(20_000) {
                    vm.isModelUsable.value && priorBodies.all { body -> vm.messages.value.any { it.displayContent == body } }
                }
                rule.runOnIdle { assertTrue(vm.sendMessage(input)) }
                val request = withTimeout(20_000) { server.requestBody.await() }
                val messages = sentMessages(request, transport)
                assertEquals(includePrior, messages.any { it.hasMessage("assistant", PromptTemplates.narrativeModeHistoryReply(prior, mode)) })
                priorLegacy?.let { body -> assertTrue(messages.any { it.hasMessage("assistant", body) }) }
                historicalUser?.let { body -> assertTrue(messages.any { it.hasMessage("user", body) }) }
                if (customCore.isNotEmpty()) {
                    val first = Json.parseToJsonElement(request).jsonObject.getValue("messages").jsonArray.first().jsonObject
                    assertEquals("system", first["role"]?.jsonPrimitive?.content)
                    assertTrue(customCore in first.getValue("content").jsonPrimitive.content)
                }
                if (customTail.isNotEmpty()) {
                    val expectedTail = customTail.replace("{{char}}", cardName)
                    val tailRole = if (transport.experimental) "system" else "assistant"
                    assertTrue(messages.any { entry ->
                        entry.jsonObject["role"]?.jsonPrimitive?.content == tailRole &&
                            (entry.jsonObject["content"] as? JsonPrimitive)?.content?.contains(expectedTail) == true
                    })
                }
                selectedFormatCard?.let { format ->
                    assertTrue(messages.any { entry ->
                        (entry.jsonObject["content"] as? JsonPrimitive)?.content?.contains(format.content) == true
                    })
                    for (configuredTool in format.userTools) assertTrue(messages.any { entry ->
                        (entry.jsonObject["content"] as? JsonPrimitive)?.content?.contains(configuredTool.text) == true
                    })
                }
                assertTrue(messages.any { it.hasMessage("user", input) })
                if (literal == LiteralCase.STRONG_SUFFIX_DENIAL) {
                    val user = messages.indexOfFirst { it.hasMessage("user", input) }
                    val role = if (transport.experimental) "system" else "assistant"
                    val strongText = checkNotNull(selectedFormatCard).userTools.single().text
                    val suffix = messages.indexOfFirst { it.hasMessage(role, strongText) }
                    assertTrue("The sent strong denial follows the actual user", user >= 0 && suffix > user)
                }
                // Experimental live preview initially carries the first tool call only.
                val preview = when {
                    toolIndexes != null -> prose
                    literal == LiteralCase.TOOL_SPLIT_CAPTION -> splitCaption
                    literal == LiteralCase.BOUNDED_LOG_QUOTATION -> quoteLead
                    continuation != null || stopAfterCompleteToolCalls -> prose
                    else -> expected
                }
                if (toolIndexes == null || 0 in toolIndexes) {
                    rule.waitUntil(20_000) {
                        val draft = vm.streamingMessage.value
                        draft != null && draft.displayContent == preview &&
                            (reasoningWitness == null || draft.reasoningContent == reasoningWitness)
                    }
                }
                assertFalse("The provider's terminal event must remain held during preview", server.terminalSent.get())
                assertTrue(vm.isResponding.value)

                if (stop) rule.runOnIdle { vm.cancelResponseGeneration() } else server.release()
                rule.waitUntil(20_000) { !vm.isResponding.value }
                assertEquals(!stop, server.terminalSent.get())
                val saved = ChatRepository(JsonFileStorage(app)).getMessages(session.id)
                assertEquals(priorBodies.toSet(), saved.take(priorBodies.size).map(ChatMessage::displayContent).toSet())
                val newMessages = saved.drop(priorBodies.size).map(ChatMessage::displayContent)
                if (toolIndexes == null) assertEquals(listOf(input, expected), newMessages) else {
                    assertEquals(input, newMessages.first())
                    assertTrue(newMessages.last().startsWith("错误:"))
                    assertFalse(newMessages.last().contains(disconnectedText))
                }
                val savedPriorRoles = saved.take(priorBodies.size).map(ChatMessage::role)
                val historicalUserCount = if (historicalUser == null) 0 else 1
                assertEquals(historicalUserCount, savedPriorRoles.count { it == MessageRole.USER })
                assertEquals(priorBodies.size - historicalUserCount, savedPriorRoles.count { it == MessageRole.ASSISTANT })
                assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), saved.drop(priorBodies.size).map(ChatMessage::role))
            } finally {
                try {
                    // The generating job, like memory maintenance, can outlive its ViewModel.
                    editor?.let { vm ->
                        rule.runOnIdle { vm.cancelResponseGeneration() }
                        rule.waitUntil(20_000) { !vm.isResponding.value }
                    }
                } finally {
                    server.release()
                    rule.runOnIdle { store.clear() }
                    try {
                        app.deletionCoordinator.deleteSession(session.id)
                    } finally {
                        app.settingsRepository.saveAppSettings(settings)
                        selectedFormatCard?.let { app.formatCardRepository.delete(it.id) }
                        app.characterRepository.delete(card.id)
                        app.modelRepository.deleteModel(model.id)
                    }
                }
            }
        }
    }

    private suspend fun verifyRepair(
        transport: Transport, stop: Boolean, secondCall: RepairSecondCall = RepairSecondCall.COMPLETE,
        orphan: RepairOrphan? = null,
        toolIndexes: List<Int>? = null,
        repairMode: ChatNarrativeMode = ChatNarrativeMode.FOCUS,
    ) {
        val app = ChatBarApp.instance
        val settings = app.settingsRepository.getAppSettings()
        val card = CharacterCard.create("Synthetic repair wire")
        val store = ViewModelStore()
        val close = "</历史回复>"
        val splitCaption = orphan == RepairOrphan.SPLIT_CAPTION
        val original = when (orphan) {
            RepairOrphan.SPLIT_CAPTION -> "End tag: &lt;/历史回复&gt;."
            RepairOrphan.VISIBLE_INPUT_LITERAL -> "结束标签示例：\n$close\n她关上门。"
            RepairOrphan.VISIBLE_INPUT_BLOCKQUOTE -> "The closing-tag example is $close."
            RepairOrphan.AUTHORED_CLOSE_EXTRA -> "屏幕显示 `$close`。旧句。"
            else -> "旧。"
        }
        val first = when (orphan) {
            RepairOrphan.SPLIT_CAPTION -> "The literal closing tag is:"
            RepairOrphan.VISIBLE_INPUT_LITERAL -> "结束标签示例：\n`$close`\n她关上门。"
            RepairOrphan.VISIBLE_INPUT_BLOCKQUOTE -> "Closing-tag example:\n\n> $close"
            RepairOrphan.AUTHORED_CLOSE_EXTRA -> "屏幕显示 `$close`。新句。"
            else -> "A repaired first line."
        }
        val second = if (splitCaption) close else "A repaired second line."
        val tool = transport == Transport.EXPERIMENTAL_TOOL
        val parts = if (toolIndexes != null) toolIndexes.map { if (it == 0) first else second } else when (orphan) {
            RepairOrphan.FIRST -> listOf("$first\n$close", second)
            RepairOrphan.SECOND_AFTER_FULL_FRAME -> listOf(PromptTemplates.narrativeModeHistoryReply(first, repairMode), "$second\n$close")
            RepairOrphan.SPLIT_CAPTION -> listOf(first, second)
            RepairOrphan.VISIBLE_INPUT_LITERAL -> listOf(PromptTemplates.narrativeModeHistoryReply(first, repairMode))
            RepairOrphan.VISIBLE_INPUT_BLOCKQUOTE -> listOf(PromptTemplates.narrativeModeHistoryReply("$first\n", repairMode))
            RepairOrphan.AUTHORED_CLOSE_EXTRA, RepairOrphan.EXPLICIT_STANDALONE_PROHIBITION -> listOf("$first\n$close")
            null -> if (tool) listOf(first, second) else listOf(first)
        }
        val formatCard = orphan?.let {
            val example = if (orphan == RepairOrphan.VISIBLE_INPUT_BLOCKQUOTE) "示例正文" else "旧示例"
            val sample = PromptTemplates.narrativeModeHistoryReply(example, repairMode)
            val instructions = when (orphan) {
                RepairOrphan.SPLIT_CAPTION -> "Decode written entities in literal examples.\n\nThe following is a counterexample:\n$sample"
                RepairOrphan.EXPLICIT_STANDALONE_PROHIBITION -> "Do not output the following history tags:\n$close"
                else -> "以下是禁止输出的反例：\n$sample"
            }
            FormatCard.create("Synthetic prohibited history wrapper", instructions)
        }
        val firstPreview = MessageFormatRepairPolicy.progressiveOverlay(original, first)
        val expected = when {
            toolIndexes != null -> original
            tool && parts.size > 1 && secondCall == RepairSecondCall.COMPLETE -> "$first\n$second"
            stop -> firstPreview
            else -> first
        }
        HeldNarrativeReplyServer(parts, tool, reasoningWitness = null, secondCall = secondCall, toolIndexes = toolIndexes).use { server ->
            val model = ModelConfig.create("Synthetic repair ${transport.name}", server.baseUrl, "fixture-key", "fixture-model")
            val session = ChatSession.create(card.id, model.displayName).copy(
                modelId = model.id, longTermMemoryEnabled = false, formatCardId = formatCard?.id, narrativeMode = repairMode,
            )
            val message = ChatMessage.create(session.id, MessageRole.ASSISTANT, original)
            var editor: ChatViewModel? = null
            try {
                app.modelRepository.saveModel(model)
                app.characterRepository.save(card)
                formatCard?.let { app.formatCardRepository.save(it) }
                app.chatRepository.createSession(session)
                app.chatRepository.addMessage(message)
                app.settingsRepository.saveAppSettings(settings.copy(
                    allowCleartextModelApi = true, defaultEmbeddingId = null, defaultFormatCardId = null,
                    formatRepairModelId = model.id, assistantSegmentedBubblesEnabled = true,
                    automaticFormatCheckEnabled = false, momentsEnabled = false,
                    experimentalApiModeEnabled = transport.experimental,
                ))
                val vm = rule.runOnIdle {
                    ViewModelProvider(store, ChatViewModelFactory(session.id))[ChatViewModel::class.java]
                }.also { editor = it }
                rule.waitUntil(20_000) { vm.messages.value.any { it.id == message.id && it.displayContent == original } }
                rule.runOnIdle { vm.repairMessageFormat(message.id) }
                val request = withTimeout(20_000) { server.requestBody.await() }
                val messages = sentMessages(request, transport)
                assertTrue(messages.any {
                    it.jsonObject["role"]?.jsonPrimitive?.content == "user" &&
                        (it.jsonObject["content"] as? JsonPrimitive)?.content?.contains(original) == true
                })
                formatCard?.let { format ->
                    assertTrue(messages.any { entry ->
                        (entry.jsonObject["content"] as? JsonPrimitive)?.content?.contains(format.content) == true
                    })
                }
                if (toolIndexes == null || 0 in toolIndexes) {
                    rule.waitUntil(20_000) { vm.messageFormatRepairState.value?.previewContent == firstPreview }
                }
                assertFalse("The provider terminal must be held while both calls are already received", server.terminalSent.get())
                assertTrue(vm.isResponding.value)
                if (stop) rule.runOnIdle { vm.cancelResponseGeneration() } else server.release()
                rule.waitUntil(20_000) { !vm.isResponding.value }
                assertEquals(!stop, server.terminalSent.get())
                val saved = ChatRepository(JsonFileStorage(app)).getMessages(session.id).single()
                assertEquals(message.id, saved.id)
                assertEquals(expected, saved.displayContent)
                val expectedNotice = when {
                    toolIndexes != null -> MessageFormatRepairNoticeKind.ERROR
                    stop -> MessageFormatRepairNoticeKind.STOPPED
                    else -> MessageFormatRepairNoticeKind.APPLIED
                }
                assertEquals(expectedNotice, saved.formatRepairNotice?.kind)
                if (toolIndexes == null) assertEquals(original, saved.formatRepairNotice?.originalContent)
                assertEquals(expected, saved.formatRepairNotice?.targetContent)
            } finally {
                try {
                    editor?.let { vm ->
                        rule.runOnIdle { vm.cancelResponseGeneration() }
                        rule.waitUntil(20_000) { !vm.isResponding.value }
                    }
                } finally {
                    server.release()
                    rule.runOnIdle { store.clear() }
                    try {
                        app.deletionCoordinator.deleteSession(session.id)
                    } finally {
                        app.settingsRepository.saveAppSettings(settings)
                        formatCard?.let { app.formatCardRepository.delete(it.id) }
                        app.characterRepository.delete(card.id)
                        app.modelRepository.deleteModel(model.id)
                    }
                }
            }
        }
    }

    private fun sentMessages(request: String, transport: Transport): JsonArray {
        val payload = Json.parseToJsonElement(request).jsonObject
        if (!transport.experimental) return payload.getValue("messages").jsonArray
        val tools = payload.getValue("tools").jsonArray.map { it.jsonObject.getValue("function").jsonObject }
        assertTrue(tools.any { it.getValue("name").jsonPrimitive.content == "game_content" })
        val context = tools.single { it.getValue("name").jsonPrimitive.content == "context" }
            .getValue("description").jsonPrimitive.content
        // Experimental transport intentionally leaves its last assistant string open.
        return Json.parseToJsonElement(context + "\"}]").jsonArray
    }

    private fun JsonElement.hasMessage(role: String, content: String): Boolean =
        jsonObject["role"]?.jsonPrimitive?.content == role &&
            (jsonObject["content"] as? JsonPrimitive)?.content == content

    private class HeldNarrativeReplyServer(
        parts: List<String>, tool: Boolean, reasoningWitness: String?, secondCall: RepairSecondCall = RepairSecondCall.COMPLETE,
        toolIndexes: List<Int>? = null,
        finishReason: String? = null,
    ) : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val terminal = CountDownLatch(1)
        val baseUrl = "http://127.0.0.1:${server.localPort}/v1"
        val requestBody = CompletableDeferred<String>()
        val terminalSent = AtomicBoolean(false)
        private val worker = thread(name = "synthetic-narrative-wire", isDaemon = true) {
            runCatching {
                server.accept().use { socket ->
                    // Read a byte at a time through a one-byte charset so UTF-8 JSON length stays exact.
                    val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    val headers = generateSequence(input::readLine).takeWhile(String::isNotEmpty).toList()
                    val length = headers.single { it.substringBefore(':').equals("Content-Length", ignoreCase = true) }
                        .substringAfter(':').trim().toInt()
                    val bytes = ByteArray(length) { input.read().also { check(it >= 0) }.toByte() }
                    requestBody.complete(bytes.toString(Charsets.UTF_8))
                    val first = "data: ${delta(parts, tool, reasoningWitness, secondCall, toolIndexes)}\n\n".toByteArray(Charsets.UTF_8)
                    val reason = finishReason ?: if (tool) "tool_calls" else "stop"
                    val last = ("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"$reason\"}]}\n\n" +
                        "data: [DONE]\n\n").toByteArray(Charsets.UTF_8)
                    val response = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n" +
                        "Content-Length: ${first.size + last.size}\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().apply {
                        write(response.toByteArray(Charsets.US_ASCII))
                        write(first)
                        flush()
                        if (terminal.await(60, TimeUnit.SECONDS)) {
                            terminalSent.set(true)
                            write(last)
                            flush()
                        }
                    }
                }
            }.onFailure { requestBody.completeExceptionally(it) }
        }

        fun release() = terminal.countDown()

        override fun close() {
            release()
            server.close()
            worker.join(1_000)
        }

        private fun delta(parts: List<String>, tool: Boolean, reasoningWitness: String?, secondCall: RepairSecondCall, toolIndexes: List<Int>?): String {
            val frame = if (tool) {
                buildJsonObject {
                    reasoningWitness?.let { put("reasoning_content", it) }
                    put("tool_calls", buildJsonArray {
                        parts.forEachIndexed { index, part ->
                            add(buildJsonObject {
                                val callIndex = toolIndexes?.get(index) ?: if (index == 1 && secondCall == RepairSecondCall.GAP) 2 else index
                                put("index", callIndex)
                                put("id", "fixture-$index")
                                put("type", "function")
                                put("function", buildJsonObject {
                                    put("name", "game_content")
                                    val arguments = when {
                                        index != 1 -> buildJsonObject { put("content", part) }.toString()
                                        secondCall == RepairSecondCall.INCOMPLETE -> """{"content":"unfinished"""
                                        secondCall == RepairSecondCall.MALFORMED -> """{"content":42}"""
                                        else -> buildJsonObject { put("content", part) }.toString()
                                    }
                                    put("arguments", arguments)
                                })
                            })
                        }
                    })
                }.toString()
            } else {
                buildJsonObject { put("content", parts.single()) }.toString()
            }
            return """{"choices":[{"delta":$frame}]}"""
        }
    }
}
