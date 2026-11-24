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
import com.example.chatbar.data.local.entity.FormatPromptPosition
import com.example.chatbar.data.local.entity.MessageFormatRepairNoticeKind
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.data.local.entity.WorldBookEntry
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
import org.junit.Assert.assertNull
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
        DOCUMENTARY_FORMAT, BOUNDED_LOG_QUOTATION, BRACE_SLOT_TEMPLATE, QUOTED_POSSESSIVE,
        SUPPLEMENTARY_TEMPLATE, UNSENT_GREETING_CARD, PARAGRAPH_QUOTE, GERMAN_QUOTE, PLAYER_TEMPLATE, PARTIAL_OUTER_CLOSE,
        COMMON_OUTPUT_TEMPLATE, AUTHOR_SUPPLEMENTARY_TITLE, OLD_TEMPLATE_NO_XML,
        PROJECTED_RAW_INNER, PROJECTED_EXACT_BYTES, AMBIGUOUS_TOOL_LIST,
        SENT_CORE_ECHO, SENT_SWITCH_ECHO, NEGATIVE_MODE_HEADING, EXACT_FORMAT_BOUNDARIES, PROJECTED_PREFIX_NEW_STORY,
        SENT_COMPLETE_MODE_SWITCH, SENT_HISTORY_HEADING, UNSENT_HISTORY_HEADING, REQUESTED_CITATION, PROJECTED_SUFFIX_HEADING,
        SENT_MODE_SUFFIX, SENT_SWITCH_SUFFIX, REQUESTED_CITATION_CHINESE,
        DISABLED_HISTORY_HEADING, DISABLED_UNSENT_HEADING, DISABLED_AUTHORED_HEADING, DISABLED_LITERAL_XML, REQUESTED_CITATION_TAGS_LAST,
        DISABLED_MIDDLE_TOOL_HEADING, WORLDBOOK_AUTHORED_HEADING, WORLDBOOK_INCIDENTAL_HEADING,
        FULL_MODE_BLOCK_INTERIOR, FULL_MODE_BLOCK_THEN_AUTHORED_TITLE,
        SPLIT_MODE_BLOCK, WORLDBOOK_EXACT_XML, WORLDBOOK_BOUNDARY_XML,
        WORLDBOOK_EXACT_CLOSE, DISPLAY_CAPTION_TITLE, UNLABELLED_MODE_SWITCH, FRESH_FRAME_WHITESPACE,
        WORLDBOOK_EXACT_OPEN, CHARACTER_EXAMPLE_HEADING, FIRST_ADVANCE_SWITCH,
    }
    private enum class RepairSecondCall { COMPLETE, INCOMPLETE, MALFORMED, GAP }
    private enum class RepairOrphan {
        FIRST, SECOND_AFTER_FULL_FRAME, SPLIT_CAPTION, VISIBLE_INPUT_LITERAL, VISIBLE_INPUT_BLOCKQUOTE, AUTHORED_CLOSE_EXTRA,
        EXPLICIT_STANDALONE_PROHIBITION, QUOTED_POSSESSIVE, PARAGRAPH_QUOTE, GERMAN_QUOTE, INDENTED_REPAIR_CARD,
        DISPATCHED_BOUNDARIES, AUTHORED_BOUNDARY, SYSTEM_PREAMBLE, AUTHORED_SYSTEM_PREAMBLE, INTERIOR_SYSTEM_PREAMBLE,
        SPLIT_SYSTEM_PREAMBLE,
        UNLABELLED_SYSTEM_INTRODUCTION,
        WHOLE_REPAIR_SYSTEM_BLOCK, WHOLE_REPAIR_SYSTEM_SUFFIX, WHOLE_REPAIR_SYSTEM_INTERIOR,
        REPAIR_SYSTEM_ECHO_ONLY, PARTIAL_REPAIR_TAIL,
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

    @Test fun braceSlotPreservesBothTagsDuringActualStopAndDone() = runBlocking {
        verifyReply(Transport.ORDINARY, stop = true, literal = LiteralCase.BRACE_SLOT_TEMPLATE)
        verifyReply(Transport.EXPERIMENTAL_TOOL, stop = false, literal = LiteralCase.BRACE_SLOT_TEMPLATE)
    }

    @Test fun quotedPossessivePreservesLiteralClosingTagInChatAndRepairColdStorage() = runBlocking {
        verifyReply(Transport.ORDINARY, stop = true, literal = LiteralCase.QUOTED_POSSESSIVE)
        verifyReply(Transport.EXPERIMENTAL_TOOL, stop = false, literal = LiteralCase.QUOTED_POSSESSIVE)
        verifyRepair(Transport.ORDINARY, stop = true, orphan = RepairOrphan.QUOTED_POSSESSIVE)
        verifyRepair(Transport.EXPERIMENTAL_TOOL, stop = false, orphan = RepairOrphan.QUOTED_POSSESSIVE,
            repairMode = ChatNarrativeMode.ADVANCE)
    }

    @Test fun actualSupplementaryTemplateAndUnsentGreetingFormatReachColdStorage() = runBlocking {
        for (literal in listOf(LiteralCase.SUPPLEMENTARY_TEMPLATE, LiteralCase.UNSENT_GREETING_CARD)) {
            verifyReply(Transport.ORDINARY, stop = true, literal = literal)
            verifyReply(Transport.EXPERIMENTAL_TOOL, stop = false, literal = literal)
        }
    }

    @Test fun paragraphAndGermanQuotesPreserveLiteralTagsInChatAndRepair() = runBlocking {
        verifyReply(Transport.ORDINARY, stop = true, literal = LiteralCase.PARAGRAPH_QUOTE)
        verifyReply(Transport.EXPERIMENTAL_TOOL, stop = false, literal = LiteralCase.GERMAN_QUOTE)
        verifyRepair(Transport.ORDINARY, stop = true, orphan = RepairOrphan.PARAGRAPH_QUOTE)
        verifyRepair(Transport.EXPERIMENTAL_TOOL, stop = false, orphan = RepairOrphan.GERMAN_QUOTE,
            repairMode = ChatNarrativeMode.ADVANCE)
    }

    @Test fun automaticRepairDoesNotTreatUserProhibitionAsLiteralRetention() = runBlocking {
        verifyAutomaticRepairDenial(stop = true)
        verifyAutomaticRepairDenial(stop = false)
    }

    @Test fun playerOverrideAndGlobalPersonaPreserveDeclaredReplyTags() = runBlocking {
        verifyReply(Transport.ORDINARY, stop = true, literal = LiteralCase.PLAYER_TEMPLATE)
        verifyReply(Transport.EXPERIMENTAL_TOOL, stop = false, literal = LiteralCase.PLAYER_TEMPLATE, useGlobalPersona = true)
    }

    @Test fun partialTransportCloseStaysHiddenInActualStopAndDone() = runBlocking {
        verifyReply(Transport.ORDINARY, stop = true, literal = LiteralCase.PARTIAL_OUTER_CLOSE)
        verifyReply(Transport.EXPERIMENTAL_TOOL, stop = false, literal = LiteralCase.PARTIAL_OUTER_CLOSE)
    }

    @Test fun autoAndManualRepairUseOnlyTheNormalizedSentInput() = runBlocking {
        verifyAutomaticRepairDenial(stop = true, unusedExample = true)
        verifyAutomaticRepairDenial(stop = false, unusedExample = true)
        verifyRepair(Transport.ORDINARY, stop = true, orphan = RepairOrphan.INDENTED_REPAIR_CARD)
        verifyRepair(Transport.EXPERIMENTAL_TOOL, stop = false, orphan = RepairOrphan.INDENTED_REPAIR_CARD,
            repairMode = ChatNarrativeMode.ADVANCE)
    }

    @Test fun actualPositiveOutputDeclarationAndAuthoredTitleSurviveStopAndDone() = runBlocking {
        for (literal in listOf(LiteralCase.COMMON_OUTPUT_TEMPLATE, LiteralCase.AUTHOR_SUPPLEMENTARY_TITLE)) {
            verifyReply(Transport.ORDINARY, stop = true, literal = literal)
            verifyReply(Transport.EXPERIMENTAL_TOOL, stop = false, literal = literal)
        }
    }

    @Test fun explicitCurrentPlainReplySupersedesOnlyTheHistoricalTemplateOnDisk() = runBlocking {
        verifyReply(Transport.ORDINARY, stop = true, literal = LiteralCase.OLD_TEMPLATE_NO_XML)
        verifyReply(Transport.EXPERIMENTAL_TOOL, stop = false, literal = LiteralCase.OLD_TEMPLATE_NO_XML)
    }

    @Test fun generatedProjectionPreservesRawInnerAndExactOriginalWhitespaceOnDisk() = runBlocking {
        for (literal in listOf(LiteralCase.PROJECTED_RAW_INNER, LiteralCase.PROJECTED_EXACT_BYTES)) {
            verifyReply(Transport.ORDINARY, stop = true, literal = literal)
            verifyReply(Transport.EXPERIMENTAL_TOOL, stop = false, literal = literal)
        }
    }

    @Test fun ambiguousListStaysIntactOnActualToolStopAndDone() = runBlocking {
        verifyReply(Transport.EXPERIMENTAL_TOOL, stop = true, literal = LiteralCase.AMBIGUOUS_TOOL_LIST)
        verifyReply(Transport.EXPERIMENTAL_TOOL, stop = false, literal = LiteralCase.AMBIGUOUS_TOOL_LIST)
    }

    @Test fun actualModePromptEchoAndDirectTitleDenialStayOutOfColdStorage() = runBlocking {
        for (literal in listOf(LiteralCase.SENT_CORE_ECHO, LiteralCase.SENT_SWITCH_ECHO, LiteralCase.NEGATIVE_MODE_HEADING)) {
            verifyReply(Transport.ORDINARY, stop = true, literal = literal)
            verifyReply(Transport.EXPERIMENTAL_TOOL, stop = false, literal = literal)
        }
    }

    @Test fun actualFormatBoundaryAndExactHistoryContinuationSurviveColdStorage() = runBlocking {
        for (literal in listOf(LiteralCase.EXACT_FORMAT_BOUNDARIES, LiteralCase.PROJECTED_PREFIX_NEW_STORY)) {
            verifyReply(Transport.ORDINARY, stop = true, literal = literal)
            verifyReply(Transport.EXPERIMENTAL_TOOL, stop = false, literal = literal)
        }
        verifyReply(Transport.EXPERIMENTAL_TOOL, stop = true, literal = LiteralCase.PROJECTED_PREFIX_NEW_STORY)
    }

    @Test fun completeDispatchedModeAndRoutineHistoryHeadingsStayOutOfColdStorage() = runBlocking {
        for (literal in listOf(
            LiteralCase.SENT_COMPLETE_MODE_SWITCH, LiteralCase.SENT_HISTORY_HEADING, LiteralCase.UNSENT_HISTORY_HEADING,
        )) {
            verifyReply(Transport.ORDINARY, stop = true, literal = literal)
            verifyReply(Transport.EXPERIMENTAL_TOOL, stop = false, literal = literal)
        }
    }

    @Test fun actualHistoryCitationAndNewProseKeepTheirOriginalBytesOnDisk() = runBlocking {
        for (literal in listOf(LiteralCase.REQUESTED_CITATION, LiteralCase.PROJECTED_SUFFIX_HEADING)) {
            verifyReply(Transport.ORDINARY, stop = true, literal = literal)
            verifyReply(Transport.EXPERIMENTAL_TOOL, stop = false, literal = literal)
        }
    }

    @Test fun wrappedModeSuffixAndChineseCitationKeepTheIntendedColdReply() = runBlocking {
        for (literal in listOf(LiteralCase.SENT_MODE_SUFFIX, LiteralCase.SENT_SWITCH_SUFFIX, LiteralCase.REQUESTED_CITATION_CHINESE)) {
            verifyReply(Transport.ORDINARY, stop = true, literal = literal)
            verifyReply(Transport.EXPERIMENTAL_TOOL, stop = false, literal = literal)
        }
    }

    @Test fun disabledNarrativeUsesOnlyTheRoutineHeadingsActuallySentOnTheWire() = runBlocking {
        for (literal in listOf(
            LiteralCase.DISABLED_HISTORY_HEADING, LiteralCase.DISABLED_UNSENT_HEADING,
            LiteralCase.DISABLED_AUTHORED_HEADING, LiteralCase.DISABLED_LITERAL_XML,
        )) {
            verifyReply(Transport.ORDINARY, stop = true, literal = literal)
            verifyReply(Transport.ORDINARY, stop = false, literal = literal)
        }
    }

    @Test fun explicitTagsLastCitationKeepsTheExactLastAssistantProjection() = runBlocking {
        verifyReply(Transport.ORDINARY, stop = true, literal = LiteralCase.REQUESTED_CITATION_TAGS_LAST)
        verifyReply(Transport.EXPERIMENTAL_TOOL, stop = false, literal = LiteralCase.REQUESTED_CITATION_TAGS_LAST)
    }

    @Test fun disabledMiddleToolHeadingAndActiveWorldBookOutputAreVerifiedOnTheWire() = runBlocking {
        verifyReply(Transport.EXPERIMENTAL_TOOL, stop = true, literal = LiteralCase.DISABLED_MIDDLE_TOOL_HEADING)
        verifyReply(Transport.EXPERIMENTAL_TOOL, stop = false, literal = LiteralCase.DISABLED_MIDDLE_TOOL_HEADING)
        verifyReply(Transport.ORDINARY, stop = true, literal = LiteralCase.WORLDBOOK_AUTHORED_HEADING)
        verifyReply(Transport.EXPERIMENTAL_TOOL, stop = false, literal = LiteralCase.WORLDBOOK_INCIDENTAL_HEADING)
    }

    @Test fun fullModeEchoDoesNotReplaceAnIndependentlyAuthoredTitleInColdStorage() = runBlocking {
        for (literal in listOf(LiteralCase.FULL_MODE_BLOCK_INTERIOR, LiteralCase.FULL_MODE_BLOCK_THEN_AUTHORED_TITLE)) {
            verifyReply(Transport.ORDINARY, stop = true, literal = literal)
            verifyReply(Transport.EXPERIMENTAL_TOOL, stop = false, literal = literal)
        }
    }

    @Test fun authoredTitleSurvivesAnEchoedCompleteSuffixThroughStopAndDone() = runBlocking {
        for ((transport, stop) in listOf(Transport.ORDINARY to true, Transport.EXPERIMENTAL_TOOL to false)) {
            val base = buildReplyFixture(transport, stop)
            val issued = PromptTemplates.narrativeModeSystemPrompt(base.mode)
            val title = issued.lineSequence().first()
            val wanted = "$title\nShe turned the page."
            val echoed = "$wanted\n\n$issued"
            runReplyFixture(base.copy(
                sentModeBlock = issued,
                input = "Begin the reply with this exact heading verbatim:\n$title",
                parts = listOf(echoed), preview = wanted, expected = wanted,
            ))
            // A literal copy of the whole block is deliberately different from a title request.
            runReplyFixture(base.copy(
                sentModeBlock = issued, input = "Copy this text verbatim:\n$echoed",
                parts = listOf(echoed), preview = echoed, expected = echoed,
            ))
        }
    }

    @Test fun validatedCallsAndAuthoredWorldBookXmlPreserveTheColdReply() = runBlocking {
        verifyReply(Transport.EXPERIMENTAL_TOOL, stop = true, literal = LiteralCase.SPLIT_MODE_BLOCK)
        verifyReply(Transport.EXPERIMENTAL_TOOL, stop = false, literal = LiteralCase.SPLIT_MODE_BLOCK)
        verifyReply(Transport.ORDINARY, stop = true, literal = LiteralCase.WORLDBOOK_EXACT_XML)
        verifyReply(Transport.EXPERIMENTAL_TOOL, stop = false, literal = LiteralCase.WORLDBOOK_EXACT_XML)
    }

    @Test fun activeWorldBookCloserAndDisplayedTitleRemainAtTheEndOfAColdReply() = runBlocking {
        for (literal in listOf(LiteralCase.WORLDBOOK_EXACT_CLOSE, LiteralCase.DISPLAY_CAPTION_TITLE)) {
            verifyReply(Transport.ORDINARY, stop = true, literal = literal)
            verifyReply(Transport.EXPERIMENTAL_TOOL, stop = false, literal = literal)
        }
    }

    @Test fun genuineAppFirstSwitchAndExplicitStableAuthorsSurviveTheColdPaths() = runBlocking {
        for (literal in listOf(LiteralCase.WORLDBOOK_EXACT_OPEN, LiteralCase.CHARACTER_EXAMPLE_HEADING, LiteralCase.FIRST_ADVANCE_SWITCH)) {
            verifyReply(Transport.ORDINARY, stop = true, literal = literal)
            verifyReply(Transport.EXPERIMENTAL_TOOL, stop = false, literal = literal)
        }
    }

    @Test fun fullIssuedRepairRulesStayOutOfColdStorage() = runBlocking {
        verifyRepair(Transport.ORDINARY, stop = true, orphan = RepairOrphan.WHOLE_REPAIR_SYSTEM_BLOCK)
        verifyRepair(Transport.EXPERIMENTAL_TOOL, stop = false, orphan = RepairOrphan.WHOLE_REPAIR_SYSTEM_BLOCK)
    }

    @Test fun authoredPostHistoryTitleAndUnpaddedFencedCodeSurviveTheColdPaths() = runBlocking {
        val heading = PromptTemplates.sectionHeading(PromptTemplates.SECTION_PREVIOUS_TURN)
        val directive = "Print this heading verbatim: $heading"
        val story = "$heading\nThe lamp was still lit."
        val code = "\n```kotlin\nval stored = 7\n```\n"
        for (stop in listOf(true, false)) {
            val transport = if (stop) Transport.ORDINARY else Transport.EXPERIMENTAL_TOOL
            val base = buildReplyFixture(transport, stop = true).copy(stop = stop)
            runReplyFixture(base.copy(
                card = base.card.copy(postHistoryInstructions = directive),
                historicalUser = "Light the lamp.", prior = "She lit the lamp.",
                sentRoutineHistoryHeading = true, renderedTail = directive,
                input = "Continue.", parts = listOf(story), expected = story, preview = story,
            ))
            val tail = "\n\n"
            runReplyFixture(base.copy(
                parts = listOf(PromptTemplates.narrativeModeHistoryReply(code, base.mode) + tail),
                expected = code + tail, preview = code + tail,
            ))
        }
    }

    @Test fun appendedIssuedRepairSystemRulesStayOutOfColdStorage() = runBlocking {
        for (stop in listOf(true, false)) {
            verifyRepair(Transport.ORDINARY, stop, orphan = RepairOrphan.WHOLE_REPAIR_SYSTEM_SUFFIX)
            verifyRepair(Transport.EXPERIMENTAL_TOOL, stop, orphan = RepairOrphan.WHOLE_REPAIR_SYSTEM_SUFFIX)
        }
    }

    @Test fun wholeIssuedRepairSystemInteriorPreservesBothColdParagraphs() = runBlocking {
        for (transport in listOf(Transport.ORDINARY, Transport.EXPERIMENTAL_TOOL)) {
            verifyRepair(transport, stop = true, orphan = RepairOrphan.WHOLE_REPAIR_SYSTEM_INTERIOR)
            verifyRepair(transport, stop = false, orphan = RepairOrphan.WHOLE_REPAIR_SYSTEM_INTERIOR)
        }
    }

    @Test fun repairEchoOnlyAndInterruptedTailKeepTheRightColdMessage() = runBlocking {
        for (transport in listOf(Transport.ORDINARY, Transport.EXPERIMENTAL_TOOL)) {
            verifyRepair(transport, stop = false, orphan = RepairOrphan.REPAIR_SYSTEM_ECHO_ONLY)
            verifyRepair(transport, stop = true, orphan = RepairOrphan.PARTIAL_REPAIR_TAIL)
        }
    }

    @Test fun headedChoicesAndTwoSentenceCitationPreserveExactlyTheirColdBytes() = runBlocking {
        for (stop in listOf(true, false)) {
            val transport = if (stop) Transport.ORDINARY else Transport.EXPERIMENTAL_TOOL
            val base = buildReplyFixture(transport, stop = true).copy(stop = stop, prior = "The rain stopped.")
            val story = "The lamp lit up."
            val options = "\n\n---\n**【行动选项】**\n\n1. **[Open the door]()**\n   Wait for the latch to click.\n2. **[Stay here]()**\n---"
            runReplyFixture(base.copy(
                parts = listOf(PromptTemplates.narrativeModeHistoryReply(story, base.mode) + options),
                expected = story + options, preview = story + options,
            ))
            val quote = PromptTemplates.narrativeModeHistoryReply(base.prior, base.mode) +
                "\n\nThe opening element identifies the reply mode."
            runReplyFixture(base.copy(
                input = "Quote the last reply verbatim, including both XML history tags.\nThen explain what the quotation demonstrates.",
                parts = listOf(quote), preview = quote, expected = quote,
            ))
        }
    }

    @Test fun dispatchedInlineBoundaryDeclarationsProtectNewRepliesOnDisk() = runBlocking {
        for (stop in listOf(true, false)) {
            val transport = if (stop) Transport.ORDINARY else Transport.EXPERIMENTAL_TOOL
            val base = buildReplyFixture(transport, stop = true).copy(stop = stop)
            val frame = PromptTemplates.narrativeModeHistoryReply("A new scene from the model.", base.mode)
            val opening = frame.substringBefore('\n')
            val closing = frame.substringAfterLast('\n')
            val directive = "Always begin the reply with the exact line `$opening` and end it with the exact line `$closing`."
            runReplyFixture(base.copy(
                card = base.card.copy(basicSetting = directive), parts = listOf(frame), preview = frame, expected = frame,
            ))
            verifyReply(transport, stop, literal = LiteralCase.WORLDBOOK_BOUNDARY_XML)
        }
    }

    @Test fun interruptedOptionsAndScopedHistoryDeclarationsUseTheirOwnReply() = runBlocking {
        val story = "The lamp lit up."
        val options = "\n\n---\n**【行动选项】**\n\n1. **[Open the door]()**\n   Wait for the latch to click.\n2. **[Stay here]()**"
        for (transport in listOf(Transport.ORDINARY, Transport.EXPERIMENTAL_TOOL)) {
            val base = buildReplyFixture(transport, stop = true)
            val wrapper = PromptTemplates.narrativeModeHistoryReply(story, base.mode)
            for (stop in listOf(true, false)) {
                val suffix = options + if (stop) "" else "\n---"
                runReplyFixture(base.copy(stop = stop, parts = listOf(wrapper + suffix), preview = story + suffix, expected = story + suffix))
            }
            val open = wrapper.substringBefore('\n')
            val close = wrapper.substringAfterLast('\n')
            val scope = "For this reply only.\nBegin the reply with the exact line `$open` and end it with the exact line `$close`."
            runReplyFixture(base.copy(
                historicalUser = scope, input = "Continue.", parts = listOf(wrapper), preview = story, expected = story,
            ))
            runReplyFixture(base.copy(
                stop = false, historicalUser = "Light the lamp.", input = scope, parts = listOf(wrapper), preview = wrapper, expected = wrapper,
            ))
        }
    }

    @Test fun exactCitationAndPlainCommentaryKeepOnlyTheRequestedFrameOnDisk() = runBlocking {
        for (stop in listOf(true, false)) {
            val transport = if (stop) Transport.ORDINARY else Transport.EXPERIMENTAL_TOOL
            val base = buildReplyFixture(transport, stop = true).copy(stop = stop, prior = "The lamp was lit.")
            val quote = PromptTemplates.narrativeModeHistoryReply(base.prior, base.mode)
            val prose = "The scene shows that they can still see."
            val raw = "$quote\n\n" + PromptTemplates.narrativeModeHistoryReply(prose, base.mode)
            val expected = "$quote\n\n$prose"
            runReplyFixture(base.copy(
                input = "Quote the previous answer with its XML history tags verbatim, then explain in plain prose.",
                parts = listOf(raw), preview = expected, expected = expected,
            ))
        }
    }

    @Test fun captionedXmlSampleKeepsItsTagsThroughMultipartStopAndDone() = runBlocking {
        val caption = "Example XML:"
        val explanation = "The element stores a story excerpt."
        for (stop in listOf(true, false)) {
            val base = buildReplyFixture(Transport.EXPERIMENTAL_TOOL, stop = true).copy(stop = stop)
            val example = PromptTemplates.narrativeModeHistoryReply("A fictional excerpt.", base.mode)
            val parts = listOf(caption, example, explanation)
            runReplyFixture(base.copy(
                input = "Show a sample XML element as plain text. Put the caption before the sample.",
                parts = parts,
                preview = caption,
                expected = parts.joinToString("\n"),
                reasoningWitness = "All three sample calls have been sent.",
            ))
        }
    }

    @Test fun exactPreviousXmlWrapperAndExplanationStayWithTheColdReply() = runBlocking {
        for (stop in listOf(true, false)) {
            val base = buildReplyFixture(Transport.ORDINARY, stop = true).copy(stop = stop, prior = "The window opened.")
            val projection = PromptTemplates.narrativeModeHistoryReply(base.prior, base.mode)
            val explanation = "This XML element is a literal copy of the preceding reply."
            val expected = "$projection\n\n$explanation"
            runReplyFixture(base.copy(
                input = "Please quote the previous answer along with its XML wrapper, then explain the example.",
                parts = listOf(expected), preview = expected, expected = expected,
            ))
        }
    }

    @Test fun explicitExactlyCitationAndPlainExplanationKeepTheRequestedXmlOnDisk() = runBlocking {
        val body = "门半开着。"
        val request = "Quote the previous assistant reply with its XML history tags exactly"
        for (stop in listOf(true, false)) {
            val transport = if (stop) Transport.ORDINARY else Transport.EXPERIMENTAL_TOOL
            val base = buildReplyFixture(transport, stop = true).copy(stop = stop, prior = body)
            val citation = PromptTemplates.narrativeModeHistoryReply(body, base.mode)
            runReplyFixture(base.copy(
                input = "$request.", parts = listOf(citation), preview = citation, expected = citation,
            ))
            val explanation = "The mode appears in an XML attribute."
            val raw = "$citation\n\n" + PromptTemplates.narrativeModeHistoryReply(explanation, base.mode)
            val expected = "$citation\n\n$explanation"
            runReplyFixture(base.copy(
                input = "$request, then explain in plain prose.", parts = listOf(raw), preview = expected, expected = expected,
            ))
        }
    }

    @Test fun citationPaddingKeepsTheSerializedRequestAndQuotedXmlOnDisk() = runBlocking {
        val body = "A hard break.  \r\n    The door opened."
        val english = "Quote the previous assistant reply with its XML history tags exactly."
        val chinese = "请原样引用上一条助手回复，连同外层的 XML 历史标签。"
        for (stop in listOf(true, false)) {
            val ordinary = buildReplyFixture(Transport.ORDINARY, stop = true).copy(stop = stop, prior = body)
            val citation = PromptTemplates.narrativeModeHistoryReply(body, ordinary.mode)
            runReplyFixture(ordinary.copy(
                input = "\r\n \t\r\n$english\r\n \t\r\n", parts = listOf(citation), preview = citation, expected = citation,
            ))
            runReplyFixture(ordinary.copy(
                input = "\n    $chinese\n", parts = listOf(citation), preview = body, expected = body,
            ))
            val parts = listOf(citation.substringBeforeLast('\n'), citation.substringAfterLast('\n'))
            runReplyFixture(ordinary.copy(
                transport = Transport.EXPERIMENTAL_TOOL, input = "\n$chinese\n\n", parts = parts,
                expected = citation, preview = parts.first(), reasoningWitness = "Both completed calls were sent.",
            ))
        }
    }

    @Test fun adjacentLogBlankLineAndRetainedLegacyQuotePreserveTheRightColdBytes() = runBlocking {
        val lead = "Below is a literal quotation copied from the history log.\n"
        val tail = "That was a verbatim quotation from the log."
        val legacy = "<聚焦模式=\"沉浸\">\nThe lamp was lit.\n</聚焦模式>"
        for (stop in listOf(true, false)) {
            val base = buildReplyFixture(Transport.EXPERIMENTAL_TOOL, stop = true).copy(stop = stop)
            val sample = PromptTemplates.narrativeModeHistoryReply("A literal example.", base.mode)
            val parts = listOf(lead, sample, tail)
            runReplyFixture(base.copy(
                input = "Continue.", parts = parts, preview = lead, expected = parts.joinToString("\n"),
                reasoningWitness = "All three complete calls have been sent.",
            ))

            val transport = if (stop) Transport.ORDINARY else Transport.EXPERIMENTAL_TOOL
            val mode = ChatNarrativeMode.ADVANCE
            val heading = PromptTemplates.narrativeModeSystemPrompt(mode).lineSequence().first()
            val prose = "The scene shows that they can still see."
            val raw = "$legacy\n\n$heading\n" + PromptTemplates.narrativeModeHistoryReply(prose, mode)
            val expected = "$legacy\n\n$prose"
            runReplyFixture(base.copy(
                transport = transport, mode = mode, priorMode = ChatNarrativeMode.FOCUS, prior = legacy,
                retainsPriorLegacyProjection = true, olderSameModeHistory = "A previous patrol passed the lamp.",
                sentModeBlock = heading,
                input = "Quote the previous answer with its XML history tags verbatim, then explain in plain prose.",
                parts = listOf(raw), preview = expected, expected = expected,
            ))
        }
    }

    @Test fun explainedXmlAndExplicitPreviousProjectionSurvivePreviewStopAndReload() = runBlocking {
        val prior = "The rain stopped."
        val focus = ChatNarrativeMode.FOCUS
        val sampleBody = "示例内容"
        val sample = PromptTemplates.narrativeModeHistoryReply(sampleBody, focus)
        val explanation = "The opening element declares the narrative mode in its attribute."
        val expectedExample = "$sample\n$explanation"
        val projection = PromptTemplates.narrativeModeHistoryReply(prior, focus)
        val commentary = "\n\nThe opening element carries the narrative mode."
        val citation = projection + commentary
        for (stop in listOf(true, false)) {
            for (transport in listOf(Transport.ORDINARY, Transport.EXPERIMENTAL_TOOL)) {
                val base = buildReplyFixture(transport, stop = true)
                    .copy(stop = stop, prior = prior)
                val tool = transport == Transport.EXPERIMENTAL_TOOL
                runReplyFixture(base.copy(
                    input = "Explain this kind of XML.",
                    parts = if (tool) listOf(sample, explanation) else listOf(expectedExample),
                    expected = expectedExample, preview = if (tool) sampleBody else expectedExample,
                    reasoningWitness = "Both sample and explanation were sent.".takeIf { tool },
                ))
                runReplyFixture(base.copy(
                    input = "Quote the previous assistant reply verbatim with its XML history tags, then explain it.",
                    parts = listOf(citation), expected = citation, preview = citation,
                ))
                runReplyFixture(base.copy(
                    input = "Continue.", parts = listOf(citation),
                    expected = prior + commentary, preview = prior + commentary,
                ))
            }
        }
    }

    @Test fun anEchoOnlyFirstSwitchHasNoStoredStoryAndFencedCodeKeepsItsNewlines() = runBlocking {
        for (stop in listOf(true, false)) {
            val firstAdvance = buildReplyFixture(Transport.ORDINARY, stop, literal = LiteralCase.FIRST_ADVANCE_SWITCH)
            val sentence = checkNotNull(firstAdvance.sentModeBlock).lineSequence().drop(1).first()
            runReplyFixture(firstAdvance.copy(
                input = "Begin the scene.", parts = listOf(sentence + if (stop) "\n" else ""),
                expected = "", preview = "", expectNoUsableReply = true,
            ))
            val body = "\n```kotlin\nval stored = 7  \n```\n"
            val afterFrame = "\n\n"
            val fenced = buildReplyFixture(Transport.EXPERIMENTAL_TOOL, stop = true)
            runReplyFixture(fenced.copy(
                stop = stop, parts = listOf(PromptTemplates.narrativeModeHistoryReply(body, fenced.mode) + afterFrame),
                expected = body + afterFrame, preview = body + afterFrame,
            ))
        }
    }

    @Test fun exactUnlabelledInstructionsAndEnvelopeWhitespaceUseTheActualColdPaths() = runBlocking {
        for (literal in listOf(LiteralCase.UNLABELLED_MODE_SWITCH, LiteralCase.FRESH_FRAME_WHITESPACE)) {
            verifyReply(Transport.ORDINARY, stop = true, literal = literal)
            verifyReply(Transport.EXPERIMENTAL_TOOL, stop = false, literal = literal)
        }
        verifyRepair(Transport.ORDINARY, stop = true, orphan = RepairOrphan.UNLABELLED_SYSTEM_INTRODUCTION)
        verifyRepair(Transport.EXPERIMENTAL_TOOL, stop = false, orphan = RepairOrphan.UNLABELLED_SYSTEM_INTRODUCTION)
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

    @Test fun actualRepairKeepsOnlyAuthoredBoundaryLinesInColdStorage() = runBlocking {
        verifyRepair(Transport.ORDINARY, stop = true, orphan = RepairOrphan.DISPATCHED_BOUNDARIES)
        verifyRepair(Transport.EXPERIMENTAL_TOOL, stop = false, orphan = RepairOrphan.DISPATCHED_BOUNDARIES)
        verifyRepair(Transport.EXPERIMENTAL_TOOL, stop = true, orphan = RepairOrphan.DISPATCHED_BOUNDARIES)
        verifyRepair(Transport.ORDINARY, stop = false, orphan = RepairOrphan.AUTHORED_BOUNDARY)
    }

    @Test fun actualRepairRemovesOnlyTheIssuedSystemPreambleInColdStorage() = runBlocking {
        verifyRepair(Transport.ORDINARY, stop = true, orphan = RepairOrphan.SYSTEM_PREAMBLE)
        verifyRepair(Transport.EXPERIMENTAL_TOOL, stop = false, orphan = RepairOrphan.SYSTEM_PREAMBLE)
        verifyRepair(Transport.EXPERIMENTAL_TOOL, stop = false, orphan = RepairOrphan.AUTHORED_SYSTEM_PREAMBLE)
    }

    @Test fun actualSinglePartRepairKeepsRevisedProseOnEachSideOfAnInteriorEcho() = runBlocking {
        verifyRepair(Transport.ORDINARY, stop = true, orphan = RepairOrphan.INTERIOR_SYSTEM_PREAMBLE)
        verifyRepair(Transport.EXPERIMENTAL_TOOL, stop = false, orphan = RepairOrphan.INTERIOR_SYSTEM_PREAMBLE)
    }

    @Test fun splitRepairPreambleLeavesBothValidatedProsePartsInColdStorage() = runBlocking {
        verifyRepair(Transport.EXPERIMENTAL_TOOL, stop = true, orphan = RepairOrphan.SPLIT_SYSTEM_PREAMBLE)
        verifyRepair(Transport.EXPERIMENTAL_TOOL, stop = false, orphan = RepairOrphan.SPLIT_SYSTEM_PREAMBLE)
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
        useGlobalPersona: Boolean = false,
    ) = runReplyFixture(buildReplyFixture(
        transport, stop, continuation, literal, stopAfterCompleteToolCalls, legacyFirst, toolIndexes, useGlobalPersona,
    ))

    private data class ReplyFixture(
        val transport: Transport,
        val stop: Boolean,
        val mode: ChatNarrativeMode,
        val narrativeModesDisabled: Boolean,
        val card: CharacterCard,
        val modelName: String,
        val parts: List<String>,
        val prior: String,
        val priorMode: ChatNarrativeMode,
        val olderSameModeHistory: String?,
        val sentModeBlock: String?,
        val sentRoutineHistoryHeading: Boolean?,
        val worldBookEntryText: String?,
        val priorLegacy: String?,
        val historicalUser: String?,
        val includePrior: Boolean,
        val rebuildInitial: Boolean,
        val customCore: String,
        val renderedTail: String,
        val supplementaryTemplate: String?,
        val renderedSupplementary: String?,
        val playerTemplate: String?,
        val useGlobalPersona: Boolean,
        val selectedFormatCard: FormatCard?,
        val input: String,
        val expected: String,
        val preview: String,
        val toolIndexes: List<Int>?,
        val reasoningWitness: String?,
        val finishReason: String?,
        val disconnectedText: String,
        val checksStrongDenial: Boolean,
        val expectNoUsableReply: Boolean = false,
        val retainsPriorLegacyProjection: Boolean = false,
    )

    private fun buildReplyFixture(
        transport: Transport,
        stop: Boolean,
        continuation: String? = null,
        literal: LiteralCase? = null,
        stopAfterCompleteToolCalls: Boolean = false,
        legacyFirst: Boolean? = null,
        toolIndexes: List<Int>? = null,
        useGlobalPersona: Boolean = false,
    ): ReplyFixture {
        val cardName = "Synthetic narrative wire"
        val mode = if (stop && legacyFirst == null && literal != LiteralCase.FIRST_ADVANCE_SWITCH) {
            ChatNarrativeMode.FOCUS
        } else ChatNarrativeMode.ADVANCE
        val narrativeModesDisabled = literal in setOf(
            LiteralCase.DISABLED_HISTORY_HEADING, LiteralCase.DISABLED_UNSENT_HEADING,
            LiteralCase.DISABLED_AUTHORED_HEADING, LiteralCase.DISABLED_LITERAL_XML,
            LiteralCase.DISABLED_MIDDLE_TOOL_HEADING, LiteralCase.WORLDBOOK_AUTHORED_HEADING,
            LiteralCase.WORLDBOOK_INCIDENTAL_HEADING,
            LiteralCase.CHARACTER_EXAMPLE_HEADING,
        )
        val otherMode = ChatNarrativeMode.entries.first { it != mode }
        val isModeSwitch = literal == LiteralCase.SENT_SWITCH_ECHO || literal == LiteralCase.SENT_COMPLETE_MODE_SWITCH ||
            literal == LiteralCase.SENT_SWITCH_SUFFIX || literal == LiteralCase.UNLABELLED_MODE_SWITCH ||
            literal == LiteralCase.FIRST_ADVANCE_SWITCH
        val priorMode = if (isModeSwitch) otherMode else mode
        val olderSameModeHistory = "Earlier story in the currently selected mode.".takeIf {
            literal == LiteralCase.SENT_SWITCH_SUFFIX
        }
        val prior = when (literal) {
            LiteralCase.ADJACENT_AUTHORED -> "旧文。"
            LiteralCase.PROJECTED_RAW_INNER -> PromptTemplates.narrativeModeHistoryReply("Earlier literal example.", otherMode)
            LiteralCase.PROJECTED_EXACT_BYTES, LiteralCase.PROJECTED_PREFIX_NEW_STORY,
            LiteralCase.PROJECTED_SUFFIX_HEADING, LiteralCase.REQUESTED_CITATION,
            LiteralCase.REQUESTED_CITATION_CHINESE, LiteralCase.REQUESTED_CITATION_TAGS_LAST -> "A hard break  \r\n    An indented line.  "
            else -> "Previous synthetic scene."
        }
        val rebuildInitial = literal == LiteralCase.UNSENT_GREETING_CARD
        val includePrior = literal != LiteralCase.CARD_ORPHAN_NO_HISTORY &&
            literal != LiteralCase.UNSENT_HISTORY_HEADING && literal != LiteralCase.DISABLED_UNSENT_HEADING &&
            literal != LiteralCase.FIRST_ADVANCE_SWITCH && !rebuildInitial
        val prose = when (literal) {
            LiteralCase.ADJACENT_AUTHORED -> prior
            LiteralCase.CHARACTER_POST_HISTORY -> "$cardName turned the page."
            else -> "She opens the door and walks into the courtyard."
        }
        val template = PromptTemplates.narrativeModeHistoryReply("[story]", mode).prependIndent("> ")
        val framedReply = PromptTemplates.narrativeModeHistoryReply(prose, mode)
        val projectedInner = PromptTemplates.narrativeModeHistoryReply(prose, otherMode)
        val opener = framedReply.substringBefore('\n')
        val closing = framedReply.substringAfterLast('\n')
        val lazyListIntro = "- Here is the example"
        val lazyListParts = listOf("$opener\n$lazyListIntro", closing, "  It is an example.", closing)
        val exactInlineCopy = "$opener\n例子$closing"
        val exactCopyWithInlineCode = "$opener\nThe closing-tag token is `$closing`.\n$closing"
        val sameLineCopyWithInlineCode = "${opener}Use `$closing` here$closing"
        val adjacentAuthored = "$opener\n须保留。\n$closing"
        val multilineCopy = "$opener\nKeep this exactly.\n$closing"
        val splitCaption = "The literal closing tag is:"
        val bodyWithClosingLiteral = "屏幕显示 `$closing`。新句。"
        val historicalTemplate = PromptTemplates.narrativeModeHistoryReply("[story]", mode)
        val modePrompt = PromptTemplates.narrativeModeSystemPrompt(mode)
        val modeHeading = modePrompt.lineSequence().first()
        val modeTail = PromptTemplates.narrativeModeTailSystemPrompt(mode, priorMode)
        val sentModeBlock = when (literal) {
            LiteralCase.SENT_CORE_ECHO, LiteralCase.NEGATIVE_MODE_HEADING, LiteralCase.DISPLAY_CAPTION_TITLE -> modePrompt
            LiteralCase.SENT_SWITCH_ECHO, LiteralCase.SENT_COMPLETE_MODE_SWITCH, LiteralCase.FULL_MODE_BLOCK_INTERIOR,
            LiteralCase.FULL_MODE_BLOCK_THEN_AUTHORED_TITLE, LiteralCase.SPLIT_MODE_BLOCK,
            LiteralCase.SENT_MODE_SUFFIX, LiteralCase.SENT_SWITCH_SUFFIX, LiteralCase.UNLABELLED_MODE_SWITCH,
            LiteralCase.FIRST_ADVANCE_SWITCH -> modeTail
            else -> null
        }?.let {
            if (literal in setOf(LiteralCase.SENT_COMPLETE_MODE_SWITCH, LiteralCase.FULL_MODE_BLOCK_INTERIOR,
                    LiteralCase.FULL_MODE_BLOCK_THEN_AUTHORED_TITLE, LiteralCase.SPLIT_MODE_BLOCK)) it
            else it.lineSequence().take(2).joinToString("\n")
        }
        val modeTailTitle = modeTail.lineSequence().first()
        val routineHistoryHeading = PromptTemplates.sectionHeading(PromptTemplates.SECTION_PREVIOUS_TURN)
        val worldBookEntryText = when (literal) {
            LiteralCase.WORLDBOOK_AUTHORED_HEADING -> "Please start each reply with this literal heading verbatim:\n$routineHistoryHeading"
            LiteralCase.WORLDBOOK_INCIDENTAL_HEADING -> "The archive labels its earliest shelf $routineHistoryHeading."
            LiteralCase.WORLDBOOK_EXACT_XML -> "Copy the following XML exactly:\n$framedReply"
            LiteralCase.WORLDBOOK_BOUNDARY_XML ->
                "Always begin the reply with the exact line `$opener` and end it with the exact line `$closing`."
            LiteralCase.WORLDBOOK_EXACT_CLOSE -> "Print the literal closing tag only on its own line:\n$closing"
            LiteralCase.WORLDBOOK_EXACT_OPEN -> "Please show the literal opening tag only on its own line, then explain it:\n$opener"
            else -> null
        }
        val sentRoutineHistoryHeading = when (literal) {
            LiteralCase.SENT_HISTORY_HEADING, LiteralCase.DISABLED_HISTORY_HEADING,
            LiteralCase.DISABLED_AUTHORED_HEADING, LiteralCase.DISABLED_LITERAL_XML,
            LiteralCase.DISABLED_MIDDLE_TOOL_HEADING, LiteralCase.WORLDBOOK_AUTHORED_HEADING,
            LiteralCase.WORLDBOOK_INCIDENTAL_HEADING, LiteralCase.CHARACTER_EXAMPLE_HEADING -> true
            LiteralCase.UNSENT_HISTORY_HEADING, LiteralCase.DISABLED_UNSENT_HEADING -> false
            else -> null
        }
        val newStorySuffix = "\n\nA kettle rang.  \r\n    A new note."
        val historyCitation = PromptTemplates.narrativeModeHistoryReply(prior, priorMode)
        val supplementaryTemplate = when (literal) {
            LiteralCase.SUPPLEMENTARY_TEMPLATE ->
                "Character is {{char}}.\n\nUse this XML template, replace [story] with new prose and keep both tags verbatim:\n" +
                    historicalTemplate
            LiteralCase.AUTHOR_SUPPLEMENTARY_TITLE ->
                "Begin each reply with the following exact title printed verbatim:\n$modeHeading"
            else -> null
        }
        val customTail = when (literal) {
            LiteralCase.CHARACTER_POST_HISTORY -> "Copy this text verbatim:\n" +
                PromptTemplates.narrativeModeHistoryReply("{{char}} turned the page.", mode)
            LiteralCase.CHARACTER_TAIL_OPEN -> "Please show the literal opening tag only on its own line, then explain it:\n$opener"
            LiteralCase.CHARACTER_TAIL_CLOSE -> "Please show the literal closing tag only on its own line:\n$closing"
            else -> ""
        }
        val playerTemplate = if (literal == LiteralCase.PLAYER_TEMPLATE) {
            "The player is {{user}}.\n\nUse this XML template, replace [story] with new prose and keep both tags verbatim:\n" +
                historicalTemplate
        } else null
        val customCore = "Required output template (keep both tags verbatim):\n$historicalTemplate"
            .takeIf { literal == LiteralCase.CHARACTER_CORE }.orEmpty()
        val inlineWorldBook = worldBookEntryText?.let {
            WorldBook.create("Synthetic setting").copy(entries = listOf(WorldBookEntry.create(listOf("sigil"), it)))
        }
        val card = CharacterCard.create(cardName).copy(
            systemPrompt = customCore, postHistoryInstructions = customTail, characterBook = inlineWorldBook,
            mesExample = "Please start each reply with this literal heading verbatim:\n$routineHistoryHeading"
                .takeIf { literal == LiteralCase.CHARACTER_EXAMPLE_HEADING }.orEmpty(),
        )
        val historicalUser = when {
            narrativeModesDisabled && includePrior -> "Begin the previous synthetic scene."
            literal == LiteralCase.HISTORICAL_TEMPLATE_PROHIBITED || literal == LiteralCase.OLD_TEMPLATE_NO_XML ->
                "Use exactly this XML shape, replace [story] with new prose and keep both tags:\n$historicalTemplate"
            literal == LiteralCase.CHARACTER_POST_HISTORY && !stop ->
                "The token `$closing` is printed in the manual."
            else -> null
        }
        val selectedFormatCard = when (literal) {
            LiteralCase.CARD_ORPHAN_NO_HISTORY, LiteralCase.UNSENT_GREETING_CARD -> {
                val sample = PromptTemplates.narrativeModeHistoryReply("wrong", mode)
                FormatCard.create("Synthetic prohibited history sample",
                    "The following is a counterexample:\n$sample\nDo not output these history tags in your reply.")
            }
            LiteralCase.DOCUMENTARY_FORMAT -> FormatCard.create(
                "Synthetic historical reference",
                "Reference only — the following tags record older history; never reproduce this sample in the answer:\n" +
                    PromptTemplates.narrativeModeHistoryReply("An older turn.", mode) + "\nWrite plain narration.",
            )
            LiteralCase.EXACT_FORMAT_BOUNDARIES -> FormatCard.create(
                "Synthetic exact format boundaries",
                "Always begin the reply with the exact line `$opener` and end it with the exact line `$closing`.",
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
        val possessiveLead = "'The visitors' log contains this line:\n$closing"
        val possessiveBody = if (stop) possessiveLead else "$possessiveLead\nThe passage continues here.'"
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
            literal == LiteralCase.COMMON_OUTPUT_TEMPLATE ->
                "Use the following XML template for each reply and keep both tags verbatim:\n$historicalTemplate"
            literal == LiteralCase.OLD_TEMPLATE_NO_XML -> "Continue in plain prose. This turn use no XML."
            literal == LiteralCase.NEGATIVE_MODE_HEADING -> if (mode == ChatNarrativeMode.FOCUS) {
                "Do not output the internal heading: $modeHeading."
            } else "不要在正文里复述$modeHeading。"
            literal == LiteralCase.REQUESTED_CITATION ->
                "Quote the previous answer with its XML history tags verbatim, then explain what purpose that wrapper serves."
            literal == LiteralCase.REQUESTED_CITATION_CHINESE -> "请原样引用上一条助手回复，连同外层的 XML 历史标签。"
            literal == LiteralCase.REQUESTED_CITATION_TAGS_LAST -> "Quote the last reply verbatim, including both XML history tags."
            literal == LiteralCase.DISABLED_AUTHORED_HEADING -> "Print this title verbatim: $routineHistoryHeading."
            literal == LiteralCase.FULL_MODE_BLOCK_THEN_AUTHORED_TITLE -> "Print this title verbatim: $modeTailTitle."
            worldBookEntryText != null -> "Examine the sigil."
            literal == LiteralCase.BRACE_SLOT_TEMPLATE ->
                "Use exactly this XML template, replace {story} with new prose and keep both tags verbatim:\n" +
                    PromptTemplates.narrativeModeHistoryReply("{story}", mode)
            authorsTemplate -> "Use exactly this XML shape, replace [story] with new prose and keep both tags:\n$template"
            else -> "Continue the scene."
        }
        val legacyOpen = "<聚焦模式=\"沉浸\">"
        val legacyClose = "</聚焦模式>"
        val priorLegacy = "$legacyOpen\nPrevious legacy scene.\n$legacyClose".takeIf { legacyFirst != null }
        val legacyScene = "A legacy scene follows."
        val legacyReply = "$legacyOpen\n$legacyScene\n$legacyClose"
        val newBodyWithBreaks = "\n\r\n$prose  \r\n    She waited.  \r\n\n"
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
            LiteralCase.STRONG_SUFFIX_DENIAL, LiteralCase.DOCUMENTARY_FORMAT, LiteralCase.BRACE_SLOT_TEMPLATE,
            LiteralCase.SUPPLEMENTARY_TEMPLATE, LiteralCase.UNSENT_GREETING_CARD, LiteralCase.PLAYER_TEMPLATE,
            LiteralCase.COMMON_OUTPUT_TEMPLATE, LiteralCase.OLD_TEMPLATE_NO_XML -> framedReply
            LiteralCase.AUTHOR_SUPPLEMENTARY_TITLE -> "$modeHeading\n$prose"
            LiteralCase.PROJECTED_RAW_INNER -> PromptTemplates.narrativeModeHistoryReply(projectedInner, mode)
            LiteralCase.PROJECTED_EXACT_BYTES -> PromptTemplates.narrativeModeHistoryReply(prior, mode)
            LiteralCase.PROJECTED_PREFIX_NEW_STORY -> PromptTemplates.narrativeModeHistoryReply(prior, mode) + newStorySuffix
            LiteralCase.SENT_CORE_ECHO, LiteralCase.SENT_SWITCH_ECHO -> "$sentModeBlock\n$prose"
            LiteralCase.SENT_COMPLETE_MODE_SWITCH -> "$sentModeBlock\n\n$prose"
            LiteralCase.SENT_MODE_SUFFIX, LiteralCase.SENT_SWITCH_SUFFIX -> "$framedReply\n$sentModeBlock"
            LiteralCase.SENT_HISTORY_HEADING, LiteralCase.UNSENT_HISTORY_HEADING -> "$routineHistoryHeading\n$prose"
            LiteralCase.REQUESTED_CITATION -> "$historyCitation\n\nThis XML element stores mode information for the previous turn."
            LiteralCase.REQUESTED_CITATION_CHINESE -> "$historyCitation\n\n外层元素用于记录上一轮模式。"
            LiteralCase.REQUESTED_CITATION_TAGS_LAST -> "$historyCitation\n\nThe outer tags identify the original mode."
            LiteralCase.DISABLED_HISTORY_HEADING, LiteralCase.DISABLED_UNSENT_HEADING,
            LiteralCase.DISABLED_AUTHORED_HEADING, LiteralCase.DISABLED_MIDDLE_TOOL_HEADING,
            LiteralCase.WORLDBOOK_AUTHORED_HEADING, LiteralCase.WORLDBOOK_INCIDENTAL_HEADING -> "$routineHistoryHeading\n$prose"
            LiteralCase.DISABLED_LITERAL_XML -> framedReply
            LiteralCase.FULL_MODE_BLOCK_INTERIOR -> "$prose\n$sentModeBlock\nShe took a lantern."
            LiteralCase.FULL_MODE_BLOCK_THEN_AUTHORED_TITLE -> "$sentModeBlock\n\n$modeTailTitle\n$prose"
            LiteralCase.SPLIT_MODE_BLOCK -> "$prose\n$sentModeBlock"
            LiteralCase.WORLDBOOK_EXACT_XML, LiteralCase.WORLDBOOK_BOUNDARY_XML -> framedReply
            LiteralCase.WORLDBOOK_EXACT_CLOSE -> "$prose\n$closing"
            LiteralCase.WORLDBOOK_EXACT_OPEN -> "$opener\n$prose"
            LiteralCase.CHARACTER_EXAMPLE_HEADING -> "$routineHistoryHeading\n$prose"
            LiteralCase.DISPLAY_CAPTION_TITLE -> "The screen displays only this title:\n$modeHeading"
            LiteralCase.UNLABELLED_MODE_SWITCH, LiteralCase.FIRST_ADVANCE_SWITCH -> modeTail.lineSequence().drop(1).first() + "\n$prose"
            LiteralCase.FRESH_FRAME_WHITESPACE -> PromptTemplates.narrativeModeHistoryReply(newBodyWithBreaks, mode)
            LiteralCase.PROJECTED_SUFFIX_HEADING -> historyCitation + newStorySuffix + "\n" + modeTail.lineSequence().first()
            LiteralCase.NEGATIVE_MODE_HEADING -> "$modeHeading\n$prose"
            LiteralCase.EXACT_FORMAT_BOUNDARIES -> framedReply
            LiteralCase.AMBIGUOUS_TOOL_LIST -> lazyListParts.joinToString("\n")
            LiteralCase.PARTIAL_OUTER_CLOSE -> "$opener\n$prose\n</历史回"
            LiteralCase.PARAGRAPH_QUOTE -> PromptTemplates.narrativeModeHistoryReply(paragraphQuote(closing), mode)
            LiteralCase.GERMAN_QUOTE -> PromptTemplates.narrativeModeHistoryReply(germanQuote(closing), mode)
            LiteralCase.QUOTED_POSSESSIVE -> if (stop) "$opener\n$possessiveBody"
                else PromptTemplates.narrativeModeHistoryReply(possessiveBody, mode)
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
            literal == LiteralCase.SPLIT_MODE_BLOCK -> {
                val lines = checkNotNull(sentModeBlock).lines()
                listOf(
                    "$prose\n" + lines.take(2).joinToString("\n"),
                    lines.drop(2).joinToString("\n") + "\n$secondScene",
                )
            }
            literal == LiteralCase.DISABLED_MIDDLE_TOOL_HEADING -> listOf(prose, routineHistoryHeading, secondScene)
            toolIndexes != null -> toolIndexes.map { if (it == 0) prose else disconnectedText }
            literal == LiteralCase.TOOL_SPLIT_CAPTION -> listOf(splitCaption, closing)
            literal == LiteralCase.BOUNDED_LOG_QUOTATION -> listOf(quoteLead, framedReply, quoteTail)
            literal == LiteralCase.AMBIGUOUS_TOOL_LIST -> lazyListParts
            literal == LiteralCase.PROJECTED_PREFIX_NEW_STORY && transport == Transport.EXPERIMENTAL_TOOL ->
                listOf(PromptTemplates.narrativeModeHistoryReply(prior, mode), newStorySuffix.removePrefix("\n"))
            stopAfterCompleteToolCalls -> listOf("$opener\n$prose", "$secondScene\n$closing")
            continuation != null -> listOf(
                reply.substringBeforeLast('\n'), "$secondScene\n${reply.substringAfterLast('\n')}", continuation,
            )
            else -> listOf(reply)
        }
        val expected = when {
            literal == LiteralCase.UNLABELLED_MODE_SWITCH || literal == LiteralCase.FIRST_ADVANCE_SWITCH -> prose
            literal == LiteralCase.FRESH_FRAME_WHITESPACE -> newBodyWithBreaks
            literal == LiteralCase.SPLIT_MODE_BLOCK -> "$prose\n$secondScene"
            literal == LiteralCase.DISABLED_MIDDLE_TOOL_HEADING -> "$prose\n$secondScene"
            literal == LiteralCase.FULL_MODE_BLOCK_INTERIOR -> "$prose\nShe took a lantern."
            literal == LiteralCase.FULL_MODE_BLOCK_THEN_AUTHORED_TITLE -> "$modeTailTitle\n$prose"
            stopAfterCompleteToolCalls -> "$prose\n$secondScene"
            continuation != null -> listOf(prose, secondScene, continuation).joinToString("\n")
            literal == LiteralCase.LAZY_LIST_CLOSE -> listBody
            literal == LiteralCase.CAPTIONED_CLOSE || literal == LiteralCase.CHARACTER_TAIL_CLOSE -> captionedExample
            literal == LiteralCase.LAZY_QUOTE_CLOSE -> lazyQuoteExample
            literal == LiteralCase.ORDINARY_CAPTIONED_CLOSE -> ordinaryCaptionedBody
            literal == LiteralCase.PROJECTED_RAW_INNER -> projectedInner
            literal == LiteralCase.PROJECTED_EXACT_BYTES -> prior
            literal == LiteralCase.PROJECTED_PREFIX_NEW_STORY -> prior + newStorySuffix
            literal == LiteralCase.PROJECTED_SUFFIX_HEADING -> prior + newStorySuffix
            literal == LiteralCase.SENT_CORE_ECHO || literal == LiteralCase.SENT_SWITCH_ECHO ||
                literal == LiteralCase.SENT_COMPLETE_MODE_SWITCH || literal == LiteralCase.SENT_HISTORY_HEADING ||
                literal == LiteralCase.SENT_MODE_SUFFIX || literal == LiteralCase.SENT_SWITCH_SUFFIX ||
                literal == LiteralCase.DISABLED_HISTORY_HEADING || literal == LiteralCase.WORLDBOOK_INCIDENTAL_HEADING ||
                literal == LiteralCase.NEGATIVE_MODE_HEADING -> prose
            literal == LiteralCase.QUOTED_POSSESSIVE -> possessiveBody
            literal == LiteralCase.PARAGRAPH_QUOTE -> paragraphQuote(closing)
            literal == LiteralCase.GERMAN_QUOTE -> germanQuote(closing)
            literal == LiteralCase.ADJACENT_AUTHORED -> "$prose\n$adjacentAuthored"
            literal == LiteralCase.CARD_ORPHAN_NO_HISTORY -> prose
            literal == LiteralCase.PADDED_OPENER || literal == LiteralCase.HISTORICAL_TEMPLATE_PROHIBITED ||
                literal == LiteralCase.STRONG_SUFFIX_DENIAL || literal == LiteralCase.DOCUMENTARY_FORMAT ||
                literal == LiteralCase.PARTIAL_OUTER_CLOSE || literal == LiteralCase.OLD_TEMPLATE_NO_XML -> prose
            literal == LiteralCase.AUTHORED_CLOSE_EXTRA -> bodyWithClosingLiteral
            legacyFirst == true -> "$legacyScene\n$prose"
            legacyFirst == false -> "$prose\n$legacyScene"
            authorsTemplate || literal != null -> reply
            else -> prose
        }
        val modelName = "Synthetic narrative wire ${transport.name}"
        val reasoningWitness = "Provider has sent every synthetic call.".takeIf {
            stopAfterCompleteToolCalls || literal == LiteralCase.TOOL_SPLIT_CAPTION ||
                literal == LiteralCase.BOUNDED_LOG_QUOTATION || literal == LiteralCase.AMBIGUOUS_TOOL_LIST ||
                literal == LiteralCase.SPLIT_MODE_BLOCK ||
                literal == LiteralCase.PROJECTED_PREFIX_NEW_STORY && transport == Transport.EXPERIMENTAL_TOOL
        }
        // Experimental live preview initially carries the first tool call only.
        val preview = when {
            literal == LiteralCase.SPLIT_MODE_BLOCK -> prose
            literal == LiteralCase.DISABLED_MIDDLE_TOOL_HEADING -> prose
            toolIndexes != null -> prose
            literal == LiteralCase.TOOL_SPLIT_CAPTION -> splitCaption
            literal == LiteralCase.BOUNDED_LOG_QUOTATION -> quoteLead
            literal == LiteralCase.AMBIGUOUS_TOOL_LIST -> lazyListIntro
            literal == LiteralCase.PROJECTED_PREFIX_NEW_STORY && transport == Transport.EXPERIMENTAL_TOOL -> prior
            continuation != null || stopAfterCompleteToolCalls -> prose
            else -> expected
        }
        return ReplyFixture(
            transport = transport, stop = stop, mode = mode, card = card, modelName = modelName, parts = parts,
            narrativeModesDisabled = narrativeModesDisabled,
            prior = prior, priorMode = priorMode, sentModeBlock = sentModeBlock,
            olderSameModeHistory = olderSameModeHistory,
            sentRoutineHistoryHeading = sentRoutineHistoryHeading, worldBookEntryText = worldBookEntryText, priorLegacy = priorLegacy,
            historicalUser = historicalUser, includePrior = includePrior,
            rebuildInitial = rebuildInitial, customCore = customCore, renderedTail = customTail.replace("{{char}}", cardName),
            supplementaryTemplate = supplementaryTemplate,
            renderedSupplementary = supplementaryTemplate?.replace("{{char}}", cardName),
            playerTemplate = playerTemplate, useGlobalPersona = useGlobalPersona,
            selectedFormatCard = selectedFormatCard, input = input, expected = expected, preview = preview,
            toolIndexes = toolIndexes, reasoningWitness = reasoningWitness,
            finishReason = "length".takeIf { literal == LiteralCase.PADDED_OPENER }, disconnectedText = disconnectedText,
            checksStrongDenial = literal == LiteralCase.STRONG_SUFFIX_DENIAL,
        )
    }

    private suspend fun runReplyFixture(fixture: ReplyFixture) {
        val app = ChatBarApp.instance
        val settings = app.settingsRepository.getAppSettings()
        val originalPlayer = if (fixture.playerTemplate != null) app.settingsRepository.getPlayerSetting() else null
        val store = ViewModelStore()
        HeldNarrativeReplyServer(
            fixture.parts, tool = fixture.transport == Transport.EXPERIMENTAL_TOOL,
            reasoningWitness = fixture.reasoningWitness, toolIndexes = fixture.toolIndexes,
            finishReason = fixture.finishReason,
        ).use { server ->
            val createdModel = ModelConfig.create(fixture.modelName, server.baseUrl, "fixture-key", "fixture-model")
            val model = if (fixture.rebuildInitial) createdModel.copy(formatPromptPosition = FormatPromptPosition.END) else createdModel
            val session = ChatSession.create(fixture.card.id, fixture.modelName).copy(
                modelId = model.id, narrativeMode = fixture.mode, longTermMemoryEnabled = false,
                automaticImageGenerationEnabled = false, audiobookModeEnabled = false,
                formatCardId = fixture.selectedFormatCard?.id, supplementarySetting = fixture.supplementaryTemplate,
                playerName = "Fixture keeper".takeIf { fixture.playerTemplate != null },
                playerSetting = fixture.playerTemplate.takeUnless { fixture.useGlobalPersona },
            )
            val greeting = ChatMessage.create(session.id, MessageRole.ASSISTANT, fixture.prior).takeIf { fixture.rebuildInitial }
            var editor: ChatViewModel? = null
            try {
                app.modelRepository.saveModel(model)
                app.characterRepository.save(fixture.card)
                fixture.selectedFormatCard?.let { app.formatCardRepository.save(it) }
                app.chatRepository.createSession(session)
                greeting?.let { app.chatRepository.addMessage(it) }
                fixture.historicalUser?.let { app.chatRepository.addMessage(ChatMessage.create(session.id, MessageRole.USER, it)) }
                fixture.priorLegacy?.let {
                    app.chatRepository.addMessage(ChatMessage.create(session.id, MessageRole.ASSISTANT, it).copy(narrativeMode = ChatNarrativeMode.FOCUS))
                }
                fixture.olderSameModeHistory?.let {
                    app.chatRepository.addMessage(
                        ChatMessage.create(session.id, MessageRole.ASSISTANT, it).copy(
                            narrativeMode = fixture.mode, createdAt = System.currentTimeMillis() - 2_000,
                        ),
                    )
                }
                if (fixture.includePrior) {
                    app.chatRepository.addMessage(
                        ChatMessage.create(session.id, MessageRole.ASSISTANT, fixture.prior).copy(narrativeMode = fixture.priorMode),
                    )
                }
                if (originalPlayer != null) {
                    val persona = if (fixture.useGlobalPersona) checkNotNull(fixture.playerTemplate) else "Unused global persona."
                    app.settingsRepository.savePlayerSetting(originalPlayer.copy(globalPersona = persona))
                }
                app.settingsRepository.saveAppSettings(settings.copy(
                    allowCleartextModelApi = true, defaultEmbeddingId = null, defaultFormatCardId = null,
                    automaticFormatCheckEnabled = false, momentsEnabled = false,
                    disableNarrativeModes = fixture.narrativeModesDisabled,
                    experimentalApiModeEnabled = fixture.transport.experimental,
                ))
                val vm = rule.runOnIdle {
                    ViewModelProvider(store, ChatViewModelFactory(session.id))[ChatViewModel::class.java]
                }.also { editor = it }
                val priorBodies = listOfNotNull(
                    fixture.priorLegacy, fixture.historicalUser, fixture.olderSameModeHistory, fixture.prior.takeIf { fixture.includePrior },
                )
                rule.waitUntil(20_000) {
                    vm.isModelUsable.value && priorBodies.all { body -> vm.messages.value.any { it.displayContent == body } } &&
                        (greeting == null || vm.messages.value.any { it.id == greeting.id })
                }
                rule.runOnIdle {
                    if (greeting != null) vm.regenerateResponse(greeting.id)
                    else assertTrue(vm.sendMessage(fixture.input))
                }
                val request = withTimeout(20_000) { server.requestBody.await() }
                assertReplyRequest(request, fixture)
                if (fixture.toolIndexes == null || 0 in fixture.toolIndexes) {
                    rule.waitUntil(20_000) {
                        val draft = vm.streamingMessage.value
                        draft != null && draft.displayContent == fixture.preview &&
                            (fixture.reasoningWitness == null || draft.reasoningContent == fixture.reasoningWitness)
                    }
                }
                assertFalse("The provider's terminal event must remain held during preview", server.terminalSent.get())
                assertTrue(vm.isResponding.value)

                if (fixture.stop) rule.runOnIdle { vm.cancelResponseGeneration() } else server.release()
                rule.waitUntil(20_000) { !vm.isResponding.value }
                assertEquals(!fixture.stop, server.terminalSent.get())
                val saved = ChatRepository(JsonFileStorage(app)).getMessages(session.id)
                assertReplyStorage(saved, greeting?.id, priorBodies, fixture)
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
                        originalPlayer?.let { app.settingsRepository.savePlayerSetting(it) }
                        fixture.selectedFormatCard?.let { app.formatCardRepository.delete(it.id) }
                        app.characterRepository.delete(fixture.card.id)
                        app.modelRepository.deleteModel(model.id)
                    }
                }
            }
        }
    }

    private fun assertReplyRequest(request: String, fixture: ReplyFixture) {
        val messages = sentMessages(request, fixture.transport)
        val history = if (fixture.narrativeModesDisabled || fixture.retainsPriorLegacyProjection) fixture.prior
        else PromptTemplates.narrativeModeHistoryReply(fixture.prior, fixture.priorMode)
        assertEquals(fixture.includePrior, messages.any {
            it.hasMessage("assistant", history)
        })
        if (fixture.narrativeModesDisabled) {
            val stamped = PromptTemplates.narrativeModeHistoryReply(fixture.prior, fixture.priorMode)
            assertFalse(messages.any { it.hasMessage("assistant", stamped) })
        }
        fixture.sentModeBlock?.let { block ->
            assertTrue(messages.any { (it.jsonObject["content"] as? JsonPrimitive)?.content?.contains(block) == true })
        }
        fixture.olderSameModeHistory?.let { body ->
            val projection = PromptTemplates.narrativeModeHistoryReply(body, fixture.mode)
            assertTrue(messages.any { it.hasMessage("assistant", projection) })
        }
        fixture.sentRoutineHistoryHeading?.let { isSent ->
            val heading = PromptTemplates.sectionHeading(PromptTemplates.SECTION_PREVIOUS_TURN)
            val role = if (fixture.transport.experimental) "system" else "assistant"
            assertEquals(isSent, messages.any { it.hasMessage(role, heading) })
        }
        fixture.worldBookEntryText?.let { declaration ->
            assertTrue(messages.any { (it.jsonObject["content"] as? JsonPrimitive)?.content?.contains(declaration) == true })
        }
        if (fixture.card.mesExample.isNotBlank()) {
            assertTrue(messages.any { (it.jsonObject["content"] as? JsonPrimitive)?.content?.contains(fixture.card.mesExample) == true })
        }
        if (fixture.card.basicSetting.isNotBlank()) {
            assertTrue(messages.any { (it.jsonObject["content"] as? JsonPrimitive)?.content?.contains(fixture.card.basicSetting) == true })
        }
        fixture.priorLegacy?.let { body -> assertTrue(messages.any { it.hasMessage("assistant", body) }) }
        fixture.historicalUser?.let { body -> assertTrue(messages.any { it.hasMessage("user", body) }) }
        if (fixture.customCore.isNotEmpty()) {
            val first = Json.parseToJsonElement(request).jsonObject.getValue("messages").jsonArray.first().jsonObject
            assertEquals("system", first["role"]?.jsonPrimitive?.content)
            assertTrue(fixture.customCore in first.getValue("content").jsonPrimitive.content)
        }
        if (fixture.renderedTail.isNotEmpty()) {
            val tailRole = if (fixture.transport.experimental) "system" else "assistant"
            assertTrue(messages.any { entry ->
                entry.jsonObject["role"]?.jsonPrimitive?.content == tailRole &&
                    (entry.jsonObject["content"] as? JsonPrimitive)?.content?.contains(fixture.renderedTail) == true
            })
        }
        fixture.selectedFormatCard?.let { format ->
            assertEquals(!fixture.rebuildInitial, messages.any { entry ->
                (entry.jsonObject["content"] as? JsonPrimitive)?.content?.contains(format.content) == true
            })
            for (configuredTool in format.userTools) assertTrue(messages.any { entry ->
                (entry.jsonObject["content"] as? JsonPrimitive)?.content?.contains(configuredTool.text) == true
            })
        }
        assertEquals(!fixture.rebuildInitial, messages.any { it.hasMessage("user", fixture.input) })
        fixture.renderedSupplementary?.let { marker ->
            val setting = messages.indexOfFirst { it.jsonObject.getValue("content").jsonPrimitive.content.contains(marker) }
            val sentUser = messages.indexOfFirst { it.hasMessage("user", fixture.input) }
            assertTrue("The evaluated supplementary setting must precede the sent user", setting >= 0 && sentUser > setting)
        }
        fixture.playerTemplate?.let { raw ->
            val rendered = raw.replace("{{user}}", "Fixture keeper")
            val setting = messages.indexOfFirst { it.jsonObject.getValue("content").jsonPrimitive.content.contains(rendered) }
            val sentUser = messages.indexOfFirst { it.hasMessage("user", fixture.input) }
            assertTrue("The evaluated player setting must precede the sent user", setting >= 0 && sentUser > setting)
            assertFalse(messages.any { "Unused global persona." in it.jsonObject.getValue("content").jsonPrimitive.content })
        }
        if (fixture.checksStrongDenial) {
            val user = messages.indexOfFirst { it.hasMessage("user", fixture.input) }
            val role = if (fixture.transport.experimental) "system" else "assistant"
            val strongText = checkNotNull(fixture.selectedFormatCard).userTools.single().text
            val suffix = messages.indexOfFirst { it.hasMessage(role, strongText) }
            assertTrue("The sent strong denial follows the actual user", user >= 0 && suffix > user)
        }
    }

    private fun assertReplyStorage(
        saved: List<ChatMessage>,
        greetingId: String?,
        priorBodies: List<String>,
        fixture: ReplyFixture,
    ) {
        if (fixture.narrativeModesDisabled) assertEquals(null, saved.last().narrativeMode)
        if (greetingId != null) {
            assertEquals(greetingId, saved.single().id)
            assertEquals(fixture.expected, saved.single().displayContent)
            return
        }
        assertEquals(priorBodies.toSet(), saved.take(priorBodies.size).map(ChatMessage::displayContent).toSet())
        val newMessages = saved.drop(priorBodies.size).map(ChatMessage::displayContent)
        if (fixture.expectNoUsableReply) {
            val expectedBodies = if (fixture.stop) listOf(fixture.input)
            else listOf(fixture.input, "错误: 模型未返回可用正文")
            assertEquals(expectedBodies, newMessages)
            return
        }
        if (fixture.toolIndexes == null) assertEquals(listOf(fixture.input, fixture.expected), newMessages) else {
            assertEquals(fixture.input, newMessages.first())
            assertTrue(newMessages.last().startsWith("错误:"))
            assertFalse(newMessages.last().contains(fixture.disconnectedText))
        }
        val savedPriorRoles = saved.take(priorBodies.size).map(ChatMessage::role)
        val historicalUserCount = if (fixture.historicalUser != null) 1 else 0
        assertEquals(historicalUserCount, savedPriorRoles.count { it == MessageRole.USER })
        assertEquals(priorBodies.size - historicalUserCount, savedPriorRoles.count { it == MessageRole.ASSISTANT })
        assertEquals(listOf(MessageRole.USER, MessageRole.ASSISTANT), saved.drop(priorBodies.size).map(ChatMessage::role))
    }

    private suspend fun verifyAutomaticRepairDenial(stop: Boolean, unusedExample: Boolean = false) {
        val app = ChatBarApp.instance
        val settings = app.settingsRepository.getAppSettings()
        val store = ViewModelStore()
        val mode = if (stop) ChatNarrativeMode.FOCUS else ChatNarrativeMode.ADVANCE
        val original = "Mira enters."
        val repaired = "Mira: Mira enters."
        val raw = PromptTemplates.narrativeModeHistoryReply(repaired, mode)
        val close = raw.substringAfterLast('\n')
        val sample = PromptTemplates.narrativeModeHistoryReply("An earlier turn.", mode)
        val userText = if (unusedExample) "A player once used this unrelated example.\n$sample"
            else "Do not output the following history tags:\n$close"
        val format = FormatCard.create("Automatic repair rule", "    The following is a counterexample:\n$sample  \n")
        val card = CharacterCard.create("Synthetic automatic format repair")
        HeldNarrativeReplyServer(listOf(original), tool = false, reasoningWitness = null).use { generation ->
            HeldNarrativeReplyServer(listOf(raw), tool = false, reasoningWitness = null).use { repair ->
                val model = ModelConfig.create("Synthetic generation", generation.baseUrl, "fixture-key", "fixture-model")
                val repairModel = ModelConfig.create("Synthetic repair", repair.baseUrl, "fixture-key", "fixture-model")
                val session = ChatSession.create(card.id, model.displayName).copy(
                    modelId = model.id, formatCardId = format.id, narrativeMode = mode, longTermMemoryEnabled = false,
                    automaticImageGenerationEnabled = false, audiobookModeEnabled = false,
                )
                var editor: ChatViewModel? = null
                try {
                    app.modelRepository.saveModel(model)
                    app.modelRepository.saveModel(repairModel)
                    app.characterRepository.save(card)
                    app.formatCardRepository.save(format)
                    app.chatRepository.createSession(session)
                    app.settingsRepository.saveAppSettings(settings.copy(
                        allowCleartextModelApi = true, formatRepairModelId = repairModel.id,
                        automaticFormatCheckEnabled = true, experimentalApiModeEnabled = false,
                        defaultEmbeddingId = null, momentsEnabled = false, disableNarrativeModes = false,
                    ))
                    val vm = rule.runOnIdle {
                        ViewModelProvider(store, ChatViewModelFactory(session.id))[ChatViewModel::class.java]
                    }.also { editor = it }
                    rule.waitUntil(20_000) { vm.isModelUsable.value }
                    rule.runOnIdle { assertTrue(vm.sendMessage(userText)) }
                    val generationRequest = withTimeout(20_000) { generation.requestBody.await() }
                    assertTrue(sentMessages(generationRequest, Transport.ORDINARY).any { it.hasMessage("user", userText) })
                    generation.release()
                    val repairRequest = withTimeout(20_000) { repair.requestBody.await() }
                    assertTrue("Actual repair receives its card and the generated draft",
                        format.content.trim().lineSequence().first() in repairRequest && original in repairRequest)
                    assertFalse("Current user text is preservation context, not a second repair turn", userText.lineSequence().first() in repairRequest)
                    rule.waitUntil(20_000) { vm.messageFormatRepairState.value?.previewContent == repaired }
                    assertFalse(repair.terminalSent.get())
                    if (stop) rule.runOnIdle { vm.cancelResponseGeneration() } else repair.release()
                    rule.waitUntil(20_000) { !vm.isResponding.value }
                    assertEquals(!stop, repair.terminalSent.get())
                    val saved = ChatRepository(JsonFileStorage(app)).getMessages(session.id)
                    assertEquals(listOf(userText, repaired), saved.map(ChatMessage::displayContent))
                    val expectedNotice = if (stop) MessageFormatRepairNoticeKind.STOPPED else MessageFormatRepairNoticeKind.APPLIED
                    assertEquals(expectedNotice, saved.last().formatRepairNotice?.kind)
                } finally {
                    try {
                        editor?.let { vm ->
                            rule.runOnIdle { vm.cancelResponseGeneration() }
                            rule.waitUntil(20_000) { !vm.isResponding.value }
                        }
                    } finally {
                        generation.release()
                        repair.release()
                        rule.runOnIdle { store.clear() }
                        try {
                            app.deletionCoordinator.deleteSession(session.id)
                        } finally {
                            app.settingsRepository.saveAppSettings(settings)
                            app.formatCardRepository.delete(format.id)
                            app.characterRepository.delete(card.id)
                            app.modelRepository.deleteModel(model.id)
                            app.modelRepository.deleteModel(repairModel.id)
                        }
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
        val possessiveLead = "'The visitors' log contains this line:\n$close"
        val messageBoundary = "{待修复消息}"
        val bubbleBoundary = "{分段气泡格式}"
        val cardBoundary = "{格式卡要求}（禁止覆盖应用格式卡中的占位文本，如对选项的描述）"
        val repairSystem = PromptTemplates.MESSAGE_FORMAT_REPAIR_SYSTEM_PROMPT.trim()
        val repairIntroduction = repairSystem.lineSequence().first()
        val repairSentence = repairIntroduction.substringBefore('。') + "。"
        val repairSecondProse = "She found the key again."
        val original = when (orphan) {
            RepairOrphan.SPLIT_CAPTION -> "End tag: &lt;/历史回复&gt;."
            RepairOrphan.VISIBLE_INPUT_LITERAL -> "结束标签示例：\n$close\n她关上门。"
            RepairOrphan.VISIBLE_INPUT_BLOCKQUOTE -> "The closing-tag example is $close."
            RepairOrphan.AUTHORED_CLOSE_EXTRA -> "屏幕显示 `$close`。旧句。"
            RepairOrphan.AUTHORED_BOUNDARY -> "$messageBoundary\n旧。"
            RepairOrphan.AUTHORED_SYSTEM_PREAMBLE -> "$repairSentence\n旧。"
            RepairOrphan.WHOLE_REPAIR_SYSTEM_SUFFIX -> "The door was closed"
            RepairOrphan.WHOLE_REPAIR_SYSTEM_INTERIOR -> "The door was open. The lamp was out."
            RepairOrphan.REPAIR_SYSTEM_ECHO_ONLY -> "The door was closed"
            RepairOrphan.PARTIAL_REPAIR_TAIL -> "门没关严。"
            else -> "旧。"
        }
        val first = when (orphan) {
            RepairOrphan.SPLIT_CAPTION -> "The literal closing tag is:"
            RepairOrphan.VISIBLE_INPUT_LITERAL -> "结束标签示例：\n`$close`\n她关上门。"
            RepairOrphan.VISIBLE_INPUT_BLOCKQUOTE -> "Closing-tag example:\n\n> $close"
            RepairOrphan.AUTHORED_CLOSE_EXTRA -> "屏幕显示 `$close`。新句。"
            RepairOrphan.QUOTED_POSSESSIVE -> if (stop) possessiveLead else "$possessiveLead\nThe passage continues here.'"
            RepairOrphan.PARAGRAPH_QUOTE -> paragraphQuote(close)
            RepairOrphan.GERMAN_QUOTE -> germanQuote(close)
            RepairOrphan.AUTHORED_BOUNDARY -> "$messageBoundary\nA repaired first line."
            RepairOrphan.AUTHORED_SYSTEM_PREAMBLE -> "$repairSentence\nA repaired first line."
            RepairOrphan.INTERIOR_SYSTEM_PREAMBLE -> "A repaired first line.\n$repairSecondProse"
            RepairOrphan.WHOLE_REPAIR_SYSTEM_SUFFIX -> "$original."
            RepairOrphan.WHOLE_REPAIR_SYSTEM_INTERIOR -> "The door was closed."
            RepairOrphan.REPAIR_SYSTEM_ECHO_ONLY -> original
            RepairOrphan.PARTIAL_REPAIR_TAIL -> "门已关严。"
            else -> "A repaired first line."
        }
        val second = when {
            splitCaption -> close
            orphan == RepairOrphan.WHOLE_REPAIR_SYSTEM_INTERIOR -> "The lamp was lit."
            else -> "A repaired second line."
        }
        val tool = transport == Transport.EXPERIMENTAL_TOOL
        val parts = if (toolIndexes != null) toolIndexes.map { if (it == 0) first else second } else when (orphan) {
            RepairOrphan.FIRST -> listOf("$first\n$close", second)
            RepairOrphan.SECOND_AFTER_FULL_FRAME -> listOf(PromptTemplates.narrativeModeHistoryReply(first, repairMode), "$second\n$close")
            RepairOrphan.SPLIT_CAPTION -> listOf(first, second)
            RepairOrphan.VISIBLE_INPUT_LITERAL -> listOf(PromptTemplates.narrativeModeHistoryReply(first, repairMode))
            RepairOrphan.VISIBLE_INPUT_BLOCKQUOTE -> listOf(PromptTemplates.narrativeModeHistoryReply("$first\n", repairMode))
            RepairOrphan.PARAGRAPH_QUOTE, RepairOrphan.GERMAN_QUOTE, RepairOrphan.INDENTED_REPAIR_CARD ->
                listOf(PromptTemplates.narrativeModeHistoryReply(first, repairMode))
            RepairOrphan.QUOTED_POSSESSIVE -> {
                val framed = PromptTemplates.narrativeModeHistoryReply(first, repairMode)
                listOf(if (stop) framed.substringBeforeLast('\n') else framed)
            }
            RepairOrphan.AUTHORED_CLOSE_EXTRA, RepairOrphan.EXPLICIT_STANDALONE_PROHIBITION -> listOf("$first\n$close")
            RepairOrphan.DISPATCHED_BOUNDARIES -> if (tool) {
                listOf("$messageBoundary\n$first", "$bubbleBoundary\n$second")
            } else listOf("$cardBoundary\n$bubbleBoundary\n$messageBoundary\n$first")
            RepairOrphan.AUTHORED_BOUNDARY -> listOf(first)
            RepairOrphan.SYSTEM_PREAMBLE -> listOf("$messageBoundary\n$repairSentence\n$first")
            RepairOrphan.AUTHORED_SYSTEM_PREAMBLE -> listOf("$messageBoundary\n$first")
            RepairOrphan.INTERIOR_SYSTEM_PREAMBLE ->
                listOf("A repaired first line.\n$messageBoundary\n$repairSentence\n$repairSecondProse")
            RepairOrphan.SPLIT_SYSTEM_PREAMBLE -> listOf("$first\n$messageBoundary", "$repairSentence\n$second")
            RepairOrphan.UNLABELLED_SYSTEM_INTRODUCTION -> listOf("$repairIntroduction\n$first")
            RepairOrphan.WHOLE_REPAIR_SYSTEM_BLOCK -> listOf("$repairSystem\n\n$first")
            RepairOrphan.WHOLE_REPAIR_SYSTEM_SUFFIX -> listOf("$first\n\n$repairSystem")
            RepairOrphan.WHOLE_REPAIR_SYSTEM_INTERIOR ->
                if (tool) listOf(first, repairSystem, second) else listOf("$first\n\n$repairSystem\n\n$second")
            RepairOrphan.REPAIR_SYSTEM_ECHO_ONLY -> listOf(repairSystem)
            RepairOrphan.PARTIAL_REPAIR_TAIL -> listOf("$first\n{待修复")
            null -> if (tool) listOf(first, second) else listOf(first)
        }
        val formatCard = orphan?.let {
            val example = if (orphan == RepairOrphan.VISIBLE_INPUT_BLOCKQUOTE) "示例正文" else "旧示例"
            val sample = PromptTemplates.narrativeModeHistoryReply(example, repairMode)
            val instructions = when (orphan) {
                RepairOrphan.SPLIT_CAPTION -> "Decode written entities in literal examples.\n\nThe following is a counterexample:\n$sample"
                RepairOrphan.EXPLICIT_STANDALONE_PROHIBITION -> "Do not output the following history tags:\n$close"
                RepairOrphan.INDENTED_REPAIR_CARD -> "    以下是禁止输出的反例：\n$sample  \n"
                RepairOrphan.DISPATCHED_BOUNDARIES, RepairOrphan.AUTHORED_BOUNDARY,
                RepairOrphan.SYSTEM_PREAMBLE, RepairOrphan.AUTHORED_SYSTEM_PREAMBLE,
                RepairOrphan.INTERIOR_SYSTEM_PREAMBLE, RepairOrphan.SPLIT_SYSTEM_PREAMBLE,
                RepairOrphan.UNLABELLED_SYSTEM_INTRODUCTION, RepairOrphan.WHOLE_REPAIR_SYSTEM_BLOCK -> "Keep all prose."
                RepairOrphan.WHOLE_REPAIR_SYSTEM_SUFFIX -> "End the sentence with a period."
                RepairOrphan.WHOLE_REPAIR_SYSTEM_INTERIOR -> "Keep both revised paragraphs."
                RepairOrphan.REPAIR_SYSTEM_ECHO_ONLY -> "End the sentence with a period."
                RepairOrphan.PARTIAL_REPAIR_TAIL -> "Keep all prose."
                else -> "以下是禁止输出的反例：\n$sample"
            }
            FormatCard.create("Synthetic prohibited history wrapper", instructions)
        }
        val visiblePrefix = if (orphan == RepairOrphan.WHOLE_REPAIR_SYSTEM_INTERIOR && !tool) "$first\n\n$second" else first
        val firstPreview = MessageFormatRepairPolicy.progressiveOverlay(original, visiblePrefix)
        val expected = when {
            orphan == RepairOrphan.WHOLE_REPAIR_SYSTEM_INTERIOR -> if (tool) "$first\n$second" else visiblePrefix
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
                val repairUser = messages.first {
                    it.jsonObject["role"]?.jsonPrimitive?.content == "user" &&
                        (it.jsonObject["content"] as? JsonPrimitive)?.content?.contains(original) == true
                }.jsonObject.getValue("content").jsonPrimitive.content
                if (orphan == RepairOrphan.DISPATCHED_BOUNDARIES) {
                    assertTrue(listOf(cardBoundary, bubbleBoundary, messageBoundary).all { it in repairUser.lines() })
                }
                if (orphan in setOf(RepairOrphan.SYSTEM_PREAMBLE, RepairOrphan.AUTHORED_SYSTEM_PREAMBLE,
                        RepairOrphan.INTERIOR_SYSTEM_PREAMBLE, RepairOrphan.SPLIT_SYSTEM_PREAMBLE,
                        RepairOrphan.UNLABELLED_SYSTEM_INTRODUCTION, RepairOrphan.WHOLE_REPAIR_SYSTEM_BLOCK,
                        RepairOrphan.WHOLE_REPAIR_SYSTEM_SUFFIX, RepairOrphan.WHOLE_REPAIR_SYSTEM_INTERIOR,
                        RepairOrphan.REPAIR_SYSTEM_ECHO_ONLY,
                        RepairOrphan.PARTIAL_REPAIR_TAIL)
                ) {
                    assertTrue(messageBoundary in repairUser.lines())
                    val system = Json.parseToJsonElement(request).jsonObject.getValue("messages").jsonArray.first().jsonObject
                    assertEquals("system", system.getValue("role").jsonPrimitive.content)
                    assertTrue(PromptTemplates.MESSAGE_FORMAT_REPAIR_SYSTEM_PROMPT.trim() in system.getValue("content").jsonPrimitive.content)
                }
                formatCard?.let { format ->
                    assertTrue(messages.any { entry ->
                        (entry.jsonObject["content"] as? JsonPrimitive)?.content?.contains(format.content.trim()) == true
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
                    toolIndexes != null || orphan == RepairOrphan.REPAIR_SYSTEM_ECHO_ONLY -> MessageFormatRepairNoticeKind.ERROR
                    stop -> MessageFormatRepairNoticeKind.STOPPED
                    else -> MessageFormatRepairNoticeKind.APPLIED
                }
                assertEquals(expectedNotice, saved.formatRepairNotice?.kind)
                if (orphan == RepairOrphan.REPAIR_SYSTEM_ECHO_ONLY) {
                    assertNull(saved.formatRepairNotice?.originalContent)
                    assertEquals("格式修复模型返回空结果", saved.formatRepairNotice?.errorMessage)
                } else if (toolIndexes == null) assertEquals(original, saved.formatRepairNotice?.originalContent)
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

    private fun paragraphQuote(close: String): String =
        "She read the following, “The first paragraph gives context.\n\n" +
            "“The second paragraph gives this exact sample:\n$close\nThen she stopped.”"

    private fun germanQuote(close: String): String =
        "Sie las vor: „Hier beginnt das Beispiel:\n$close\nDas war das Beispiel.“"

    private fun sentMessages(request: String, transport: Transport): JsonArray {
        val payload = Json.parseToJsonElement(request).jsonObject
        if (!transport.experimental) return payload.getValue("messages").jsonArray
        val tools = payload.getValue("tools").jsonArray.map { it.jsonObject.getValue("function").jsonObject }
        assertTrue(tools.any { it.getValue("name").jsonPrimitive.content == "game_content" })
        val context = tools.single { it.getValue("name").jsonPrimitive.content == "context" }
            .getValue("description").jsonPrimitive.content
        // Experimental transport intentionally leaves its last assistant string open.
        return runCatching { Json.parseToJsonElement(context).jsonArray }
            .getOrElse { Json.parseToJsonElement(context + "\"}]").jsonArray }
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
