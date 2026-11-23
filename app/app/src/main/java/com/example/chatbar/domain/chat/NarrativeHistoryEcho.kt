package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatNarrativeMode
import com.example.chatbar.domain.prompt.PromptTemplates

/** Removes request-only history wrappers when the model echoes them around its new reply. */
object NarrativeHistoryEcho {
    private data class Wrapper(val name: String, val opening: String, val closings: List<String>)
    private data class WrapperBoundary(val closing: MatchResult?, val canEndWithPartialClose: Boolean)
    private data class CleanedReply(
        val body: String,
        val matchedOuterPair: Boolean = false,
        val pendingToolOpening: Int? = null,
        val preservedLiteralExample: Boolean = false,
    ) {
        val couldSpanToolCalls: Boolean get() = pendingToolOpening != null
    }
    private data class HeadingSelection(val body: IntRange, val prefixEnd: Int = 0) {
        fun content(text: String): String = text.substring(0, prefixEnd) + text.substring(body)
    }
    private data class LiteralMarkup(val range: IntRange, val isComment: Boolean)
    private data class LiteralOutput(
        val pairs: Set<String> = emptySet(),
        val openings: Set<String> = emptySet(),
        val closings: Set<String> = emptySet(),
    )
    private data class UserOutput(
        val literal: LiteralOutput = LiteralOutput(),
        val templateOpenings: Set<String> = emptySet(),
        val counterexampleClosingStarts: Set<Int> = emptySet(),
    )
    private data class ReplyLiterals(
        val ranges: List<IntRange>,
        val roleplayLiteralCode: List<IntRange>,
        val lazyQuoteLineStarts: Set<Int>,
        val listItemLineStarts: Set<Int>,
        val lazyListItemLineStarts: Set<Int>,
        val indentedParagraphLineStarts: Set<Int>,
        val markdown: String,
        val commentRanges: List<IntRange>,
        val fences: List<MarkdownCodeFences.Block>,
    )

    private val legacyWrapper = Wrapper("聚焦模式", "<聚焦模式=\"沉浸\">", listOf("</聚焦模式>", "</聚焦模式=\"沉浸\">"))
    private val wrappers = ChatNarrativeMode.entries.map { mode ->
        Wrapper("历史回复", "<历史回复 模式=\"${mode.shortLabel}\">", listOf("</历史回复>"))
    } + legacyWrapper
    private val wrapperClosings = wrappers.flatMapTo(mutableSetOf(), Wrapper::closings)
    private val narrativeModeHeadings = ChatNarrativeMode.entries.map { mode ->
        PromptTemplates.narrativeModeSystemPrompt(mode).lineSequence().first()
    }.toSet()
    private val wrapperFamilyTokens = wrappers.associate { wrapper ->
        val name = Regex.escape(wrapper.name)
        wrapper.name to Regex("</?$name(?=[\\s=>/])[^<>]*>")
    }
    private val backtickRuns = Regex("`+")
    private const val commentOpening = "<!--"
    private val commentDelimiters = Regex("<!--|-->")
    private val markdownContainerOrIndent = Regex(
        "(?m)^(?:[ \\t]{4}|[ \\t]*\\t| {0,3}(?:>|~{3,}|[-+*][ \\t]|[0-9]{1,9}[.)][ \\t]))",
    )
    private const val cdataOpening = "<![CDATA["
    private const val cdataClosing = "]]>"
    private val formatTemplateLabel = Regex(
        " {0,3}(\\*\\*|)(?:(?:本轮)?输出(?:模板|格式)|本轮的固定输出模板|请按以下格式输出|" +
            "每次回复必须使用此固定模板|请(?:完整|逐字)保留首尾标签|" +
            "(?:(?:required|exact|fixed)[ \\t]+)?output template(?:[ \\t]+\\(keep both tags verbatim\\))?)" +
            "(?:[ \\t]*[：:]\\1|\\1[ \\t]*[：:])[ \\t]*",
        RegexOption.IGNORE_CASE,
    )
    private val formatTemplateAffirmation = Regex(
        "[ \\t]*(?:（[ \\t]*标签是固定输出的一部分[ \\t]*）|\\([ \\t]*标签是固定输出的一部分[ \\t]*\\))[ \\t]*[。.]?[ \\t]*",
    )
    private const val keepTemplateTags =
        "(?:(?:必须|务必|请)(?:逐字|原样|完整)保留(?:以下)?首尾标签|" +
            "(?:keep|preserve)[ \\t]+(?:both|the)[ \\t]+(?:opening and closing[ \\t]+)?tags[ \\t]+" +
            "(?:verbatim|exactly as (?:written|shown)))"
    private const val templateSlot = "\\[[\\p{L}][\\p{L}\\p{N}_ -]{0,31}\\]"
    private const val userTemplateSlot = "($templateSlot)"
    private val formatTemplateSlot = Regex(templateSlot)
    private val userTemplateInstruction = Regex(
        " {0,3}(?:use (?:exactly )?(?:this|the following) XML (?:shape|template)[,.;][ \\t]*" +
            "replace $userTemplateSlot with (?:new prose|new story text|the new story)[ \\t]+" +
            "and (?:keep|preserve) both tags(?: verbatim| exactly as (?:written|shown))?|" +
            "请(?:严格)?使用以下[ \\t]*XML[ \\t]*模板[，,][ \\t]*将${userTemplateSlot}替换为新正文[，,]?[ \\t]*并保留首尾标签)" +
            "[：:][ \\t]*",
        RegexOption.IGNORE_CASE,
    )
    private const val quoteSamplePrefix = " {0,3}>[ \\t]?"
    private val quoteSampleLine = Regex(quoteSamplePrefix)
    private val quotedTemplateSample = Regex(
        "(?m)^$quoteSamplePrefix(${wrappers.joinToString("|") { Regex.escape(it.opening) }})[ \\t]*\\r?\\n" +
            "$quoteSamplePrefix($templateSlot)[ \\t]*\\r?\\n" +
            "$quoteSamplePrefix(${wrapperClosings.joinToString("|", transform = Regex::escape)})[ \\t]*(?=\\r?\\n|$)",
    )
    private val inlineExactCopyInstruction = Regex(
        " {0,3}(?:(?:please[ \\t]+)?(?:copy|return|reproduce|print|output|repeat|show)[ \\t]+" +
            "(?:(?:this|the[ \\t]+following)(?:[ \\t]+(?:text|XML|markup|snippet|block|element))?|" +
            "the[ \\t]+(?:text|XML|markup|snippet|block|element)[ \\t]+(?:below|that[ \\t]+follows))" +
            "[ \\t]+(?:verbatim|exactly)(?:,[ \\t]+with[ \\t]+both[ \\t]+tags)?|" +
            "请(?:逐字|原样)(?:复制|照抄)以下(?:文字|文本)[，,]?[ \\t]*并保留首尾标签)[：:][ \\t]*",
        RegexOption.IGNORE_CASE,
    )
    private val standaloneOpeningInstruction = Regex(
        " {0,3}(?:(?:please[ \\t]+)?(?:show|print|output)[ \\t]+(?:the[ \\t]+)?" +
            "literal[ \\t]+opening[ \\t]+tag[ \\t]+only[ \\t]+on[ \\t]+its[ \\t]+own[ \\t]+line" +
            "(?:,?[ \\t]+then[ \\t]+explain[ \\t]+it)?|" +
            "请(?:只|仅)在单独一行(?:原样|逐字)(?:显示|输出)(?:以下)?(?:开始|起始|开头)标签" +
            "(?:[，,]?[ \\t]*(?:然后|再)解释(?:它|该标签))?)[：:][ \\t]*",
        RegexOption.IGNORE_CASE,
    )
    private val standaloneClosingInstruction = Regex(
        " {0,3}(?:(?:please[ \\t]+)?(?:show|print|output)[ \\t]+(?:the[ \\t]+)?" +
            "literal[ \\t]+closing[ \\t]+tag[ \\t]+(?:only[ \\t]+)?on[ \\t]+its[ \\t]+own[ \\t]+line" +
            "(?:,?[ \\t]+then[ \\t]+explain[ \\t]+it)?|" +
            "请(?:只|仅)在单独一行(?:原样|逐字)(?:显示|输出)(?:以下)?(?:结束|闭合)标签)[：:][ \\t]*",
        RegexOption.IGNORE_CASE,
    )
    private const val omitTemplateFence =
        "(?:(?:不要|不得|禁止)输出代码围栏|" +
            "(?:omit|do not (?:output|include))[ \\t]+(?:the[ \\t]+)?(?:surrounding[ \\t]+)?(?:code[ \\t]+)?fence)"
    private const val forbidTemplateTags =
        "(?:(?:请勿|不要|不得|禁止|严禁)(?:在(?:本轮|回复中))?输出(?:以下(?:任何)?|下列|这些|上述|上面的)(?:历史)?标签|" +
            "(?:do not|never)[ \\t]+(?:output|include|emit)[ \\t]+(?:these|the (?:following|above))[ \\t]+" +
            "(?:(?:metadata|history|historical)[ \\t]+)?tags" +
            "(?:[ \\t]+in[ \\t]+(?:your[ \\t]+|the[ \\t]+)?(?:reply|replies|answer|output))?)"
    private val fencedTemplateLabel = Regex(
        " {0,3}请严格按照以下固定模板作答[，,][ \\t]*$keepTemplateTags[，,][ \\t]*$omitTemplateFence[：:][ \\t]*",
        RegexOption.IGNORE_CASE,
    )
    private val replySampleLabel = Regex(" {0,3}(\\*\\*|)回复样张[ \\t]*[：:]\\1[ \\t]*")
    private val outputFormatLabel = Regex(" {0,3}(\\*\\*|)output[ \\t]+format[ \\t]*[：:]\\1[ \\t]*", RegexOption.IGNORE_CASE)
    private val keepSampleTags = Regex(
        "^ {0,3}(?:每次回复[ \\t]*)?$keepTemplateTags(?:[ \\t]+in[ \\t]+your[ \\t]+(?:reply|answer))?(?=$|[，。；,.; \\t])",
        RegexOption.IGNORE_CASE,
    )
    private val omitSampleFence = Regex(
        "(?:^|[，。；,.;][ \\t]*|[ \\t]+and[ \\t]+)($omitTemplateFence)[。.]?[ \\t]*$",
        RegexOption.IGNORE_CASE,
    )
    private val directTemplateKeep = Regex(
        "(?:^|[。.!?；;])[ \\t]*($keepTemplateTags)(?=$|[，。；,.; \\t])",
        RegexOption.IGNORE_CASE,
    )
    private val headingProhibition = Regex(
        "(?:^|[。.!?；;])[ \\t]*(" +
            "(?:(?:下面|以下|这)是(?:一条)?(?:系统|内部|叙事模式)?标题[，,][ \\t]*)?" +
            "(?:请勿|请?不要|不得|禁止|严禁)(?:在(?:本轮(?:回复中|正文中)?|回复中|正文中))?(?:输出|包含|复制|复述)" +
            "(?:(?:以下|下列|下面的|这条|该)?(?:系统|内部|叙事模式)?标题)?" +
            "|(?:please[ \\t]+)?(?:do not|never|must not)[ \\t]+(?:include|copy|output|emit|repeat|reproduce)[ \\t]+" +
            "(?:(?:the|this|following)[ \\t]+)*(?:(?:internal|system|narrative mode|metadata)[ \\t]+)*(?:heading|title)" +
            "(?:[ \\t]+(?:in|into)[ \\t]+(?:your|the|this)[ \\t]+(?:reply|answer|output))?" +
            ")[ \\t]*[：:][ \\t]*$",
        RegexOption.IGNORE_CASE,
    )
    private val literalHeadingLead = Regex(
        "(?:(?:念出|读出|讀出|朗读|朗讀)[^。！？!?；;：:\\r\\n]{0,36}(?:标题|標題)|" +
            "\\b(?:reads?|recites?|recited)[ \\t]+(?:the[ \\t]+)?(?:on-screen[ \\t]+)?" +
            "(?:title|heading)(?:[ \\t]+aloud)?)[ \\t]*[：:][ \\t]*$",
        RegexOption.IGNORE_CASE,
    )
    private val trailingStatusLabel = Regex(
        " {0,3}(?:(?:required[ \\t]+)?trailing[ \\t]+status[ \\t]+(?:panel[ \\t]+)?template[：:]|" +
            "(?:write the story first\\.[ \\t]+)?end every reply with (?:exactly )?one status panel after the story\\." +
            "(?:[ \\t]+keep the status fence(?: and replace the time)?\\.)?|" +
            "每次回复末尾必须输出以下状态栏[：:]|" +
            "每次回复(?:必须)?以(?:一个)?状态栏结束[。.]?)[ \\t]*",
        RegexOption.IGNORE_CASE,
    )
    private val singlePairTemplateConstraint = Regex(
        " {0,3}(?:每次回复只使用一对以下首尾标签，正文中不得重复这两个标签。|" +
            "use exactly one pair of the following tags\\.[ \\t]+do not repeat them inside the reply\\.)[ \\t]*",
        RegexOption.IGNORE_CASE,
    )
    private val typedStatusInfos = setOf("status", "text")
    private val formatTemplateFenceInfo = setOf("", "html", "xml")
    private val formatCounterexampleLead = Regex(
        " {0,3}(?:以下是禁止输出的反例[。.:：]?|以下标签仅供举例，不要在回复中输出[：:]|" +
            "$forbidTemplateTags[。.:：]?|the following is a counterexample[.!:])[ \\t]*",
        RegexOption.IGNORE_CASE,
    )
    private val formatCounterexampleTail = Regex(
        " {0,3}$forbidTemplateTags[。.!]?[ \\t]*",
        RegexOption.IGNORE_CASE,
    )
    private val metadataExampleBridge = Regex(
        " {0,3}(?:for (?:reference|context)[,:][ \\t]+)?(?:the following|this)[ \\t]+" +
            "(?:illustrates|shows|is (?:an example|an illustration) of)[ \\t]+" +
            "(?:the provider's |the model's |internal |request-only |the )?" +
            "(?:history metadata|historical metadata|history tags|metadata tags)[.!:]?[ \\t]*",
        RegexOption.IGNORE_CASE,
    )
    private val historyExampleExplanation = Regex(
        " {0,3}(?:(?:以上|上述)(?:的)?(?:历史(?:记录)?标签|标签)(?:仅用于|只是为了)" +
            "(?:演示|说明|展示)(?:历史(?:记录)?格式|历史元数据)[。.]?|" +
            "这是(?:一个)?(?:历史)?标签示例[。.]?|" +
            "(?:The (?:above|preceding)|These) (?:history|metadata) tags (?:are|were) " +
            "(?:only|just) (?:an example|for illustration)\\.?)[ \\t]*",
        RegexOption.IGNORE_CASE,
    )
    private val literalClosingTagLead = Regex(
        "(?:\\b(?:literal|example of(?: the)?)[ \\t]+(?:closing|history)[ -]tag|" +
            "\\b(?:closing|history)[ -]tag[ \\t]+(?:example|sample)|" +
            "(?:结束|闭合|历史)标签(?:的)?(?:示例|样例))[ \\t]*[:：][ \\t]*$",
        RegexOption.IGNORE_CASE,
    )
    private val statusFenceInfo = Regex("(text|status)(?=$|[ \\t(（]).*", RegexOption.IGNORE_CASE)
    private val paragraphBreak = Regex("\\r?\\n[ \\t]*\\r?\\n")
    private val bareCarriageReturn = Regex("\\r(?!\\n)")
    private val proseDelimiters = mapOf(
        '「' to '」', '『' to '』', '｢' to '｣', '“' to '”', '‘' to '’', '«' to '»', '"' to '"', '\'' to '\'',
    )
    private val leadingApostropheContraction = Regex("t(?:is|was|were|will|would)(?![a-z])", RegexOption.IGNORE_CASE)
    private val unfinishedPluralPossessor = Regex("(?:the|these|those|our|your|their)[ \\t]+[a-z]{2,}s")
    private val unfinishedPossessedWord = Regex("[a-z]{2,}")
    private val chineseSpeechLead = Regex("[说說][ \\t]*[：:]?[ \\t]*$")
    private val hanIdeograph = Regex("[\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF]")
    private val ordinaryProseFollowers = setOf(
        "after", "again", "all", "already", "aloud", "and", "as", "away", "before", "but",
        "elsewhere", "for", "here", "in", "instead", "later", "now", "or", "outside", "so",
        "then", "there", "through", "to", "today", "tomorrow", "until", "when", "where", "while",
        "with", "without", "yesterday",
    )

    internal data class FormatOutput(
        val declaredOpenings: Set<String> = emptySet(),
        val trailingStatusInfos: Set<String> = emptySet(),
        val trailingBareStatusLeads: Set<String> = emptySet(),
        val singlePairOpenings: Set<String> = emptySet(),
        val declaresAnnotatedActions: Boolean = false,
        val counterexampleOpenings: Set<String> = emptySet(),
        val counterexampleClosingStarts: Set<Int> = emptySet(),
    )

    internal class RequestCleanup internal constructor(
        private val enabled: Boolean,
        private val formatOutput: FormatOutput = FormatOutput(),
        private val formatCard: String? = null,
        private val repairOpenings: Set<String>? = null,
        private val repairLiteralSources: List<String> = emptyList(),
    ) {
        private val projectedOpenings = mutableSetOf<String>()
        private val projectedDuplicateOpenings = mutableSetOf<String>()
        private val projectedInnerOpenings = mutableMapOf<String, MutableSet<String>>()
        private val projectedClosings = mutableSetOf<String>()
        private val narrativeHeadings = mutableSetOf<String>()
        private val authoredSources = mutableMapOf<String, UserOutput>()
        private val authoredPairs = mutableSetOf<String>()
        private val authoredTemplateOpenings = mutableSetOf<String>()
        private var currentLiteralOutput = LiteralOutput()

        fun recordNarrativePrompt(prompt: String, userText: String?) {
            prompt.lineSequence().forEachIndexed { index, line ->
                if (line.isNotBlank() && (index == 0 || line in narrativeModeHeadings)) narrativeHeadings += line
            }
            userText?.let(::recordAuthoredOutput)
        }

        fun recordAuthoredOutput(source: String) {
            if (!enabled || source.isBlank() || source in authoredSources) return
            val output = declaredUserOutput(source)
            authoredSources[source] = output
            authoredPairs += output.literal.pairs
            authoredTemplateOpenings += output.templateOpenings
        }

        fun recordCurrentLiteralTags(source: String) {
            if (!enabled || source.isBlank()) return
            currentLiteralOutput = (authoredSources[source] ?: declaredUserOutput(source)).literal
            authoredPairs += currentLiteralOutput.pairs
        }

        private fun isAuthoredHeading(token: String): Boolean =
            formatCard?.let { hasAuthoredHeading(it, token, narrativeHeadings) } == true ||
                authoredSources.keys.any { hasAuthoredHeading(it, token, narrativeHeadings) }

        private fun isAuthoredClosing(token: String): Boolean =
            authoredSources.any { (source, output) -> hasAuthoredClosing(source, token, output.counterexampleClosingStarts) } ||
                formatCard?.let { hasAuthoredClosing(it, token, formatOutput.counterexampleClosingStarts) } == true

        val declaresLegacyHistoryWrapper: Boolean
            get() = legacyWrapper.opening in formatOutput.declaredOpenings

        /** Record the transport framing added to this request, including a retained legacy projection. */
        fun recordHistoryProjection(body: String, projection: String, retainsLegacyFraming: Boolean = false) {
            if (!enabled) return
            if (projection == body && isStandaloneLegacyHistoryWrapper(projection)) {
                authoredPairs += body
                if (retainsLegacyFraming) {
                    projectedOpenings += legacyWrapper.opening
                    projectedClosings += legacyWrapper.closings.first { projection.endsWith(it) }
                }
                return
            }
            for (wrapper in wrappers) {
                val declaredSinglePair = wrapper.opening in formatOutput.singlePairOpenings
                if (projection != "${wrapper.opening}\n$body\n${wrapper.closings.first()}") continue
                projectedOpenings += wrapper.opening
                projectedClosings += wrapper.closings.first()
                if (legacyWrapper.isStandalonePair(body) && isStandaloneLegacyHistoryWrapper(body)) {
                    authoredPairs += body
                }
                if (wrapper.opening !in formatOutput.declaredOpenings) {
                    for (inner in wrappers) {
                        if (inner == wrapper || inner.opening in formatOutput.declaredOpenings ||
                            projectedInnerOpenings[wrapper.opening]?.contains(inner.opening) == true
                        ) continue
                        if (inner.isStandalonePair(body) && inner.hasExactlyOneVisiblePair(body)) {
                            projectedInnerOpenings.getOrPut(wrapper.opening, ::mutableSetOf).add(inner.opening)
                        }
                    }
                }
                if (wrapper.opening in projectedDuplicateOpenings) continue
                val undeclaredTransportPair = wrapper != legacyWrapper &&
                    wrapper.opening !in formatOutput.declaredOpenings && wrapper.isStandalonePair(body)
                if (declaredSinglePair && wrapper.hasExactlyOnePair(body) ||
                    undeclaredTransportPair && wrapper.hasExactlyOneVisiblePair(body)
                ) projectedDuplicateOpenings += wrapper.opening
            }
        }

        fun streamingPreview() = StreamingPreview()

        /** The live caller supplies the length before its append. Stop and Done use completedBody. */
        internal inner class StreamingPreview {
            private var seenLength = 0
            private var bodyStart: Int? = null
            private var requiresFullCleanup = false

            fun appendedBody(text: String, previousLength: Int, partLengths: List<Int> = emptyList()): String {
                if (!enabled) return text
                if (previousLength == 0 || previousLength != seenLength || previousLength !in 0..text.length) {
                    bodyStart = null
                    requiresFullCleanup = false
                }
                seenLength = text.length
                if (partLengths.isNotEmpty()) requiresFullCleanup = true
                val from = bodyStart
                if (!requiresFullCleanup && from != null && (previousLength until text.length).all { text[it].isPlainPreviewCharacter() }) {
                    return text.substring(from)
                }
                if (from != null) requiresFullCleanup = true
                val cleaned = completedBody(text, partLengths = partLengths, streaming = true)
                if (requiresFullCleanup) return cleaned

                val opening = projectedOpenings.firstOrNull { text.startsWith("$it\n") }
                if (opening == null && projectedOpenings.any { "$it\n".startsWith(text) }) return cleaned
                val candidateStart = if (opening == null) 0 else opening.length + 1
                // Only a single plain narration line can be admitted. Any syntax that a later
                // chunk could complete stays on the authoritative scanner.
                val canReuse = repairOpenings == null &&
                    narrativeHeadings.all { it.trimStart().firstOrNull()?.isPlainPreviewCharacter() == false } &&
                    (opening == null || opening !in formatOutput.declaredOpenings &&
                        opening !in currentLiteralOutput.openings && opening !in authoredTemplateOpenings &&
                        authoredPairs.none { it.startsWith(opening) }) &&
                    (candidateStart until text.length).all { text[it].isPlainPreviewCharacter() } &&
                    cleaned.length == text.length - candidateStart &&
                    text.regionMatches(candidateStart, cleaned, 0, cleaned.length)
                if (canReuse) bodyStart = candidateStart else requiresFullCleanup = true
                return cleaned
            }
        }

        fun completedBody(
            text: String,
            interrupted: Boolean = false,
            partLengths: List<Int> = emptyList(),
            streaming: Boolean = false,
        ): String {
            if (!enabled) return text
            val provisional = streaming || interrupted
            if (repairOpenings != null) {
                val cleaned = cleanCompletedBody(
                    text, formatOutput, interrupted, partLengths, streaming, allowedOpenings = repairOpenings,
                )
                if (cleaned != text) return cleaned
                val repairClosings = wrappers.filter { it.opening in repairOpenings }.flatMap { it.closings }
                    .filterNot { close -> repairLiteralSources.any { close in it } }
                return withoutOrphanClosing(
                    text, repairClosings, provisional, formatOutput,
                    isAuthored = { partial -> repairLiteralSources.any { partial in it } },
                )
            }

            val headings = narrativeHeadings.filterNot(::isAuthoredHeading)
            val replyFormat = if (authoredTemplateOpenings.isEmpty()) formatOutput else {
                formatOutput.copy(declaredOpenings = formatOutput.declaredOpenings + authoredTemplateOpenings)
            }
            val reply = withoutEchoedHeading(text, headings, provisional, ::isAuthoredHeading)
            val body = text.substring(reply.body)
            val preservedPrefix = text.substring(0, reply.prefixEnd)
            val lengths = if (reply.body == text.indices) partLengths else partLengths.trimTo(reply.body, text.length)
            val closings by lazy(LazyThreadSafetyMode.NONE) { projectedClosings.filterNot(::isAuthoredClosing) }
            val cleaned = cleanCompletedBody(
                body, replyFormat, interrupted, lengths, streaming, projectedDuplicateOpenings,
                projectedInnerOpenings = projectedInnerOpenings,
                allowedOpenings = projectedOpenings + formatOutput.counterexampleOpenings,
                literalOutput = currentLiteralOutput.copy(pairs = authoredPairs),
                trailerHeadings = headings,
                cleanExposedBody = { inner ->
                    val exposed = withoutEchoedHeading(inner, headings, provisional, ::isAuthoredHeading).content(inner)
                    withoutHeadingBeforeTrailer(exposed, headings, provisional, formatOutput)
                },
                cleanPartHeading = { part: String, isJoinedLiteral: (Int) -> Boolean ->
                    withoutEchoedHeading(part, headings, provisional, ::isAuthoredHeading, isJoinedLiteral)
                }.takeIf { headings.isNotEmpty() },
                cleanPartOrphan = { part, isJoinedLiteral ->
                    withoutOrphanClosing(part, closings, provisional, formatOutput, ::isAuthoredClosing, isJoinedLiteral)
                },
            )
            if (cleaned != body) {
                return preservedPrefix + withoutHeadingBeforeTrailer(cleaned, headings, provisional, formatOutput)
            }
            val withoutTrailerHeading = withoutHeadingBeforeTrailer(body, headings, provisional, formatOutput)
            val unwrapped = withoutOrphanClosing(
                withoutTrailerHeading, closings, provisional, formatOutput, ::isAuthoredClosing,
            )
            if (unwrapped == withoutTrailerHeading) return preservedPrefix + unwrapped
            return preservedPrefix + withoutHeadingBeforeTrailer(unwrapped, headings, provisional, formatOutput)
        }
    }

    private fun withoutHeadingBeforeTrailer(
        text: String, headings: List<String>, provisional: Boolean, formatOutput: FormatOutput,
    ): String {
        if (headings.none { it in text }) return text
        val literals = classifyLiterals(text, includeUnclosed = provisional)
        val heading = headingBeforeTrailer(
            text, headings, provisional, formatOutput, literals, literals.replySuffixRanges(formatOutput, provisional),
        ) ?: return text
        return text.substring(0, heading.first).trimEnd('\r', '\n') + text.substring(heading.last + 1)
    }

    private fun headingBeforeTrailer(
        text: String,
        headings: List<String>,
        provisional: Boolean,
        formatOutput: FormatOutput,
        literals: ReplyLiterals,
        suffixRanges: List<IntRange>,
        isJoinedLiteral: (Int) -> Boolean = { false },
    ): IntRange? {
        var cursor = text.length
        var removeFrom = -1
        var preserveFrom = -1
        while (cursor > 0) {
            val end = (cursor - 1 downTo 0).firstOrNull { !text[it].isWhitespace() }?.plus(1) ?: break
            val start = lineStart(text, end)
            cursor = start
            if (headingText(text.substring(start, end)) !in headings || !literals.isUnprotected(start) ||
                isJoinedLiteral(start) || literals.followsLiteralHeadingLead(start)
            ) {
                if (removeFrom >= 0) break
                continue
            }
            if (removeFrom < 0) {
                val lineBreak = text.indexOfAny(charArrayOf('\r', '\n'), end)
                if (lineBreak < 0 || !hasOnlyReplySuffix(
                        text, lineBreak, suffixRanges, literals, provisional, formatOutput, requireTrailer = true,
                    )
                ) continue
                preserveFrom = lineBreak
            }
            removeFrom = start
        }
        return if (removeFrom < 0) null else removeFrom until preserveFrom
    }

    private fun declaredUserOutput(text: String): UserOutput {
        if (wrappers.none { it.opening in text } && wrapperClosings.none { it in text }) return UserOutput()
        val templateOpenings = mutableSetOf<String>()
        val counterexampleClosingStarts = mutableSetOf<Int>()
        val standaloneOpenings = mutableSetOf<String>()
        val standaloneClosings = mutableSetOf<String>()
        val literals by lazy(LazyThreadSafetyMode.NONE) { classifyLiterals(text) }
        fun startsLine(offset: Int): Boolean {
            val start = lineStart(text, offset)
            return offset - start <= 3 && (start until offset).all { text[it] == ' ' }
        }
        val pairs = buildSet {
            for (family in wrapperFamilyTokens.values) {
                val tags = family.findAll(text).toList()
                for ((index, tag) in tags.withIndex()) {
                    val start = tag.range.first
                    val line = lineStart(text, start)
                    val atLineStart = startsLine(start)
                    if (!atLineStart && (tag.value in wrapperClosings ||
                            !inlineExactCopyInstruction.matches(text.subSequence(line, start)))
                    ) continue
                    val lead = previousVisibleLine(literals.markdown, line)
                    if (tag.value in wrapperClosings) {
                        val after = tag.range.last + 1
                        if (literals.isUnprotected(start) && text.subSequence(after, text.length).lineSequence().first().isBlank()) {
                            val explicitRequest = lead != null && literals.matchesVisibleLine(lead, standaloneClosingInstruction) &&
                                literals.markdown.substring(lead.last + 1, line) in setOf("\n", "\r\n")
                            val beforeLead = lead?.takeIf { explicitRequest }?.let { previousVisibleLine(literals.markdown, it.first) }
                            if (literals.prohibitsWrapper(lead, after) ||
                                beforeLead?.let { literals.prohibitsWrapper(it, after) } == true
                            ) counterexampleClosingStarts += start
                            else if (explicitRequest) standaloneClosings += tag.value
                        }
                        continue
                    }
                    val wrapper = wrappers.firstOrNull { it.opening == tag.value } ?: continue
                    if (atLineStart && lead != null &&
                        literals.matchesVisibleLine(lead, standaloneOpeningInstruction) &&
                        literals.markdown.substring(lead.last + 1, line) in setOf("\n", "\r\n") &&
                        literals.isUnprotected(start)
                    ) {
                        val after = tag.range.last + 1
                        val tail = text.subSequence(after, text.length).lineSequence().first()
                        if (tail.all { it.isHorizontalPadding() } &&
                            !literals.prohibitsWrapper(previousVisibleLine(literals.markdown, lead.first), after)
                        ) standaloneOpenings += wrapper.opening
                    }
                    val closing = wrapperBoundary(wrapper, tags.asSequence().drop(index + 1)).closing ?: continue
                    val end = closing.range.last + 1
                    val closingLineEnd = text.indexOfAny(charArrayOf('\r', '\n'), end).takeIf { it >= 0 } ?: text.length
                    if ((end until closingLineEnd).any { !text[it].isHorizontalPadding() }) continue
                    val visibleLead = literals.visibleLine(lead)
                    val labeled = visibleLead?.let(formatTemplateLabel::matches) == true
                    val prohibitionLead = if (labeled && lead != null) previousVisibleLine(literals.markdown, lead.first) else lead
                    val visiblePair = literals.isUnprotected(start) && literals.isUnprotected(closing.range.first)
                    val singleLine = lineStart(text, closing.range.first) == line
                    if (!atLineStart && (!singleLine || !visiblePair)) continue
                    if (visiblePair && literals.prohibitsWrapper(prohibitionLead, end)) {
                        counterexampleClosingStarts += closing.range.first
                        continue
                    }
                    val pair = text.substring(start, end)
                    val standalonePair = atLineStart && text.getOrNull(tag.range.last + 1).isHistoryLineBreak() &&
                        startsLine(closing.range.first)
                    val explicitMultilineCopy = atLineStart && visiblePair && !singleLine && lead != null &&
                        visibleLead?.let(inlineExactCopyInstruction::matches) == true &&
                        literals.markdown.substring(lead.last + 1, line) in setOf("\n", "\r\n") &&
                        text.getOrNull(tag.range.last + 1).isHistoryLineBreak()
                    if (singleLine || standalonePair || explicitMultilineCopy) add(pair)
                    if (!standalonePair || !visiblePair || visibleLead == null) continue
                    val templateBody = text.substring(tag.range.last + 1, closing.range.first).trim()
                    val slot = userTemplateSlotFromInstruction(visibleLead)
                    if (templateBody == slot || labeled && formatTemplateSlot.matches(templateBody)) {
                        templateOpenings += wrapper.opening
                    }
                }
            }
        }
        for (fence in literals.fences) {
            val closingLine = fence.closingLineStart ?: continue
            if (!fence.topLevel || fence.info.lowercase() !in formatTemplateFenceInfo) continue
            val lead = previousVisibleLine(literals.markdown, fence.range.first) ?: continue
            val slot = literals.visibleLine(lead)?.let(::userTemplateSlotFromInstruction) ?: continue
            val previous = previousVisibleLine(literals.markdown, lead.first)
            if (literals.prohibitsWrapper(previous, fence.range.last + 1)) continue
            val contentStart = literals.markdown.indexOf('\n', fence.range.first) + 1
            val template = text.substring(contentStart, closingLine).trim()
            val wrapper = wrappers.firstOrNull { it.isStandalonePair(template) } ?: continue
            val close = wrapper.closings.first(template::endsWith)
            if (template.substring(wrapper.opening.length, template.length - close.length).trim() == slot) {
                templateOpenings += wrapper.opening
            }
        }
        for (sample in quotedTemplateSample.findAll(bareCarriageReturn.replace(text, "\n"))) {
            val opening = requireNotNull(sample.groups[1])
            val slot = requireNotNull(sample.groups[2])
            val closing = requireNotNull(sample.groups[3])
            val wrapper = wrappers.first { it.opening == opening.value }
            if (closing.value !in wrapper.closings ||
                listOf(opening, slot, closing).any { part -> literals.ranges.containsOffset(part.range.first) }
            ) continue
            val start = sample.range.first
            val end = sample.range.last + 1
            val nextLine = literals.markdown.indexOf('\n', end) + 1
            if (nextLine > 0 && (quoteSampleLine.matchAt(literals.markdown, nextLine) != null ||
                    literals.isLazyQuoteLine(nextLine))
            ) continue
            val lead = previousVisibleLine(literals.markdown, start) ?: continue
            val gap = literals.markdown.substring(lead.last + 1, start)
            if (gap != "\n" && gap != "\r\n") continue
            if (literals.visibleLine(lead)?.let(::userTemplateSlotFromInstruction) != slot.value) continue
            val previous = previousVisibleLine(literals.markdown, lead.first)
            if (literals.prohibitsWrapper(previous, end)) counterexampleClosingStarts += closing.range.first
            else templateOpenings += wrapper.opening
        }
        return UserOutput(LiteralOutput(pairs, standaloneOpenings, standaloneClosings), templateOpenings, counterexampleClosingStarts)
    }

    private fun userTemplateSlotFromInstruction(line: String): String? {
        val match = userTemplateInstruction.matchEntire(line) ?: return null
        return (match.groups[1] ?: match.groups[2])?.value
    }

    private fun hasAuthoredClosing(source: String, token: String, prohibitedStarts: Set<Int>): Boolean {
        var position = source.indexOf(token)
        while (position >= 0) {
            if (position !in prohibitedStarts) return true
            position = source.indexOf(token, position + 1)
        }
        return false
    }

    internal fun forRequest(narrativeMode: ChatNarrativeMode?, formatCard: String?): RequestCleanup =
        if (narrativeMode == null) RequestCleanup(enabled = false)
        else RequestCleanup(
            enabled = true, formatOutput = formatCard?.let(::declaredOutput) ?: FormatOutput(), formatCard = formatCard,
        )

    internal fun forFormatRepair(formatCard: String?, original: String, userText: String? = null): RequestCleanup {
        val output = formatCard?.let(::declaredOutput) ?: FormatOutput()
        val sources = listOfNotNull(original, userText)
        val candidates = wrappers.filter {
            it.opening in output.counterexampleOpenings && it.opening !in output.declaredOpenings
        }
        val delimiterTokens = candidates.flatMapTo(mutableSetOf()) { listOf(it.opening) + it.closings }
        // Delimiters inside a literal are independent of a newly emitted outer pair.
        // Keep the stricter source check for orphan closers, where there is no matching opener.
        val visibleDelimiters = sources.flatMapTo(mutableSetOf()) { source ->
            if (delimiterTokens.none { it in source }) return@flatMapTo emptyList()
            val tags = wrapperFamilyTokens.values.flatMap { it.findAll(source).toList() }
            val literals = classifyLiterals(source, tags)
            tags.filter {
                it.value in delimiterTokens && literals.isVisibleWrapperTag(source, it, includeStandaloneLazyTags = true)
            }
                .map { it.value }
        }
        val openings = candidates.filter { wrapper ->
            wrapper.opening !in visibleDelimiters && wrapper.closings.none { it in visibleDelimiters }
        }.mapTo(mutableSetOf(), Wrapper::opening)
        return RequestCleanup(
            enabled = openings.isNotEmpty(), formatOutput = output, repairOpenings = openings,
            repairLiteralSources = sources,
        )
    }

    private fun hasAuthoredHeading(source: String, token: String, issuedHeadings: Set<String>): Boolean {
        var position = source.indexOf(token)
        if (position < 0) return false
        val possibleHeadings = issuedHeadings.filter { it.startsWith(token) }
        val literals by lazy(LazyThreadSafetyMode.NONE) { classifyLiterals(source) }
        while (position >= 0) {
            val heading = possibleHeadings.firstOrNull { source.startsWith(it, position) } ?: return true
            val start = lineStart(source, position)
            val end = position + heading.length
            val lineEnd = source.indexOfAny(charArrayOf('\r', '\n'), end).takeIf { it >= 0 } ?: source.length
            if (source.substring(end, lineEnd).isNotBlank()) return true
            val lead = if (source.substring(start, position).isNotBlank()) start until position else {
                previousVisibleLine(source, start) ?: return true
            }
            val instruction = headingProhibition.find(source.substring(lead))?.groups?.get(1) ?: return true
            if (!literals.isUnprotected(lead.first + instruction.range.first) || !literals.isUnprotected(position)) {
                return true
            }
            position = source.indexOf(token, end)
        }
        return false
    }

    private fun withoutEchoedHeading(
        text: String, headings: List<String>, provisional: Boolean, isAuthored: (String) -> Boolean,
        isJoinedLiteral: (Int) -> Boolean = { false },
    ): HeadingSelection {
        if (headings.isEmpty() || text.isBlank()) return HeadingSelection(text.indices)
        val literals by lazy(LazyThreadSafetyMode.NONE) { classifyLiterals(text, includeUnclosed = provisional) }
        fun matches(rawLine: String, allowPrefix: Boolean): Boolean {
            val line = headingText(rawLine) ?: return false
            return headings.any { heading ->
                line == heading || allowPrefix && heading.startsWith(line) &&
                    line.startsWith(heading.substringBefore('（').removeSuffix("】")) && !isAuthored(line)
            }
        }

        val commentsEnd = leadingClosedCommentLinesEnd(text)
        var bodyStart = commentsEnd
        var cursor = commentsEnd
        while (cursor < text.length) {
            val start = (cursor until text.length).firstOrNull { !text[it].isHistoryLineBreak() } ?: break
            val end = text.indexOfAny(charArrayOf('\r', '\n'), start).takeIf { it >= 0 } ?: text.length
            cursor = (end until text.length).firstOrNull { !text[it].isHistoryLineBreak() } ?: text.length
            val line = text.substring(start, end)
            if (line.all { it.isHorizontalPadding() }) continue
            if (!matches(line, provisional && end == text.length) ||
                !literals.isUnprotected(start) || isJoinedLiteral(start)
            ) break
            bodyStart = cursor
        }
        val prefixEnd = if (bodyStart > commentsEnd) commentsEnd else 0
        if (bodyStart == commentsEnd) bodyStart = 0
        var bodyEnd = text.length
        while (bodyEnd > bodyStart) {
            val end = (bodyEnd - 1 downTo bodyStart).firstOrNull { !text[it].isWhitespace() }?.plus(1) ?: break
            val start = lineStart(text, end)
            if (!matches(text.substring(start, end), provisional && bodyEnd == text.length) ||
                !literals.isUnprotected(start) || isJoinedLiteral(start) || literals.followsLiteralHeadingLead(start)
            ) break
            bodyEnd = (start - 1 downTo bodyStart).firstOrNull { !text[it].isHistoryLineBreak() }?.plus(1) ?: bodyStart
        }
        return HeadingSelection(bodyStart until bodyEnd, prefixEnd)
    }

    private fun leadingClosedCommentLinesEnd(text: String): Int {
        var offset = 0
        while (offset < text.length) {
            val opening = (offset until text.length).firstOrNull { !text[it].isWhitespace() } ?: break
            if (!text.startsWith(commentOpening, opening) || hasCodeIndent(text, opening)) break
            val end = closedCommentEnd(text, opening) ?: break
            val lineBreak = text.indexOfAny(charArrayOf('\r', '\n'), end)
            if (lineBreak < 0 || text.subSequence(end, lineBreak).isNotBlank()) break
            offset = if (text.startsWith("\r\n", lineBreak)) lineBreak + 2 else lineBreak + 1
        }
        return offset
    }

    private fun headingText(line: String): String? {
        val start = line.indexOfFirst { it != ' ' }
        return if (start in 0..3) line.substring(start).trimEnd(' ', '\t') else null
    }

    private fun ReplyLiterals.followsLiteralHeadingLead(start: Int): Boolean =
        visibleLine(previousVisibleLine(markdown, start))?.let(literalHeadingLead::containsMatchIn) == true

    private fun List<Int>.trimTo(reply: IntRange, textLength: Int): List<Int> {
        if (size <= 1 || any { it < 0 } || sumOf(Int::toLong) + size - 1 != textLength.toLong()) return emptyList()
        var offset = 0
        val end = reply.last + 1
        return mapNotNull { length ->
            val limit = offset + length
            val overlap = minOf(limit, end) - maxOf(offset, reply.first)
            offset = limit + 1
            overlap.takeIf { it >= 0 }
        }
    }

    private fun withoutOrphanClosing(
        text: String, closings: List<String>, provisional: Boolean, formatOutput: FormatOutput,
        isAuthored: (String) -> Boolean,
        isJoinedLiteral: (Int) -> Boolean = { false },
    ): String {
        if (closings.isEmpty() || "</" !in text) return text
        val end = text.trimEnd()
        val partialStart = lineStart(end, end.length)
        val partial = if (provisional) end.substring(partialStart).trimStart(' ') else ""
        val tags = wrappers.filter { wrapper -> wrapper.closings.any { it in closings } }
            .distinctBy(Wrapper::name)
            .flatMap { wrapperFamilyTokens.getValue(it.name).findAll(end).toList() }
            .sortedBy { it.range.first }
        val candidates = tags.filter { it.value in closings && hasMarkerPadding(end, it.range.first) }
        val partialClosing = partial.takeIf {
            it.length > 2 && hasMarkerPadding(end, end.length - it.length) && closings.any { close ->
                val name = wrappers.first { close in it.closings }.name
                it.startsWith("</${name.dropLast(1)}") && it.length < close.length && close.startsWith(it)
            } && !isAuthored(it)
        }
        if (candidates.isEmpty() && partialClosing == null) return text
        val literals = classifyLiterals(end, tags, closings, includeUnclosed = provisional)
        val visible = tags.filter {
            literals.isVisibleWrapperTag(end, it, includeStandaloneLazyTags = true) &&
                !literals.isLazyListItemLine(it.range.first) && !isJoinedLiteral(it.range.first)
        }
        val close = visible.singleOrNull()?.takeIf { it in candidates }
        val position = close?.range?.first ?: if (visible.isEmpty() && partialClosing != null) {
            (end.length - partialClosing.length).takeIf {
                literals.isUnprotected(it) && !literals.isLazyListItemLine(it) && !isJoinedLiteral(it)
            }
                ?: return text
        } else return text
        val before = end.substring(0, lineStart(end, position))
        val lastBodyCharacter = before.trimEnd().lastOrNull()
        if (lastBodyCharacter == ':' || lastBodyCharacter == '：') return text
        if (close == null || close.range.last == end.lastIndex) return before.trimEnd('\r', '\n')
        val suffixStart = close.range.last + 1
        if (!hasOnlyReplySuffix(
                end, suffixStart, literals.replySuffixRanges(formatOutput, provisional), literals,
                provisional, formatOutput,
            )
        ) return text
        val suffix = text.substring(suffixStart)
        return if (suffix.lineSequence().first().isNotBlank()) before + suffix else before.trimEnd('\r', '\n') + suffix
    }

    private fun declaredOutput(formatCard: String): FormatOutput {
        val candidates = wrappers.filter { it.opening in formatCard }
        if (candidates.isEmpty() && annotatedRoleplayActionOpening !in formatCard && "```" !in formatCard) {
            return FormatOutput()
        }
        val tokens = candidates.map { it.name }.distinct().flatMap { name ->
            wrapperFamilyTokens.getValue(name).findAll(formatCard).toList()
        }.sortedBy { it.range.first }
        val literals = classifyLiterals(formatCard, tokens)
        val markup = tokens.filter {
            literals.isVisibleWrapperTag(formatCard, it, includeStandaloneLazyTags = true)
        }
        val trailingStatusInfos = mutableSetOf<String>()
        val trailingBareStatusLeads = mutableSetOf<String>()
        val singlePairOpenings = mutableSetOf<String>()
        val counterexampleOpenings = mutableSetOf<String>()
        val counterexampleClosingStarts = mutableSetOf<Int>()
        val declaredOpenings = buildSet {
            var matchedEnd = -1
            for ((index, opening) in markup.withIndex()) {
                val start = opening.range.first
                if (start <= matchedEnd) continue
                val wrapper = candidates.firstOrNull { it.opening == opening.value } ?: continue
                val prefixStart = lineStart(formatCard, start)
                val prefix = formatCard.subSequence(prefixStart, start)
                val before = previousVisibleLine(literals.markdown, prefixStart)
                val standaloneLabel = before?.takeIf { line ->
                    formatTemplateLabel.matches(literals.markdown.substring(line)) &&
                        !literals.ranges.containsOffset(line.first) &&
                        !literals.isMarkdownContainerLine(line.first, includeStandaloneLazyTags = true) &&
                        (prefixStart until start).all { offset ->
                            formatCard[offset].isWhitespace() || literals.commentRanges.any { offset in it }
                        }
                }
                val explicitLabel = standaloneLabel != null ||
                    formatTemplateLabel.matches(literals.markdown.subSequence(prefixStart, start))
                if (!explicitLabel && !prefix.isBlank()) continue
                val family = wrapperFamilyTokens.getValue(wrapper.name)
                val closing = wrapperBoundary(
                    wrapper, markup.asSequence().drop(index + 1).filter { family.matches(it.value) },
                ).closing ?: continue
                matchedEnd = closing.range.last
                val suffix = literals.markdown.substring(matchedEnd + 1).lineSequence().first()
                if (!suffix.isBlank() && !(explicitLabel && formatTemplateAffirmation.matches(suffix))) continue

                val lead = if (standaloneLabel == null) before else {
                    previousVisibleLine(literals.markdown, standaloneLabel.first)
                }
                if (literals.prohibitsWrapper(lead, matchedEnd + 1)) {
                    counterexampleOpenings += wrapper.opening
                    counterexampleClosingStarts += closing.range.first
                    continue
                }
                add(wrapper.opening)
                if (explicitLabel && lead != null &&
                    singlePairTemplateConstraint.matches(literals.markdown.substring(lead)) &&
                    wrapper.hasExactlyOnePair(formatCard.substring(start, matchedEnd + 1))
                ) singlePairOpenings += wrapper.opening
            }
            for (fence in literals.fences) {
                val closingLine = fence.closingLineStart ?: continue
                if (!fence.topLevel) continue
                val label = previousVisibleLine(literals.markdown, fence.range.first) ?: continue
                if (literals.ranges.containsOffset(label.first) ||
                    literals.isMarkdownContainerLine(label.first, includeStandaloneLazyTags = true)
                ) continue
                val before = previousVisibleLine(literals.markdown, label.first)
                val after = nextNonblankLine(literals.markdown, fence.range.last + 1)
                val isCounterexample = literals.isCounterexampleBefore(before) ||
                    literals.isCounterexampleBefore(label) || literals.matchesVisibleLine(after, formatCounterexampleTail)

                val labelText = literals.markdown.substring(label)
                val precedingInstruction = literals.visibleLine(before)
                val isReplySample = replySampleLabel.matches(labelText)
                val contentStart = literals.markdown.indexOf('\n', fence.range.first) + 1
                val template = formatCard.substring(contentStart, closingLine).trim()
                if (isCounterexample) {
                    val example = candidates.firstOrNull {
                        template.startsWith(it.opening) && it.closings.any(template::endsWith)
                    }
                    if (example != null && cleanReply(
                            template, streaming = false, interrupted = false, requirePair = true,
                        ).matchedOuterPair
                    ) {
                        counterexampleOpenings += example.opening
                        val templateStart = formatCard.indexOf(example.opening, contentStart)
                        val close = example.closings.first(template::endsWith)
                        counterexampleClosingStarts += templateStart + template.length - close.length
                    }
                    continue
                }
                val statusInfo = fence.statusInfo()
                if (statusInfo != null && template.isNotBlank() &&
                    (trailingStatusLabel.matches(labelText) ||
                        isReplySample && precedingInstruction?.let(trailingStatusLabel::matches) == true)
                ) {
                    if (statusInfo.isEmpty()) {
                        val lead = statusLead(template.lineSequence().first())
                        if (lead.isNotEmpty()) trailingBareStatusLeads += lead
                    } else {
                        trailingStatusInfos += statusInfo
                    }
                }
                val explicitReplySample = isReplySample && requiresUnfencedTags(precedingInstruction)
                val explicitOutputFormat = outputFormatLabel.matches(labelText) &&
                    (requiresUnfencedTags(precedingInstruction) || requiresUnfencedTags(literals.visibleLine(after)))
                if (fence.info.lowercase() !in formatTemplateFenceInfo) continue
                val labeled = fencedTemplateLabel.matches(labelText) || formatTemplateLabel.matches(labelText) ||
                    explicitReplySample || explicitOutputFormat
                val wrapper = candidates.firstOrNull {
                    template.startsWith(it.opening) && it.closings.any(template::endsWith)
                } ?: continue
                if (!labeled && (!wrapper.hasTemplateSlot(template) || !literals.directlyRequiresUnfencedTags(label, before))) {
                    continue
                }
                if (cleanReply(template, streaming = false, interrupted = false, requirePair = true).matchedOuterPair) {
                    add(wrapper.opening)
                    if (before != null && singlePairTemplateConstraint.matches(literals.markdown.substring(before)) &&
                        wrapper.hasExactlyOnePair(template)
                    ) singlePairOpenings += wrapper.opening
                }
            }
        }
        val declaresAnnotatedActions = roleplayAnnotatedActionRanges(literals.markdown).any { range ->
            literals.isUnprotected(range.first) && literals.isUnprotected(range.last) &&
                !literals.matchesVisibleLine(
                    previousVisibleLine(literals.markdown, range.first), formatCounterexampleLead,
                ) &&
                !literals.matchesVisibleLine(nextNonblankLine(literals.markdown, range.last + 1), formatCounterexampleTail)
        }
        return FormatOutput(
            declaredOpenings, trailingStatusInfos, trailingBareStatusLeads, singlePairOpenings, declaresAnnotatedActions,
            counterexampleOpenings, counterexampleClosingStarts,
        )
    }

    private fun requiresUnfencedTags(instruction: String?): Boolean =
        instruction != null && keepSampleTags.containsMatchIn(instruction) && omitSampleFence.containsMatchIn(instruction)

    private fun ReplyLiterals.directlyRequiresUnfencedTags(line: IntRange, previous: IntRange?): Boolean {
        val adjacent = previous?.takeIf {
            markdown.subSequence(it.last + 1, line.first).count { char -> char == '\n' } == 1
        }
        val lines = listOfNotNull(adjacent, line)
        fun hasVisibleCommand(pattern: Regex): Boolean = lines.any { range ->
            val command = visibleLine(range)?.trimEnd()?.let(pattern::find)?.groups?.get(1)
            command != null && isUnprotected(range.first + command.range.first) &&
                isUnprotected(range.first + command.range.last)
        }
        return hasVisibleCommand(directTemplateKeep) && hasVisibleCommand(omitSampleFence)
    }

    private fun Wrapper.hasTemplateSlot(template: String): Boolean {
        if (!isStandalonePair(template)) return false
        val close = closings.first(template::endsWith)
        return formatTemplateSlot.matches(template.substring(opening.length, template.length - close.length).trim())
    }

    private fun ReplyLiterals.visibleLine(line: IntRange?): String? =
        line?.takeIf { isUnprotected(it.first) }?.let(markdown::substring)

    private fun ReplyLiterals.matchesVisibleLine(line: IntRange?, pattern: Regex): Boolean =
        visibleLine(line)?.let(pattern::matches) == true

    private fun ReplyLiterals.isCounterexampleBefore(lead: IntRange?): Boolean {
        if (lead == null) return false
        val instruction = visibleLine(lead) ?: return false
        if (formatCounterexampleLead.matches(instruction)) return true
        return metadataExampleBridge.matches(instruction) &&
            matchesVisibleLine(previousVisibleLine(markdown, lead.first), formatCounterexampleLead)
    }

    private fun ReplyLiterals.prohibitsWrapper(lead: IntRange?, after: Int): Boolean =
        isCounterexampleBefore(lead) ||
            matchesVisibleLine(nextNonblankLine(markdown, after), formatCounterexampleTail)

    private fun ReplyLiterals.isUnprotected(position: Int): Boolean =
        !ranges.containsOffset(position) && !isMarkdownContainerLine(position, includeStandaloneLazyTags = true)

    private fun Wrapper.hasExactlyOnePair(text: String): Boolean =
        text.startsWith(opening) && closings.any(text::endsWith) &&
            wrapperFamilyTokens.getValue(name).findAll(text).count() == 2

    private fun Wrapper.hasExactlyOneVisiblePair(text: String): Boolean {
        val tags = wrapperFamilyTokens.getValue(name).findAll(text).toList()
        val literals = classifyLiterals(text, tags, closings)
        val visible = tags.filter { literals.isVisibleWrapperTag(text, it) }
        return visible.size == 2 && visible.first().range.first == 0 &&
            visible.last().range.last + 1 == text.length && visible.last().value in closings
    }

    private fun Wrapper.isStandalonePair(text: String): Boolean =
        text.startsWith(opening) && text.getOrNull(opening.length).isHistoryLineBreak() &&
            closings.any { text.endsWith(it) && text.getOrNull(text.length - it.length - 1).isHistoryLineBreak() }

    private fun MarkdownCodeFences.Block.statusInfo(): String? =
        info.lowercase().takeIf { topLevel && marker == '`' && markerLength == 3 && (it.isEmpty() || it in typedStatusInfos) }

    private fun statusLead(line: String): String {
        val colon = line.indexOfFirst { it == ':' || it == '：' }
        return (if (colon >= 0) line.substring(0, colon) else line).trim().let {
            if (colon >= 0 && it.isNotEmpty()) "$it:" else it
        }
    }

    private fun MarkdownCodeFences.Block.matchesBareStatusLead(
        text: String, declaredLeads: Set<String>, provisional: Boolean,
    ): Boolean {
        if (declaredLeads.isEmpty() || closingLineStart == null && !provisional) return false
        val firstNewline = text.indexOf('\n', range.first)
        val bodyEnd = closingLineStart ?: range.last + 1
        val body = if (firstNewline in range) text.substring(firstNewline + 1, bodyEnd) else ""
        val firstLine = body.lineSequence().firstOrNull { it.isNotBlank() }
        if (firstLine == null) return closingLineStart == null && provisional
        val lead = statusLead(firstLine)
        return lead in declaredLeads || lead.isNotEmpty() && closingLineStart == null && provisional &&
            !body.endsWith('\n') && body.trimStart() == firstLine.trimStart() && declaredLeads.any { it.startsWith(lead) }
    }

    private fun previousVisibleLine(markdown: String, before: Int): IntRange? {
        var end = before
        while (end > 0) {
            end--
            if (markdown[end] == '\n' && end > 0 && markdown[end - 1] == '\r') end--
            val start = lineStart(markdown, end)
            if (markdown.subSequence(start, end).isNotBlank()) return start until end
            end = start
        }
        return null
    }

    private fun nextNonblankLine(markdown: String, after: Int): IntRange? {
        var start = markdown.indexOf('\n', after) + 1
        if (start == 0) return null
        while (start < markdown.length) {
            val newline = markdown.indexOf('\n', start).takeIf { it >= 0 } ?: markdown.length
            val end = if (markdown.getOrNull(newline - 1) == '\r') newline - 1 else newline
            if (markdown.subSequence(start, end).isNotBlank()) return start until end
            start = newline + 1
        }
        return null
    }

    fun completedBody(
        text: String,
        enabled: Boolean,
        interrupted: Boolean = false,
        partLengths: List<Int> = emptyList(),
        streaming: Boolean = false,
    ): String = if (enabled) {
        cleanCompletedBody(text, FormatOutput(), interrupted, partLengths, streaming)
    } else text

    private fun cleanCompletedBody(
        text: String,
        formatOutput: FormatOutput,
        interrupted: Boolean,
        partLengths: List<Int>,
        streaming: Boolean,
        projectedDuplicateOpenings: Set<String> = emptySet(),
        projectedInnerOpenings: Map<String, Set<String>> = emptyMap(),
        allowedOpenings: Set<String>? = null,
        literalOutput: LiteralOutput = LiteralOutput(),
        trailerHeadings: List<String> = emptyList(),
        cleanExposedBody: (String) -> String = { it },
        cleanPartHeading: ((String, (Int) -> Boolean) -> HeadingSelection)? = null,
        cleanPartOrphan: ((String, (Int) -> Boolean) -> String)? = null,
    ): String {
        fun clean(part: String) = cleanReply(
            part, streaming, interrupted, formatOutput = formatOutput,
            projectedDuplicateOpenings = projectedDuplicateOpenings,
            projectedInnerOpenings = projectedInnerOpenings,
            allowedOpenings = allowedOpenings, literalOutput = literalOutput, trailerHeadings = trailerHeadings,
            cleanExposedBody = cleanExposedBody,
        ).body
        if (partLengths.size > 1 && partLengths.sumOf(Int::toLong) + partLengths.size - 1 == text.length.toLong()) {
            var offset = 0
            val parts = partLengths.map { length ->
                val end = offset + length
                if (length < 0 || end !in offset..text.length ||
                    (end < text.length && text[end] != '\n')
                ) return clean(text)
                (offset until end).also { offset = end + 1 }
            }
            // A model can spread one wrapper across several valid game_content calls.
            // A complete match on the joined body takes precedence over per-call cleanup,
            // including when the format card reserves the matching outer wrapper.
            val wholeReply = cleanReply(
                text, streaming, interrupted, requirePair = true, formatOutput = formatOutput,
                projectedDuplicateOpenings = projectedDuplicateOpenings, projectedInnerOpenings = projectedInnerOpenings,
                allowedOpenings = allowedOpenings, literalOutput = literalOutput, trailerHeadings = trailerHeadings,
                cleanExposedBody = cleanExposedBody,
            )
            if (wholeReply.matchedOuterPair) return wholeReply.body
            // Independent wrappers and groups of calls can coexist in the same tool reply.
            val joinedTags by lazy(LazyThreadSafetyMode.NONE) {
                wrapperFamilyTokens.mapValues { (_, family) -> family.findAll(text).toList() }
            }
            val joinedLiterals by lazy(LazyThreadSafetyMode.NONE) {
                val tags = joinedTags.values.flatten().sortedBy { it.range.first }
                classifyLiterals(text, tags, wrappers.flatMap { it.closings }, includeUnclosed = streaming || interrupted)
            }
            val toolCloses by lazy(LazyThreadSafetyMode.NONE) {
                toolClosesByOpening(text, joinedTags, joinedLiterals, literalOutput)
            }
            fun explainsPreviousExample(index: Int): Boolean =
                parts.getOrNull(index)?.let { part ->
                    text.substring(part).lineSequence().firstOrNull(String::isNotBlank)
                        ?.let(historyExampleExplanation::matches)
                } == true

            var previousGroupClosedWrapper = false
            fun cleanParts(
                first: Int,
                last: Int,
                requirePair: Boolean = false,
                followsClosedWrapper: Boolean = previousGroupClosedWrapper,
            ): CleanedReply {
                val start = parts[first].first
                val part = text.substring(start, parts[last].last + 1)
                fun isJoinedLiteral(index: Int, opening: Boolean): Boolean {
                    val joinedIndex = start + index
                    return joinedLiterals.ranges.containsOffset(joinedIndex) ||
                        joinedLiterals.isMarkdownContainerLine(
                            joinedIndex,
                            includeStandaloneLazyTags = opening && !followsClosedWrapper,
                        )
                }
                val cleaned = cleanReply(
                    part, streaming, interrupted,
                    requirePair = requirePair, formatOutput = formatOutput,
                    projectedDuplicateOpenings = projectedDuplicateOpenings, projectedInnerOpenings = projectedInnerOpenings,
                    allowedOpenings = allowedOpenings,
                    literalOutput = literalOutput, trailerHeadings = trailerHeadings, cleanExposedBody = cleanExposedBody,
                    isJoinedLiteral = ::isJoinedLiteral,
                )
                if (first == last && !requirePair && cleaned.body == part) {
                    val selection = cleanPartHeading?.invoke(part) {
                        isJoinedLiteral(it, true) || joinedLiterals.followsLiteralHeadingLead(start + it)
                    } ?: HeadingSelection(part.indices)
                    val body = part.substring(selection.body)
                    val unwrapped = if (!cleaned.couldSpanToolCalls && !cleaned.matchedOuterPair &&
                        !explainsPreviousExample(last + 1)
                    ) {
                        cleanPartOrphan?.invoke(body) {
                            val localIndex = selection.body.first + it
                            isJoinedLiteral(localIndex, true) || joinedLiterals.isLazyListItemLine(start + localIndex)
                        } ?: body
                    } else body
                    return cleaned.copy(body = part.substring(0, selection.prefixEnd) + unwrapped)
                }
                return cleaned
            }
            return buildString {
                var first = 0
                var appendedPart = false
                while (first < parts.size) {
                    var cleaned = cleanParts(first, first)
                    var next = first + 1
                    val followsExampleLead = (cleaned.matchedOuterPair || cleaned.couldSpanToolCalls) &&
                        joinedLiterals.matchesVisibleLine(
                            previousVisibleLine(joinedLiterals.markdown, parts[first].first), metadataExampleBridge,
                        )
                    if (cleaned.matchedOuterPair && (followsExampleLead || explainsPreviousExample(next))) {
                        cleaned = CleanedReply(text.substring(parts[first]))
                    }
                    val opening = cleaned.pendingToolOpening?.let { parts[first].first + it }
                    if (opening != null) {
                        val closingParts = toolCloses[opening].orEmpty().sortedBy { it.first }.asSequence().flatMap { close ->
                            val index = parts.binarySearchBy(close.first) { it.first }
                            val closePart = if (index < 0) -index - 2 else index
                            // An existing suffix can span calls, but plain prose after the close
                            // cannot become a recognized trailer by appending more calls.
                            val canExtend = parts[closePart].last >= close.last &&
                                canContinueReplySuffix(text, close.last + 1, parts[closePart].last + 1, trailerHeadings)
                            val limit = if (canExtend) parts.lastIndex else closePart
                            maxOf(next, closePart)..limit
                        }.distinct()
                        for (last in closingParts) {
                            val grouped = cleanParts(first, last, requirePair = true)
                            if (!grouped.matchedOuterPair && !grouped.preservedLiteralExample) continue
                            val explicitExample = followsExampleLead || explainsPreviousExample(last + 1)
                            val ambiguousContinuation = if (!explicitExample && last < parts.lastIndex) {
                                // A standalone marker at the end of a call establishes the boundary.
                                val following = cleanParts(last + 1, last + 1, followsClosedWrapper = true)
                                !following.matchedOuterPair && !following.couldSpanToolCalls &&
                                    !endsWithStandaloneClosing(text.substring(parts[last]))
                            } else false
                            cleaned = if (explicitExample || ambiguousContinuation || grouped.preservedLiteralExample) {
                                CleanedReply(text.substring(parts[first].first, parts[last].last + 1))
                            } else grouped
                            next = last + 1
                            break
                        }
                        if (followsExampleLead && next == first + 1) {
                            cleaned = CleanedReply(text.substring(parts[first]))
                        }
                    }
                    if (cleaned.body.isNotEmpty() || next == first + 1 && parts[first].isEmpty()) {
                        if (appendedPart) append('\n')
                        append(cleaned.body)
                        appendedPart = true
                    }
                    // A genuine closing tag ends that reply's quoted paragraph before the next call.
                    previousGroupClosedWrapper = cleaned.matchedOuterPair
                    first = next
                }
            }
        }
        return clean(text)
    }

    fun replyBody(
        text: String,
        streaming: Boolean = false,
        enabled: Boolean = true,
        interrupted: Boolean = false,
    ): String = if (enabled) cleanReply(text, streaming, interrupted).body else text

    private fun cleanReply(
        text: String,
        streaming: Boolean,
        interrupted: Boolean,
        requirePair: Boolean = false,
        formatOutput: FormatOutput = FormatOutput(),
        projectedDuplicateOpenings: Set<String> = emptySet(),
        projectedInnerOpenings: Map<String, Set<String>> = emptyMap(),
        isJoinedLiteral: ((index: Int, opening: Boolean) -> Boolean)? = null,
        allowedOpenings: Set<String>? = null,
        literalOutput: LiteralOutput = LiteralOutput(),
        trailerHeadings: List<String> = emptyList(),
        cleanExposedBody: (String) -> String = { it },
    ): CleanedReply {
        val first = text.indexOfFirst { !it.isWhitespace() }
        if (first < 0) return CleanedReply(text)
        val leadingStatus by lazy(LazyThreadSafetyMode.NONE) {
            classifyLiterals(text, interpretProseQuotes = false).fences.filter { it.topLevel && it.legacyStatus }
        }
        var openingStart = first
        while (openingStart < text.length) {
            openingStart = when {
                text.startsWith(commentOpening, openingStart) && !hasCodeIndent(text, openingStart) ->
                    closedCommentEnd(text, openingStart) ?: return CleanedReply(text)
                text.startsWith("```", openingStart) && MarkdownCodeFences.legacyStatusLabel in text -> {
                    val line = lineStart(text, openingStart)
                    val status = leadingStatus.firstOrNull { it.range.first == line } ?: break
                    status.range.last + 1
                }
                else -> break
            }
            while (openingStart < text.length && text[openingStart].isWhitespace()) openingStart++
        }
        if (openingStart == text.length || hasCodeIndent(text, openingStart)) return CleanedReply(text)
        val leadingContent = if (openingStart == first) "" else text.substring(0, openingStart)
        val start = text.substring(openingStart)
        val candidates = if (allowedOpenings == null) wrappers else wrappers.filter { it.opening in allowedOpenings }
        val wrapper = candidates.firstOrNull { start.startsWith(it.opening) }
        if (wrapper == null) {
            val identifiable = start.startsWith("<历史回复 模式=") || start.startsWith("<聚焦模式=")
            if (!requirePair && (streaming || identifiable || interrupted && start.length > 1) &&
                candidates.any { it.opening.startsWith(start) } && formatOutput.declaredOpenings.none { it.startsWith(start) } &&
                literalOutput.openings.none { it.startsWith(start) } &&
                isJoinedLiteral?.invoke(openingStart, true) != true
            ) return CleanedReply(leadingContent)
            return CleanedReply(text)
        }
        if (isJoinedLiteral?.invoke(openingStart, true) == true) return CleanedReply(text)
        val afterOpening = start.substring(wrapper.opening.length)
        val body = afterOpening.trimStart('\r', '\n')
        val bodyStart = text.length - body.length
        val end = body.trimEnd()
        val familyTags = wrapperFamilyTokens.getValue(wrapper.name).findAll(end).toList()
        val literals by lazy(LazyThreadSafetyMode.NONE) {
            classifyLiterals(end, familyTags, wrapper.closings, includeUnclosed = streaming || interrupted)
        }
        val rawVisibleTags = familyTags.filter { tag ->
            literals.isVisibleWrapperTag(end, tag, allowIndentedParagraphTag = true) &&
                (tag.value !in wrapper.closings || !literals.isCaptionedLazyListClosing(tag.range.first)) &&
                !literals.isRequestedClosingLine(tag.range.first, tag.value, literalOutput.closings) &&
                isJoinedLiteral?.invoke(bodyStart + tag.range.first, false) != true
        }
        val suffixRanges by lazy(LazyThreadSafetyMode.NONE) {
            literals.replySuffixRanges(formatOutput, streaming || interrupted)
        }
        val trailerHeading by lazy(LazyThreadSafetyMode.NONE) {
            if (trailerHeadings.none { it in body }) null else headingBeforeTrailer(
                body, trailerHeadings, streaming || interrupted, formatOutput, literals, suffixRanges,
            ) { isJoinedLiteral?.invoke(bodyStart + it, true) == true }
        }
        // A sent heading may separate a genuine end marker from the recognized status or action trailer.
        fun headingAfterClose(suffixStart: Int): IntRange? = trailerHeading?.takeIf {
            suffixStart <= it.first && body.subSequence(suffixStart, it.first).isBlank()
        }
        fun bodyBeforeClose(position: Int): String {
            val trimmed = body.substring(0, position).trimEnd()
            val trailingLiteral = literals.roleplayLiteralCode.firstOrNull {
                trimmed.lastIndex in it && trimmed.length in it
            } ?: return trimmed
            val limit = minOf(position, trailingLiteral.last + 1)
            var endOfContent = trimmed.length
            while (endOfContent < limit && !body[endOfContent].isHistoryLineBreak()) endOfContent++
            return body.substring(0, endOfContent)
        }
        fun endsReply(tag: MatchResult): Boolean =
            tag.range.last + 1 == end.length ||
                hasOnlyReplySuffix(
                    body, tag.range.last + 1, suffixRanges, literals, streaming || interrupted,
                    formatOutput,
                ) || headingAfterClose(tag.range.last + 1) != null

        val visibleTags = if (literalOutput.openings.isEmpty() && literalOutput.closings.isEmpty() ||
            !afterOpening.firstOrNull().isHistoryLineBreak()
        ) rawVisibleTags else {
            val last = rawVisibleTags.lastOrNull()
            val terminalClose = last?.takeIf { it.value in wrapper.closings && endsReply(it) }
            val ordinaryClose = wrapperBoundary(wrapper, rawVisibleTags.asSequence()).closing
            val requestedClose = rawVisibleTags.firstOrNull { tag ->
                tag.value in literalOutput.closings && literals.isStandaloneTagLine(tag.range.first, tag.value) &&
                    (lineStart(body, tag.range.first) == 0 || terminalClose != null &&
                        tag == ordinaryClose && tag != terminalClose)
            }
            val withoutRequestedCloses = rawVisibleTags.filterNot { it == requestedClose }
            val requestedOpen = if (terminalClose != null &&
                wrapperBoundary(wrapper, withoutRequestedCloses.asSequence()).closing == null
            ) withoutRequestedCloses.singleOrNull { tag ->
                tag.value in literalOutput.openings && literals.isStandaloneTagLine(tag.range.first, tag.value)
            } else null
            withoutRequestedCloses.filterNot { it == requestedOpen }
        }

        val terminal = visibleTags.lastOrNull()?.takeIf { tag ->
            tag.value in wrapper.closings && !literals.isLazyQuoteLine(tag.range.first) && endsReply(tag)
        }
        // A proven close outside the Markdown container leaves its earlier lazy lines literal.
        val outsideContainer = terminal?.let {
            val outsideList = !literals.isLazyListItemLine(it.range.first)
            wrapperBoundary(wrapper, visibleTags.asSequence().filterNot { tag ->
                tag.value in wrapper.closings && (literals.isLazyQuoteLine(tag.range.first) ||
                    outsideList && literals.isLazyListItemLine(tag.range.first))
            }).takeIf { it.closing == terminal }
        }
        val boundary = outsideContainer ?: wrapperBoundary(wrapper, visibleTags.asSequence())
        val closing = boundary.closing
        val exactAuthoredPair = closing != null && literalOutput.pairs.isNotEmpty() &&
            text.substring(openingStart, bodyStart + closing.range.last + 1) in literalOutput.pairs
        val pendingAuthoredPair = closing == null && (streaming || interrupted) &&
            body.isNotBlank() && literalOutput.pairs.any { it.startsWith(start.trimEnd()) }
        if (exactAuthoredPair || pendingAuthoredPair) {
            return CleanedReply(
                text, matchedOuterPair = closing?.let(::endsReply) == true,
                pendingToolOpening = openingStart.takeIf { closing == null },
            )
        }
        val declared = wrapper.opening in formatOutput.declaredOpenings &&
            !(wrapper.opening in projectedDuplicateOpenings &&
                openingStart == first && afterOpening.firstOrNull().isHistoryLineBreak() &&
                (closing != null || streaming || interrupted) &&
                wrapper.matchesProjectedDuplicate(end, visibleTags))
        val witnessedDoubleTransport = wrapper != legacyWrapper && wrapper.opening !in formatOutput.declaredOpenings &&
            wrapper.opening in projectedDuplicateOpenings && afterOpening.firstOrNull().isHistoryLineBreak() &&
            (wrapper.matchesProjectedDuplicate(end, visibleTags) &&
                (end.length == wrapper.opening.length || end.getOrNull(wrapper.opening.length).isHistoryLineBreak()) &&
                visibleTags.drop(1).all { end.getOrNull(it.range.first - 1).isHistoryLineBreak() } ||
                (streaming || interrupted) && end.length < wrapper.opening.length &&
                wrapper.opening.startsWith(end) && isJoinedLiteral?.invoke(bodyStart, true) != true)

        fun removeInnerTransport(inner: String, requireInnerPair: Boolean): String {
            val witnessedInner = if (witnessedDoubleTransport) wrapper else wrappers.firstOrNull {
                it.opening in projectedInnerOpenings[wrapper.opening].orEmpty() &&
                    it.opening !in formatOutput.declaredOpenings &&
                    afterOpening.firstOrNull().isHistoryLineBreak() &&
                    (if (requireInnerPair) it.isStandalonePair(inner) else
                        it.opening.startsWith(inner) || inner.startsWith(it.opening) &&
                            (inner.length == it.opening.length || inner.getOrNull(it.opening.length).isHistoryLineBreak()))
            } ?: return inner
            val result = cleanReply(
                inner, streaming, interrupted, requirePair = requireInnerPair, formatOutput = formatOutput,
                isJoinedLiteral = isJoinedLiteral?.let { joined -> { index, opening -> joined(bodyStart + index, opening) } },
                allowedOpenings = setOf(witnessedInner.opening), literalOutput = literalOutput,
                trailerHeadings = trailerHeadings,
                cleanExposedBody = cleanExposedBody,
            )
            return if (result.matchedOuterPair) result.body + inner.takeLastWhile(Char::isWhitespace) else result.body
        }

        if (closing != null) {
            val position = closing.range.first
            val afterClosing = closing.range.last + 1
            // A complete leading pair followed by visible prose is an example.
            if (!endsReply(closing)) {
                return CleanedReply(
                    text,
                    pendingToolOpening = openingStart,
                    preservedLiteralExample = literals.matchesVisibleLine(
                        nextNonblankLine(literals.markdown, afterClosing), historyExampleExplanation,
                    ) && body.subSequence(afterClosing, lineEnd(body, afterClosing)).isBlank(),
                )
            }
            if (declared) return CleanedReply(text, matchedOuterPair = true)
            if (afterClosing == end.length) {
                return CleanedReply(
                    leadingContent + cleanExposedBody(
                        removeInnerTransport(bodyBeforeClose(position), requireInnerPair = true)
                    ),
                    matchedOuterPair = true,
                )
            }
            val tail = headingAfterClose(afterClosing)?.let { heading ->
                body.substring(afterClosing, heading.first).trimEnd('\r', '\n') + body.substring(heading.last + 1)
            } ?: body.substring(afterClosing)
            val markerLineStart = lineStart(body, position)
            val markerStartsLine = body.subSequence(markerLineStart, position).isBlank()
            val suffixSharesLine = tail.lineSequence().first().isNotBlank()
            val before = if (markerStartsLine && suffixSharesLine) {
                body.substring(0, markerLineStart)
            } else {
                bodyBeforeClose(position)
            }
            val exposed = removeInnerTransport(before, requireInnerPair = true)
            val cleaned = cleanExposedBody(exposed)
            val separator = if (markerStartsLine && suffixSharesLine &&
                cleaned.isNotEmpty() && !cleaned.last().isHistoryLineBreak()
            ) exposed.takeLastWhile { it.isHistoryLineBreak() } else ""
            return CleanedReply(
                leadingContent + cleaned + separator + tail,
                matchedOuterPair = true,
            )
        }

        if (declared || requirePair) return CleanedReply(text, pendingToolOpening = openingStart)
        val sameLineContent = end.isNotEmpty() && !afterOpening.firstOrNull().isHistoryLineBreak()
        val lastTag = end.lastIndexOf('<')
        if (lastTag >= 0 && boundary.canEndWithPartialClose) {
            val suffix = end.substring(lastTag)
            val identifiable = suffix.startsWith("</${wrapper.name}")
            val aloneOnLine = end.substring(0, lastTag).substringAfterLast('\n').substringAfterLast('\r').isBlank()
            // Same-line prose can explain the opener. A fully identified closing prefix confirms the pair.
            if ((!sameLineContent || lastTag == 0 || identifiable) &&
                (streaming || identifiable || interrupted && (suffix.length > 1 || aloneOnLine)) &&
                wrapper.closings.any { suffix.length < it.length && it.startsWith(suffix) } &&
                !isEscaped(end, lastTag) && !literals.isMarkdownContainerLine(lastTag) &&
                !literals.isCaptionedLazyListClosing(lastTag) &&
                !literals.isRequestedClosingLine(lastTag, suffix, literalOutput.closings) &&
                !(afterOpening.firstOrNull().isHistoryLineBreak() && lineStart(body, lastTag) == 0 &&
                    literals.isStandaloneTagLine(lastTag, suffix) &&
                    (literalOutput.openings + literalOutput.closings).any { it.startsWith(suffix) }) &&
                !literals.ranges.containsOffset(lastTag) && !literals.hasCodeIndentAt(lastTag) &&
                isJoinedLiteral?.invoke(bodyStart + lastTag, false) != true
            ) {
                val before = bodyBeforeClose(lastTag)
                val cleaned = if (streaming || interrupted) removeInnerTransport(before, requireInnerPair = false) else before
                return CleanedReply(leadingContent + cleanExposedBody(cleaned), pendingToolOpening = openingStart)
            }
        }
        if (wrapper.opening in literalOutput.openings &&
            (afterOpening.isEmpty() || afterOpening.first().isHistoryLineBreak()) &&
            visibleTags.none { it.value in wrapper.closings }
        ) {
            val firstBodyLine = body.subSequence(0, lineEnd(body, 0)).trim()
            val alreadyContainsRequestedOpening = firstBodyLine.isNotEmpty() &&
                literalOutput.openings.any { it.startsWith(firstBodyLine) }
            if (!alreadyContainsRequestedOpening) return CleanedReply(text, pendingToolOpening = openingStart)
        }
        val cleaned = if (streaming || interrupted) removeInnerTransport(body, requireInnerPair = false) else body
        return CleanedReply(
            if (sameLineContent) text else leadingContent + cleanExposedBody(cleaned),
            pendingToolOpening = openingStart,
        )
    }

    private fun Wrapper.matchesProjectedDuplicate(body: String, tags: List<MatchResult>): Boolean {
        val openingTag = tags.firstOrNull() ?: return false
        if (openingTag.range.first != 0 || openingTag.value != opening) return false
        val closingTags = tags.drop(1)
        if (closingTags.size > 2 || closingTags.any { it.value !in closings }) return false
        val innerClose = closingTags.firstOrNull() ?: return true
        val outerClose = closingTags.getOrNull(1)
        val tail = body.subSequence(innerClose.range.last + 1, outerClose?.range?.first ?: body.length)
        if (outerClose != null) return tail.isBlank() && tail.any { it.isHistoryLineBreak() }
        if (tail.isBlank()) return true

        // A pending outer close can follow the inner pair, but prose invalidates the duplicate shape.
        val prefix = tail.trimStart()
        return tail.take(tail.length - prefix.length).any { it.isHistoryLineBreak() } &&
            closings.any { it.startsWith(prefix) }
    }

    private fun wrapperBoundary(wrapper: Wrapper, tags: Sequence<MatchResult>): WrapperBoundary {
        var innerDepth = 0
        for (tag in tags) {
            if (tag.value.startsWith("</")) {
                if (innerDepth > 0) {
                    innerDepth--
                } else {
                    return WrapperBoundary(tag.takeIf { it.value in wrapper.closings }, canEndWithPartialClose = false)
                }
            } else if (!tag.value.dropLast(1).trimEnd().endsWith('/')) {
                innerDepth++
            }
        }
        return WrapperBoundary(closing = null, canEndWithPartialClose = innerDepth == 0)
    }

    private fun toolClosesByOpening(
        text: String, families: Map<String, List<MatchResult>>, literals: ReplyLiterals,
        literalOutput: LiteralOutput,
    ): Map<Int, Set<IntRange>> {
        val matches = mutableMapOf<Int, MutableSet<IntRange>>()
        val optionalContinuationLines = listOf(
            emptySet(), literals.lazyQuoteLineStarts, literals.lazyQuoteLineStarts + literals.lazyListItemLineStarts,
        ).distinct()
        val preserveClosingChoices = if (literalOutput.closings.isEmpty()) listOf(false) else listOf(false, true)
        fun record(open: MatchResult, close: MatchResult) {
            val wrapper = wrappers.firstOrNull { it.opening == open.value } ?: return
            if (close.value in wrapper.closings) matches.getOrPut(open.range.first, ::mutableSetOf).add(close.range)
        }
        for (tags in families.values) {
            val visible = tags.filter { literals.isVisibleWrapperTag(text, it, allowIndentedParagraphTag = true) }
            for (ignoredClosings in optionalContinuationLines) {
                for (preserveRequestedClosing in preserveClosingChoices) {
                    val openings = ArrayDeque<MatchResult>()
                    val skippedClosingFor = mutableSetOf<Int>()
                    for (tag in visible) {
                        if (tag.value.startsWith("</")) {
                            if (literals.isCaptionedLazyListClosing(tag.range.first) ||
                                literals.isRequestedClosingLine(tag.range.first, tag.value, literalOutput.closings) ||
                                lineStart(literals.markdown, tag.range.first) in ignoredClosings
                            ) continue
                            val open = openings.lastOrNull() ?: continue
                            if (preserveRequestedClosing && tag.value in literalOutput.closings &&
                                literals.isStandaloneTagLine(tag.range.first, tag.value) &&
                                skippedClosingFor.add(open.range.first)
                            ) continue
                            openings.removeLast()
                            record(open, tag)
                            // This close may end the preceding outer wrapper when its apparent
                            // nested opener was requested literal text. The candidate still needs
                            // to pass the regular boundary check on its exact span.
                            val parent = openings.lastOrNull()
                            if (parent != null && open.value in literalOutput.openings &&
                                literals.isStandaloneTagLine(open.range.first, open.value)
                            ) record(parent, tag)
                        } else if (!tag.value.dropLast(1).trimEnd().endsWith('/')) {
                            openings.addLast(tag)
                        }
                    }
                }
            }
        }
        return matches
    }

    private fun canContinueReplySuffix(
        text: String, from: Int, limit: Int, trailerHeadings: List<String>,
    ): Boolean {
        val start = (from until limit).firstOrNull { !text[it].isWhitespace() } ?: return false
        // Only a real comment opener can introduce an angle-prefixed trailer across call boundaries.
        val startsComment = limit - start >= commentOpening.length && text.startsWith(commentOpening, start)
        return startsComment || text[start] in "`-—―─－" || trailerHeadings.any { it.first() == text[start] }
    }

    private fun ReplyLiterals.isVisibleWrapperTag(
        text: String,
        tag: MatchResult,
        includeStandaloneLazyTags: Boolean = false,
        allowIndentedParagraphTag: Boolean = false,
    ): Boolean {
        val position = tag.range.first
        val quote = text.getOrNull(position - 1)
        val asciiQuoted = (quote == '"' || quote == '\'') && text.getOrNull(tag.range.last + 1) == quote
        return !asciiQuoted && !isEscaped(text, position) && !ranges.containsOffset(position) &&
            !isMarkdownContainerLine(position, includeStandaloneLazyTags) && !hasCodeIndentAt(position, allowIndentedParagraphTag)
    }

    private fun ReplyLiterals.hasCodeIndentAt(offset: Int, allowIndentedParagraphTag: Boolean = true): Boolean {
        val start = lineStart(markdown, offset)
        val startsParagraphLine = allowIndentedParagraphTag && start in indentedParagraphLineStarts &&
            markdown.subSequence(start, offset).isBlank()
        return !startsParagraphLine && hasCodeIndent(markdown, offset)
    }

    private fun ReplyLiterals.isLazyQuoteLine(offset: Int): Boolean =
        lineStart(markdown, offset) in lazyQuoteLineStarts

    private fun ReplyLiterals.isLazyListItemLine(offset: Int): Boolean =
        lineStart(markdown, offset) in lazyListItemLineStarts

    private fun ReplyLiterals.isCaptionedLazyListClosing(offset: Int): Boolean {
        val start = lineStart(markdown, offset)
        if (start !in lazyListItemLineStarts || markdown.subSequence(start, offset).isNotBlank()) return false
        val lead = previousVisibleLine(markdown, start) ?: return false
        return literalClosingTagLead.containsMatchIn(markdown.substring(lead))
    }

    private fun ReplyLiterals.isRequestedClosingLine(offset: Int, token: String, requested: Set<String>): Boolean {
        if (requested.none { it.startsWith(token) } || !isStandaloneTagLine(offset, token)) return false
        val start = lineStart(markdown, offset)
        val caption = previousVisibleLine(markdown, start) ?: return false
        return markdown.substring(caption.last + 1, start) in setOf("\n", "\r\n") &&
            visibleLine(caption)?.let(literalClosingTagLead::containsMatchIn) == true
    }

    private fun ReplyLiterals.isStandaloneTagLine(offset: Int, token: String): Boolean {
        if (!hasMarkerPadding(markdown, offset)) return false
        val end = offset + token.length
        return markdown.subSequence(end, lineEnd(markdown, end)).isBlank()
    }

    private fun ReplyLiterals.isMarkdownContainerLine(offset: Int, includeStandaloneLazyTags: Boolean = false): Boolean {
        val start = lineStart(markdown, offset)
        if (start in listItemLineStarts || MarkdownCodeFences.hasQuoteContainerPrefix(markdown, start)) return true
        if (start !in lazyQuoteLineStarts) return false
        // A standalone closing marker can still end a wrapper after a quoted paragraph.
        return includeStandaloneLazyTags || (start until offset).any { index ->
            !markdown[index].isWhitespace() && commentRanges.none { index in it }
        }
    }

    private fun ReplyLiterals.replySuffixRanges(formatOutput: FormatOutput, provisional: Boolean): List<IntRange> {
        val status = fences.filter {
            it.topLevel && it.legacyStatus || when (val info = it.statusInfo()) {
                null -> false
                "" -> it.matchesBareStatusLead(markdown, formatOutput.trailingBareStatusLeads, provisional)
                else -> info in formatOutput.trailingStatusInfos
            }
        }.map { it.range }
        val options = (roleplayAnnotatedActionRanges(markdown).asSequence() +
            roleplayListedActionRanges(markdown)).filter { range ->
            !ranges.containsOffset(range.first) && !ranges.containsOffset(range.last) &&
                !isMarkdownContainerLine(range.first) && !isMarkdownContainerLine(range.last)
        }.toList()
        return (commentRanges + status + options).sortedBy { it.first }
    }

    private fun hasOnlyReplySuffix(
        text: String,
        start: Int,
        suffixRanges: List<IntRange>,
        literals: ReplyLiterals,
        provisional: Boolean,
        formatOutput: FormatOutput,
        requireTrailer: Boolean = false,
    ): Boolean {
        var next = start
        var foundTrailer = false
        for (range in suffixRanges) {
            if (range.first < start || range.last < next) continue
            if (range.first > next && !text.subSequence(next, range.first).isBlank()) break
            next = range.last + 1
            if (requireTrailer && range !in literals.commentRanges) foundTrailer = true
        }
        val remainder = text.substring(next).trimStart()
        if (remainder.isEmpty()) return !requireTrailer || foundTrailer
        if (provisional && commentOpening.startsWith(remainder.trimEnd())) return !requireTrailer || foundTrailer
        if (!provisional && !formatOutput.declaresAnnotatedActions) return false

        val position = text.length - remainder.length
        val line = lineStart(text, position)
        val indent = text.subSequence(line, position)
        if (indent.length > 3 || indent.any { it != ' ' }) return false

        // A status opener can arrive a token at a time. A newline already committed to ordinary code stays literal.
        if (provisional && (MarkdownCodeFences.legacyStatusOpening.startsWith(remainder) ||
                formatOutput.trailingStatusInfos.any { "```$it".startsWith(remainder, ignoreCase = true) } ||
                formatOutput.trailingBareStatusLeads.isNotEmpty() && "```".startsWith(remainder))
        ) return true
        if (!literals.isUnprotected(position)) return false
        val action = literals.markdown.substring(line) + text.substring(literals.markdown.length)
        return formatOutput.declaresAnnotatedActions && isPendingAnnotatedRoleplayActionOpening(action) ||
            provisional && isPendingRoleplayActionSuffix(action)
    }

    internal fun isStandaloneLegacyHistoryWrapper(text: String): Boolean {
        if (!legacyWrapper.isStandalonePair(text)) return false
        return cleanReply(text, streaming = false, interrupted = false, requirePair = true).matchedOuterPair
    }

    private fun Char.isPlainPreviewCharacter(): Boolean =
        isLetterOrDigit() || this in " .,!?;:，。！？；：、"

    private fun Char?.isHistoryLineBreak(): Boolean = this == '\n' || this == '\r'

    private fun Char.isHorizontalPadding(): Boolean = this == ' ' || this == '\t'

    private fun hasMarkerPadding(text: String, position: Int): Boolean {
        if (position < 0) return false
        val start = lineStart(text, position)
        return position - start <= 3 && (start until position).all { text[it] == ' ' }
    }

    private fun endsWithStandaloneClosing(text: String): Boolean {
        val end = text.trimEnd()
        val offset = end.lastIndexOf('<')
        return hasMarkerPadding(end, offset) && end.substring(offset) in wrapperClosings
    }

    /** Closed comments hidden in the bubble. Code and escaped comment openers are literal. */
    internal fun hiddenHtmlCommentRanges(text: String): List<IntRange> =
        if (commentOpening !in text) emptyList() else classifyLiterals(text, interpretProseQuotes = false).commentRanges

    /** Preserve Markdown code while keeping legacy status fences eligible for roleplay parsing. */
    internal fun roleplayLiteralCodeRanges(text: String): List<IntRange> {
        if ('`' !in text && '~' !in text && '\t' !in text && "    " !in text && cdataOpening !in text) {
            return emptyList()
        }
        val code = classifyLiterals(text, interpretProseQuotes = false).roleplayLiteralCode
        return buildList {
            for (range in code.sortedBy { it.first }) {
                val previous = lastOrNull()
                if (previous != null && (previous.last + 1 until range.first).all { text[it].isWhitespace() }) {
                    this[lastIndex] = previous.first..maxOf(previous.last, range.last)
                } else {
                    add(range)
                }
            }
        }
    }

    private fun classifyLiterals(
        end: String,
        familyTags: List<MatchResult> = emptyList(),
        closings: List<String> = emptyList(),
        includeUnclosed: Boolean = false,
        interpretProseQuotes: Boolean = true,
    ): ReplyLiterals {
        val markdown = bareCarriageReturn.replace(end, "\n")
        val comments = mutableListOf<IntRange>()
        val cdata = mutableListOf<IntRange>()
        val pendingMarkup = mutableListOf<LiteralMarkup>()
        val hasMarkup = commentOpening in markdown || cdataOpening in markdown
        val markupCanChangeMarkdown = hasMarkup && ('`' in markdown || markdownContainerOrIndent.containsMatchIn(markdown))

        fun inspect(source: String): ReplyLiterals {
            val lazyQuoteLineStarts = mutableSetOf<Int>()
            val containerLineStarts = mutableSetOf<Int>()
            val listItemLineStarts = mutableSetOf<Int>()
            val lazyListItemLineStarts = mutableSetOf<Int>()
            val indentedParagraphLineStarts = mutableSetOf<Int>()
            val indentedCode = mutableListOf<IntRange>()
            val fences = MarkdownCodeFences.blocks(
                source,
                onLazyQuoteLine = { lazyQuoteLineStarts += it },
                onIndentedCodeLine = { indentedCode += it },
                onContainerLine = { if (hasMarkup) containerLineStarts += it },
                onListItemLine = { listItemLineStarts += it },
                onIndentedParagraphLine = { indentedParagraphLineStarts += it },
                onLazyListItemLine = { lazyListItemLineStarts += it },
            )
            val codeBlocks = indentedCode + fences.map { it.range }
            val inlineCode = inlineCodeRangesOutsideBlocks(source, codeBlocks, indentedParagraphLineStarts)
            val literalFences = fences.filter { fence ->
                val statusKind = statusFenceInfo.matchEntire(fence.info)?.groupValues?.get(1)
                val nestedPlainCode = !fence.topLevel &&
                    (fence.info.isEmpty() || statusKind.equals("text", ignoreCase = true))
                fence.marker == '~' || fence.markerLength > 3 || nestedPlainCode ||
                    fence.info.isNotEmpty() && statusKind == null
            }
            val roleplayLiteralCode = indentedCode + inlineCode + literalFences.map { it.range } + cdata
            val ranges = (codeBlocks + inlineCode + comments + cdata).indexedRanges()
            val dialogue = if (interpretProseQuotes && ('[' in end || '［' in end)) {
                roleplaySpokenDialogueRanges(end, ranges, includeUnclosed) { isEscaped(end, it) }
            } else emptyList()
            val quoteDelimitersToSkip = (ranges + familyTags.map { it.range }).indexedRanges()
            val interiorClosings = if (includeUnclosed) familyTags.mapIndexedNotNull { index, tag ->
                val position = tag.range.first
                val linePrefix = source.subSequence(lineStart(source, position), position)
                val nextTag = familyTags.getOrNull(index + 1)?.range?.first ?: end.length
                val continuationLines = end.subSequence(tag.range.last + 1, nextTag).lineSequence().iterator()
                position.takeIf {
                    tag.value in closings && linePrefix.isBlank() &&
                        !hasCodeIndent(source, position, indentedParagraphLineStarts) &&
                        !ranges.containsOffset(position) && continuationLines.next().isBlank() &&
                        continuationLines.hasNext() && hanIdeograph.containsMatchIn(continuationLines.next())
                }
            } else emptyList()
            val collectMarkup = { markup: LiteralMarkup ->
                pendingMarkup += markup
                !markupCanChangeMarkdown ||
                    canBatchMarkup(source, markup, containerLineStarts, lazyQuoteLineStarts, listItemLineStarts)
            }
            val quotes = proseQuoteRanges(
                end, source, quoteDelimitersToSkip, includeUnclosed, interiorClosings, collectMarkup.takeIf { hasMarkup },
                interpretProseQuotes, dialogue,
            )
            return ReplyLiterals(
                (ranges + quotes + dialogue).indexedRanges(), roleplayLiteralCode, lazyQuoteLineStarts, listItemLineStarts, lazyListItemLineStarts,
                indentedParagraphLineStarts, source, comments, fences,
            )
        }

        var result = inspect(markdown)
        while (pendingMarkup.isNotEmpty()) {
            for (markup in pendingMarkup) {
                if (markup.isComment) comments += markup.range else cdata += markup.range
            }
            pendingMarkup.clear()
            // A batch stops when masking a range can make Markdown reinterpret later text.
            result = inspect(maskLiteralMarkup(markdown, comments + cdata))
        }
        return result
    }

    private fun canBatchMarkup(
        text: String,
        markup: LiteralMarkup,
        containers: Set<Int>,
        lazyQuotes: Set<Int>,
        listItems: Set<Int>,
    ): Boolean {
        val range = markup.range
        val containsBackticks = range.any { text[it] == '`' }
        val multiline = range.any { text[it] == '\n' }
        // Backtick runs can only pair inside their paragraph. A standalone, isolated
        // one-line comment cannot affect a different paragraph when its runs are masked.
        if (containsBackticks && multiline) return false
        val start = lineStart(text, range.first)
        val prefix = text.subSequence(start, range.first)
        if (multiline || containsBackticks) {
            if (!markup.isComment || !prefix.isBlank() || start in containers || start in lazyQuotes) return false
            val end = text.indexOf('\n', range.last + 1).let { if (it < 0) text.length else it }
            if (!text.subSequence(range.last + 1, end).isBlank()) return false
            // Blank lines isolate a plain, standalone note from adjacent Markdown paragraphs.
            val blankBefore = start == 0 || text.subSequence(lineStart(text, start - 1), start - 1).isBlank()
            val nextEnd = text.indexOf('\n', end + 1).let { if (it < 0) text.length else it }
            val blankAfter = end == text.length || text.subSequence(end + 1, nextEnd).isBlank()
            return blankBefore && blankAfter && !markdownContainerOrIndent.containsMatchIn(text.subSequence(start, end))
        }
        if (prefix.trimStart().firstOrNull()?.isLetter() == true) return true
        // Masking one inline note after visible list prose leaves its marker, indentation, and
        // paragraph state intact. Empty items and notes containing block or code syntax still rescan.
        if (markup.isComment && start in listItems &&
            MarkdownCodeFences.hasListProseBefore(text, start, range.first)
        ) return true
        if (!prefix.isBlank() || start in containers || start in lazyQuotes) return false
        val after = text.indexOf('\n', range.last + 1).let { if (it < 0) text.length else it }
        if (!text.subSequence(range.last + 1, after).isBlank()) return false
        // A blank line cannot change a following top-level prose line or a run of standalone notes.
        val next = text.getOrNull(after + 1)
        return next == null || next == '\n' || next.isLetter() ||
            text.startsWith(commentOpening, after + 1) || text.startsWith(cdataOpening, after + 1)
    }

    private fun List<IntRange>.indexedRanges(): List<IntRange> = buildList {
        for (range in this@indexedRanges.sortedBy { it.first }) {
            if (range.isEmpty()) continue
            val previous = lastOrNull()
            if (previous != null && range.first <= previous.last) {
                this[lastIndex] = previous.first..maxOf(previous.last, range.last)
            } else add(range)
        }
    }

    private fun List<IntRange>.containsOffset(offset: Int): Boolean {
        val index = binarySearchBy(offset, selector = IntRange::first)
        return index >= 0 || -index - 2 >= 0 && offset <= this[-index - 2].last
    }

    private fun inlineCodeRangesOutsideBlocks(
        body: String,
        codeBlocks: List<IntRange>,
        indentedParagraphLineStarts: Set<Int>,
    ): List<IntRange> {
        if ('`' !in body) return emptyList()
        val boundaries = (codeBlocks + paragraphBreak.findAll(body).map { it.range }.toList())
            .sortedBy { it.first }
        return buildList {
            var start = 0
            for (boundary in boundaries) {
                if (start < boundary.first) {
                    addAll(inlineCodeRanges(body, start, boundary.first, indentedParagraphLineStarts))
                }
                start = maxOf(start, boundary.last + 1)
            }
            if (start < body.length) addAll(inlineCodeRanges(body, start, body.length, indentedParagraphLineStarts))
        }
    }

    private fun inlineCodeRanges(
        text: String,
        start: Int,
        end: Int,
        indentedParagraphLineStarts: Set<Int>,
    ): List<IntRange> = buildList {
        val runs = backtickRuns.findAll(text, start).takeWhile { it.range.first < end }.toList()
        var index = 0
        while (index < runs.size) {
            val opening = runs[index++]
            val position = opening.range.first
            if (hasCodeIndent(text, position, indentedParagraphLineStarts)) continue
            val unescapedStart = if (isEscaped(text, position)) position + 1 else position
            val length = opening.range.last + 1 - unescapedStart
            if (length == 0) continue
            // A span closes with the next run of equal length. Backslashes inside it are literal.
            val close = (index until runs.size).firstOrNull { runs[it].value.length == length } ?: continue
            add(unescapedStart..runs[close].range.last)
            index = close + 1
        }
    }

    private fun proseQuoteRanges(
        text: String,
        markdown: String,
        delimiterExclusions: List<IntRange>,
        includeUnclosed: Boolean,
        interiorClosings: List<Int>,
        onMarkup: ((LiteralMarkup) -> Boolean)?,
        interpretProseQuotes: Boolean,
        dialogue: List<IntRange>,
    ): List<IntRange> = buildList {
        var open = -1
        var closing: Char? = null
        var depth = 0
        var canOpenComment = onMarkup != null
        var markupEnd = 0
        fun isProseDelimiter(index: Int): Boolean =
            !isEscaped(text, index) && !delimiterExclusions.containsOffset(index) && dialogue.none { index in it }

        for (index in text.indices) {
            if (index < markupEnd) continue
            if (canOpenComment && markdown.startsWith(commentOpening, index) &&
                !isEscaped(text, index) && !delimiterExclusions.containsOffset(index)
            ) {
                val end = closedCommentEnd(text, index)
                val inQuote = MarkdownCodeFences.hasQuoteContainerPrefix(markdown, lineStart(markdown, index))
                if (end != null || includeUnclosed && open < 0 && !inQuote) {
                    markupEnd = end ?: text.length
                    if (onMarkup?.invoke(LiteralMarkup(index until markupEnd, isComment = true)) != true) break
                    continue
                }
                // The body renderer treats the rest of a completed, unclosed comment as ordinary text.
                if (open < 0 && !inQuote) canOpenComment = false
            }
            if (onMarkup != null && open < 0 && markdown.startsWith(cdataOpening, index) && isProseDelimiter(index)) {
                val close = text.indexOf(cdataClosing, index + cdataOpening.length)
                val inQuote = MarkdownCodeFences.hasQuoteContainerPrefix(markdown, lineStart(markdown, index))
                if (close >= 0 || includeUnclosed && !inQuote) {
                    markupEnd = if (close >= 0) close + cdataClosing.length else text.length
                    if (!onMarkup(LiteralMarkup(index until markupEnd, isComment = false))) break
                    continue
                }
            }
            if (!interpretProseQuotes) continue
            val symbol = text[index]
            val matchingClose = proseDelimiters[symbol]
            if (matchingClose == null && symbol != closing) continue
            if (!isProseDelimiter(index)) continue
            val followsWord = text.getOrNull(index - 1)?.isLetterOrDigit() == true
            val precedesWord = text.getOrNull(index + 1)?.isLetterOrDigit() == true
            // Apostrophes in contractions and possessives do not start a prose quote.
            if (symbol == '\'' && followsWord && (precedesWord || open < 0)) continue
            if (open < 0 && !followsWord && (symbol == '\'' || symbol == '‘') &&
                leadingApostropheContraction.matchesAt(text, index + 1) &&
                !hasContractionQuoteClose(text, index, symbol, ::isProseDelimiter)
            ) continue
            if (symbol == '’' && followsWord && (isWordApostrophe(text, index) ||
                    isCurlyPossessive(text, open, index, interiorClosings, ::isProseDelimiter))
            ) continue
            if (symbol == '"' && open < 0 && text.getOrNull(index - 1)?.isDigit() == true && !precedesWord) continue
            when {
                open < 0 && matchingClose != null -> {
                    open = index
                    closing = matchingClose
                    depth = 1
                }
                symbol == closing -> {
                    if (--depth == 0) {
                        add(open..index)
                        open = -1
                        closing = null
                    }
                }
                open >= 0 && symbol == text[open] -> depth++
            }
        }
        if (includeUnclosed && open >= 0) add(open..text.lastIndex)
    }

    private fun hasContractionQuoteClose(
        text: String,
        opening: Int,
        marker: Char,
        isProseDelimiter: (Int) -> Boolean,
    ): Boolean {
        val closing = proseDelimiters.getValue(marker)
        val next = (opening + 1 until text.length).firstOrNull { index ->
            (text[index] == marker || text[index] == closing) &&
                !isWordApostrophe(text, index) && isProseDelimiter(index)
        } ?: return false
        if (text[next] != closing) return false
        // For straight quotes, a mark before a new word can start the next quotation.
        return marker != closing || !text[next - 1].isWhitespace() ||
            text.getOrNull(next + 1)?.isLetterOrDigit() != true
    }

    private fun isCurlyPossessive(
        text: String,
        quoteStart: Int,
        position: Int,
        interiorClosings: List<Int>,
        isProseDelimiter: (Int) -> Boolean,
    ): Boolean {
        if (!text[position - 1].equals('s', ignoreCase = true) ||
            text.getOrNull(position + 1)?.isWhitespace() != true
        ) return false
        val wordStart = (position + 2 until text.length).firstOrNull { !text[it].isWhitespace() } ?: return false
        if (!text[wordStart].isLetter()) return false
        val nextQuote = (position + 1 until text.length).firstOrNull { index ->
            (text[index] == '‘' || text[index] == '’' && !isWordApostrophe(text, index)) &&
                isProseDelimiter(index)
        }
        if (nextQuote != null) return text[nextQuote] == '’'
        // Require a speech cue before widening a quote with an ambiguous English closing mark.
        if (!chineseSpeechLead.containsMatchIn(text.subSequence(lineStart(text, quoteStart), quoteStart))) return false
        if (!unfinishedPluralPossessor.matches(text.subSequence(quoteStart + 1, position))) return false
        val tagLine = interiorClosings.firstOrNull { it > wordStart }?.let { lineStart(text, it) } ?: return false
        if (tagLine <= wordStart || lineStart(text, wordStart) != lineStart(text, position)) return false
        val possessed = text.substring(wordStart, tagLine).trimEnd()
        return unfinishedPossessedWord.matches(possessed) && possessed !in ordinaryProseFollowers && !possessed.endsWith("ly")
    }

    private fun isWordApostrophe(text: String, position: Int): Boolean =
        text.getOrNull(position - 1)?.isLetterOrDigit() == true &&
            text.getOrNull(position + 1)?.isLetterOrDigit() == true

    private fun maskLiteralMarkup(source: String, markup: List<IntRange>): String {
        val masked = source.toCharArray()
        val leadingMarkup = mutableMapOf<Int, IntRange>()
        for (range in markup.sortedBy { it.first }) {
            var start = lineStart(source, range.first)
            while (start <= range.last) {
                val end = source.indexOf('\n', start).takeIf { it >= 0 } ?: source.length
                val first = maxOf(start, range.first)
                val limit = minOf(end, range.last + 1)
                if (first < limit && (start until first).all { source[it] == ' ' || source[it] == '\t' }) {
                    leadingMarkup.putIfAbsent(start, first until end)
                }
                for (offset in first until limit) {
                    if (masked[offset] != '\r') masked[offset] = ' '
                }
                start = end + 1
            }
        }
        for (line in leadingMarkup.values) {
            // Preserve the original indentation of visible content on mixed lines. Markup-only
            // lines stay blank so they cannot turn the following indented code into a paragraph.
            if (line.any { !masked[it].isWhitespace() }) masked[line.first] = '\uFFFC'
        }
        return String(masked)
    }

    private fun closedCommentEnd(text: String, start: Int): Int? {
        var depth = 1
        for (token in commentDelimiters.findAll(text, start + commentOpening.length)) {
            if (token.value == commentOpening) depth++
            else if (--depth == 0) return token.range.last + 1
        }
        return null
    }

    private fun isEscaped(text: String, position: Int): Boolean {
        var preceding = position
        while (preceding > 0 && text[preceding - 1] == '\\') preceding--
        return (position - preceding) % 2 != 0
    }

    private fun lineStart(text: String, offset: Int): Int {
        var index = minOf(text.length, offset) - 1
        while (index >= 0 && !text[index].isHistoryLineBreak()) index--
        return index + 1
    }

    private fun lineEnd(text: String, offset: Int): Int =
        text.indexOfAny(charArrayOf('\r', '\n'), offset).takeIf { it >= 0 } ?: text.length

    private fun hasCodeIndent(text: String, offset: Int, indentedParagraphLineStarts: Set<Int> = emptySet()): Boolean {
        val lineStart = lineStart(text, offset)
        if (lineStart in indentedParagraphLineStarts) return false
        val contentStart = MarkdownCodeFences.afterQuotePrefixes(text, lineStart)
        val indent = text.substring(contentStart.coerceAtMost(offset), offset).takeWhile { it == ' ' || it == '\t' }
        return indent.length >= 4 || '\t' in indent
    }
}
