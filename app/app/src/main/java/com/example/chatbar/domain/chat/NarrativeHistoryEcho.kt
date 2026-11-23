package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatNarrativeMode

/** Removes request-only history wrappers when the model echoes them around its new reply. */
object NarrativeHistoryEcho {
    private data class Wrapper(val name: String, val opening: String, val closings: List<String>)
    private data class WrapperBoundary(val closing: MatchResult?, val canEndWithPartialClose: Boolean)
    private data class CleanedReply(
        val body: String,
        val matchedOuterPair: Boolean = false,
        val couldSpanToolCalls: Boolean = false,
    )
    private data class LiteralMarkup(val range: IntRange, val isComment: Boolean)
    private data class ReplyLiterals(
        val ranges: List<IntRange>,
        val roleplayLiteralCode: List<IntRange>,
        val lazyQuoteLineStarts: Set<Int>,
        val listItemLineStarts: Set<Int>,
        val indentedParagraphLineStarts: Set<Int>,
        val markdown: String,
        val commentRanges: List<IntRange>,
        val fences: List<MarkdownCodeFences.Block>,
    )

    private val legacyWrapper = Wrapper("聚焦模式", "<聚焦模式=\"沉浸\">", listOf("</聚焦模式>", "</聚焦模式=\"沉浸\">"))
    private val wrappers = ChatNarrativeMode.entries.map { mode ->
        Wrapper("历史回复", "<历史回复 模式=\"${mode.shortLabel}\">", listOf("</历史回复>"))
    } + legacyWrapper
    private val wrapperFamilyTokens = wrappers.associate { wrapper ->
        val name = Regex.escape(wrapper.name)
        wrapper.name to Regex("</?$name(?=[\\s=>/])[^<>]*>")
    }
    private val backtickRuns = Regex("`+")
    private const val commentOpening = "<!--"
    private val commentDelimiters = Regex("<!--|-->")
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
            "(?:keep|preserve)[ \\t]+(?:both|the)[ \\t]+(?:opening and closing[ \\t]+)?tags[ \\t]+verbatim)"
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
        "(?:^|[，。；,.;][ \\t]*|[ \\t]+and[ \\t]+)$omitTemplateFence[。.]?[ \\t]*$",
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
    )

    internal class RequestCleanup internal constructor(
        private val enabled: Boolean,
        private val formatOutput: FormatOutput = FormatOutput(),
        private val formatCard: String? = null,
        private val repairOpenings: Set<String>? = null,
        private val repairLiteralSources: List<String> = emptyList(),
    ) {
        private val projectedDuplicateOpenings = mutableSetOf<String>()
        private val projectedClosings = mutableSetOf<String>()
        private val narrativeHeadings = mutableSetOf<String>()
        private var currentUserText: String? = null
        private var authoredPairs: Set<String> = emptySet()

        fun recordNarrativePrompt(prompt: String, userText: String?) {
            prompt.lineSequence().firstOrNull()?.takeIf(String::isNotBlank)?.let(narrativeHeadings::add)
            if (currentUserText != userText) {
                currentUserText = userText
                authoredPairs = declaredUserPairs(userText)
            }
        }

        private fun isAuthored(token: String): Boolean =
            formatCard?.contains(token) == true || currentUserText?.contains(token) == true

        val declaresLegacyHistoryWrapper: Boolean
            get() = legacyWrapper.opening in formatOutput.declaredOpenings

        /** Record double framing that entered this request around a stored single pair. */
        fun recordHistoryProjection(body: String, projection: String) {
            if (projection == body && isStandaloneLegacyHistoryWrapper(projection)) {
                projectedClosings += legacyWrapper.closings.first { projection.endsWith(it) }
            }
            for (wrapper in wrappers) {
                val declaredSinglePair = wrapper.opening in formatOutput.singlePairOpenings
                if (projection != "${wrapper.opening}\n$body\n${wrapper.closings.first()}") continue
                projectedClosings += wrapper.closings.first()
                if (wrapper.opening in projectedDuplicateOpenings) continue
                val undeclaredTransportPair = wrapper != legacyWrapper &&
                    wrapper.opening !in formatOutput.declaredOpenings && wrapper.isStandalonePair(body)
                if (declaredSinglePair && wrapper.hasExactlyOnePair(body) ||
                    undeclaredTransportPair && wrapper.hasExactlyOneVisiblePair(body)
                ) projectedDuplicateOpenings += wrapper.opening
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
                return withoutOrphanClosing(text, repairClosings, provisional, formatOutput) { partial ->
                    repairLiteralSources.any { partial in it }
                }
            }

            val headings = narrativeHeadings.filterNot(::isAuthored)
            val reply = withoutEchoedHeading(text, headings, provisional, ::isAuthored)
            val body = text.substring(reply)
            val lengths = if (reply == text.indices) partLengths else partLengths.trimTo(reply, text.length)
            val cleaned = cleanCompletedBody(
                body, formatOutput, interrupted, lengths, streaming, projectedDuplicateOpenings,
                authoredPairs = authoredPairs,
                cleanExposedBody = { inner ->
                    inner.substring(withoutEchoedHeading(inner, headings, provisional, ::isAuthored))
                },
            )
            if (cleaned != body) return cleaned
            val closings = projectedClosings.filterNot(::isAuthored)
            return withoutOrphanClosing(body, closings, provisional, formatOutput, ::isAuthored)
        }
    }

    private fun declaredUserPairs(text: String?): Set<String> {
        if (text == null || wrappers.none { it.opening in text }) return emptySet()
        return buildSet {
            for (family in wrapperFamilyTokens.values) {
                val tags = family.findAll(text).toList()
                for ((index, tag) in tags.withIndex()) {
                    val wrapper = wrappers.firstOrNull { it.opening == tag.value } ?: continue
                    if (lineStart(text, tag.range.first) != tag.range.first) continue
                    val closing = wrapperBoundary(wrapper, tags.asSequence().drop(index + 1)).closing ?: continue
                    val end = closing.range.last + 1
                    if (lineStart(text, closing.range.first) != closing.range.first ||
                        (end < text.length && !text[end].isHistoryLineBreak())
                    ) continue
                    val pair = text.substring(tag.range.first, end)
                    if (wrapper.isStandalonePair(pair)) add(pair)
                }
            }
        }
    }

    internal fun forRequest(narrativeMode: ChatNarrativeMode?, formatCard: String?): RequestCleanup =
        if (narrativeMode == null) RequestCleanup(enabled = false)
        else RequestCleanup(
            enabled = true, formatOutput = formatCard?.let(::declaredOutput) ?: FormatOutput(), formatCard = formatCard,
        )

    internal fun forFormatRepair(formatCard: String?, original: String, userText: String? = null): RequestCleanup {
        val output = formatCard?.let(::declaredOutput) ?: FormatOutput()
        val openings = wrappers.filter { wrapper ->
            wrapper.opening in output.counterexampleOpenings && wrapper.opening !in output.declaredOpenings &&
                wrapper.opening !in original && wrapper.closings.none { it in original } &&
                userText?.contains(wrapper.opening) != true &&
                wrapper.closings.none { userText?.contains(it) == true }
        }.mapTo(mutableSetOf(), Wrapper::opening)
        return RequestCleanup(
            enabled = openings.isNotEmpty(), formatOutput = output, repairOpenings = openings,
            repairLiteralSources = listOfNotNull(original, userText),
        )
    }

    private fun withoutEchoedHeading(
        text: String, headings: List<String>, provisional: Boolean, isAuthored: (String) -> Boolean,
    ): IntRange {
        if (headings.isEmpty() || text.isBlank()) return text.indices
        val literals by lazy(LazyThreadSafetyMode.NONE) { classifyLiterals(text, includeUnclosed = provisional) }
        fun matches(line: String, allowPrefix: Boolean): Boolean = headings.any { heading ->
            line == heading || allowPrefix && heading.startsWith(line) &&
                line.startsWith(heading.substringBefore('（').removeSuffix("】")) && !isAuthored(line)
        }

        var bodyStart = 0
        while (bodyStart < text.length) {
            val start = (bodyStart until text.length).firstOrNull { !text[it].isHistoryLineBreak() } ?: break
            val end = text.indexOfAny(charArrayOf('\r', '\n'), start).takeIf { it >= 0 } ?: text.length
            if (!matches(text.substring(start, end), provisional && end == text.length) ||
                !literals.isUnprotected(start)
            ) break
            bodyStart = (end until text.length).firstOrNull { !text[it].isHistoryLineBreak() } ?: text.length
        }
        var bodyEnd = text.length
        while (bodyEnd > bodyStart) {
            val end = (bodyEnd - 1 downTo bodyStart).firstOrNull { !text[it].isWhitespace() }?.plus(1) ?: break
            val start = lineStart(text, end)
            if (!matches(text.substring(start, end), provisional && bodyEnd == text.length) ||
                !literals.isUnprotected(start)
            ) break
            bodyEnd = (start - 1 downTo bodyStart).firstOrNull { !text[it].isHistoryLineBreak() }?.plus(1) ?: bodyStart
        }
        return bodyStart until bodyEnd
    }

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
    ): String {
        if (closings.isEmpty() || "</" !in text) return text
        val end = text.trimEnd()
        val partial = if (provisional) end.substring(lineStart(end, end.length)) else ""
        val tags = wrappers.filter { wrapper -> wrapper.closings.any { it in closings } }
            .distinctBy(Wrapper::name)
            .flatMap { wrapperFamilyTokens.getValue(it.name).findAll(end).toList() }
            .sortedBy { it.range.first }
        val candidates = tags.filter { it.value in closings && lineStart(end, it.range.first) == it.range.first }
        val partialClosing = partial.takeIf {
            it.length > 2 && closings.any { close ->
                val name = wrappers.first { close in it.closings }.name
                it.startsWith("</${name.dropLast(1)}") && it.length < close.length && close.startsWith(it)
            } && !isAuthored(it)
        }
        if (candidates.isEmpty() && partialClosing == null) return text
        val literals = classifyLiterals(end, tags, closings, includeUnclosed = provisional)
        val visible = tags.filter { literals.isVisibleWrapperTag(end, it, includeStandaloneLazyTags = true) }
        val close = visible.singleOrNull()?.takeIf { it in candidates }
        val position = close?.range?.first ?: if (visible.isEmpty() && partialClosing != null) {
            (end.length - partialClosing.length).takeIf { literals.isUnprotected(it) } ?: return text
        } else return text
        val before = end.substring(0, position)
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
                        literals.ranges.none { line.first in it } &&
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
                val after = nextNonblankLine(literals.markdown, matchedEnd + 1)
                if (literals.isCounterexampleBefore(lead) ||
                    literals.matchesVisibleLine(after, formatCounterexampleTail)
                ) {
                    counterexampleOpenings += wrapper.opening
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
                if (literals.ranges.any { label.first in it } ||
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
                    ) counterexampleOpenings += example.opening
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
                if (fence.info.lowercase() !in formatTemplateFenceInfo ||
                    !fencedTemplateLabel.matches(labelText) && !formatTemplateLabel.matches(labelText) &&
                    !explicitReplySample && !explicitOutputFormat
                ) continue
                val wrapper = candidates.firstOrNull {
                    template.startsWith(it.opening) && it.closings.any(template::endsWith)
                } ?: continue
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
            counterexampleOpenings,
        )
    }

    private fun requiresUnfencedTags(instruction: String?): Boolean =
        instruction != null && keepSampleTags.containsMatchIn(instruction) && omitSampleFence.containsMatchIn(instruction)

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

    private fun ReplyLiterals.isUnprotected(position: Int): Boolean =
        ranges.none { position in it } && !isMarkdownContainerLine(position, includeStandaloneLazyTags = true)

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
        allowedOpenings: Set<String>? = null,
        authoredPairs: Set<String> = emptySet(),
        cleanExposedBody: (String) -> String = { it },
    ): String {
        fun clean(part: String) = cleanReply(
            part, streaming, interrupted, formatOutput = formatOutput,
            projectedDuplicateOpenings = projectedDuplicateOpenings,
            allowedOpenings = allowedOpenings, authoredPairs = authoredPairs, cleanExposedBody = cleanExposedBody,
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
                projectedDuplicateOpenings = projectedDuplicateOpenings,
                allowedOpenings = allowedOpenings, authoredPairs = authoredPairs, cleanExposedBody = cleanExposedBody,
            )
            if (wholeReply.matchedOuterPair) return wholeReply.body
            // Independent wrappers and groups of calls can coexist in the same tool reply.
            val joinedLiterals by lazy(LazyThreadSafetyMode.NONE) {
                val tags = wrapperFamilyTokens.values.flatMap { it.findAll(text).toList() }.sortedBy { it.range.first }
                classifyLiterals(text, tags, wrappers.flatMap { it.closings }, includeUnclosed = streaming || interrupted)
            }
            var previousGroupClosedWrapper = false
            fun cleanParts(
                first: Int,
                last: Int,
                requirePair: Boolean = false,
                followsClosedWrapper: Boolean = previousGroupClosedWrapper,
            ): CleanedReply {
                val start = parts[first].first
                return cleanReply(
                    text.substring(start, parts[last].last + 1), streaming, interrupted,
                    requirePair = requirePair, formatOutput = formatOutput,
                    projectedDuplicateOpenings = projectedDuplicateOpenings, allowedOpenings = allowedOpenings,
                    authoredPairs = authoredPairs, cleanExposedBody = cleanExposedBody,
                    isJoinedLiteral = { index, opening ->
                        val joinedIndex = start + index
                        joinedLiterals.ranges.any { joinedIndex in it } ||
                            joinedLiterals.isMarkdownContainerLine(
                                joinedIndex,
                                includeStandaloneLazyTags = opening && !followsClosedWrapper,
                            )
                    },
                )
            }
            return buildString {
                var first = 0
                while (first < parts.size) {
                    var cleaned = cleanParts(first, first)
                    var next = first + 1
                    if (cleaned.couldSpanToolCalls) {
                        for (last in next..parts.lastIndex) {
                            val grouped = cleanParts(first, last, requirePair = true)
                            if (!grouped.matchedOuterPair) continue
                            if (last < parts.lastIndex) {
                                // A plain following call may explain a tag example. Only another
                                // eligible opener establishes a boundary inside the joined body.
                                val following = cleanParts(last + 1, last + 1, followsClosedWrapper = true)
                                if (!following.matchedOuterPair && !following.couldSpanToolCalls) continue
                            }
                            cleaned = grouped
                            next = last + 1
                            break
                        }
                    }
                    if (first > 0) append('\n')
                    append(cleaned.body)
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
        isJoinedLiteral: ((index: Int, opening: Boolean) -> Boolean)? = null,
        allowedOpenings: Set<String>? = null,
        authoredPairs: Set<String> = emptySet(),
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
        val visibleTags = familyTags.filter { tag ->
            literals.isVisibleWrapperTag(end, tag, allowIndentedParagraphTag = true) &&
                isJoinedLiteral?.invoke(bodyStart + tag.range.first, false) != true
        }
        val suffixRanges by lazy(LazyThreadSafetyMode.NONE) {
            literals.replySuffixRanges(formatOutput, streaming || interrupted)
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
                )

        val terminal = visibleTags.lastOrNull()?.takeIf { tag ->
            tag.value in wrapper.closings && !literals.isLazyQuoteLine(tag.range.first) && endsReply(tag)
        }
        // A proven close outside the Markdown quote lets an earlier lazy line stay literal.
        val outsideQuote = terminal?.let {
            wrapperBoundary(wrapper, visibleTags.asSequence().filterNot { tag ->
                tag.value in wrapper.closings && literals.isLazyQuoteLine(tag.range.first)
            }).takeIf { it.closing == terminal }
        }
        val boundary = outsideQuote ?: wrapperBoundary(wrapper, visibleTags.asSequence())
        val closing = boundary.closing
        val exactAuthoredPair = closing != null && authoredPairs.isNotEmpty() &&
            text.substring(openingStart, bodyStart + closing.range.last + 1) in authoredPairs
        val pendingAuthoredPair = closing == null && (streaming || interrupted) &&
            body.isNotBlank() && authoredPairs.any { it.startsWith(start.trimEnd()) }
        if (exactAuthoredPair || pendingAuthoredPair) {
            return CleanedReply(text, matchedOuterPair = closing?.let(::endsReply) == true, couldSpanToolCalls = closing == null)
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
            if (!witnessedDoubleTransport) return inner
            val result = cleanReply(
                inner, streaming, interrupted, requirePair = requireInnerPair, formatOutput = formatOutput,
                isJoinedLiteral = isJoinedLiteral?.let { joined -> { index, opening -> joined(bodyStart + index, opening) } },
                allowedOpenings = allowedOpenings, authoredPairs = authoredPairs, cleanExposedBody = cleanExposedBody,
            )
            return if (result.matchedOuterPair) result.body + inner.takeLastWhile(Char::isWhitespace) else result.body
        }

        if (closing != null) {
            val position = closing.range.first
            val afterClosing = closing.range.last + 1
            // A complete leading pair followed by visible prose is an example.
            if (!endsReply(closing)) {
                return CleanedReply(text, couldSpanToolCalls = true)
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
            val tail = body.substring(afterClosing)
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

        if (declared || requirePair) return CleanedReply(text, couldSpanToolCalls = true)
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
                literals.ranges.none { lastTag in it } && !literals.hasCodeIndentAt(lastTag) &&
                isJoinedLiteral?.invoke(bodyStart + lastTag, false) != true
            ) {
                val before = bodyBeforeClose(lastTag)
                val cleaned = if (streaming || interrupted) removeInnerTransport(before, requireInnerPair = false) else before
                return CleanedReply(leadingContent + cleanExposedBody(cleaned), couldSpanToolCalls = true)
            }
        }
        val cleaned = if (streaming || interrupted) removeInnerTransport(body, requireInnerPair = false) else body
        return CleanedReply(if (sameLineContent) text else leadingContent + cleanExposedBody(cleaned), couldSpanToolCalls = true)
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

    private fun ReplyLiterals.isVisibleWrapperTag(
        text: String,
        tag: MatchResult,
        includeStandaloneLazyTags: Boolean = false,
        allowIndentedParagraphTag: Boolean = false,
    ): Boolean {
        val position = tag.range.first
        val quote = text.getOrNull(position - 1)
        val asciiQuoted = (quote == '"' || quote == '\'') && text.getOrNull(tag.range.last + 1) == quote
        return !asciiQuoted && !isEscaped(text, position) && ranges.none { position in it } &&
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
            ranges.none { range.first in it || range.last in it } &&
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
    ): Boolean {
        var next = start
        for (range in suffixRanges) {
            if (range.first < start || range.last < next) continue
            if (range.first > next && !text.subSequence(next, range.first).isBlank()) break
            next = range.last + 1
        }
        val remainder = text.substring(next).trimStart()
        if (remainder.isEmpty()) return true
        if (provisional && commentOpening.startsWith(remainder.trimEnd())) return true
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

    private fun Char?.isHistoryLineBreak(): Boolean = this == '\n' || this == '\r'

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
        var nextMarkup: LiteralMarkup? = null

        fun inspect(source: String, onMarkup: ((LiteralMarkup) -> Unit)?): ReplyLiterals {
            val lazyQuoteLineStarts = mutableSetOf<Int>()
            val listItemLineStarts = mutableSetOf<Int>()
            val indentedParagraphLineStarts = mutableSetOf<Int>()
            val indentedCode = mutableListOf<IntRange>()
            val fences = MarkdownCodeFences.blocks(
                source,
                onLazyQuoteLine = { lazyQuoteLineStarts += it },
                onIndentedCodeLine = { indentedCode += it },
                onListItemLine = { listItemLineStarts += it },
                onIndentedParagraphLine = { indentedParagraphLineStarts += it },
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
            val ranges = codeBlocks + inlineCode + comments + cdata
            val dialogue = if (interpretProseQuotes && ('[' in end || '［' in end)) {
                roleplaySpokenDialogueRanges(end, ranges, includeUnclosed) { isEscaped(end, it) }
            } else emptyList()
            val quoteDelimitersToSkip = ranges + familyTags.map { it.range }
            val interiorClosings = if (includeUnclosed) familyTags.mapIndexedNotNull { index, tag ->
                val position = tag.range.first
                val linePrefix = source.subSequence(lineStart(source, position), position)
                val nextTag = familyTags.getOrNull(index + 1)?.range?.first ?: end.length
                val continuationLines = end.subSequence(tag.range.last + 1, nextTag).lineSequence().iterator()
                position.takeIf {
                    tag.value in closings && linePrefix.isBlank() &&
                        !hasCodeIndent(source, position, indentedParagraphLineStarts) &&
                        ranges.none { position in it } && continuationLines.next().isBlank() &&
                        continuationLines.hasNext() && hanIdeograph.containsMatchIn(continuationLines.next())
                }
            } else emptyList()
            val quotes = proseQuoteRanges(
                end, source, quoteDelimitersToSkip, includeUnclosed, interiorClosings, onMarkup,
                interpretProseQuotes, dialogue,
            )
            return ReplyLiterals(
                ranges + quotes + dialogue, roleplayLiteralCode, lazyQuoteLineStarts, listItemLineStarts,
                indentedParagraphLineStarts, source, comments, fences,
            )
        }

        val capture = { markup: LiteralMarkup -> nextMarkup = markup }
        var result = inspect(markdown, capture.takeIf { commentOpening in markdown || cdataOpening in markdown })
        while (true) {
            val markup = nextMarkup ?: break
            if (markup.isComment) comments += markup.range else cdata += markup.range
            nextMarkup = null
            // Literal markup can itself contain a fence opener, so rescan the masked Markdown.
            result = inspect(maskLiteralMarkup(markdown, comments + cdata), capture)
        }
        return result
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
        onMarkup: ((LiteralMarkup) -> Unit)?,
        interpretProseQuotes: Boolean,
        dialogue: List<IntRange>,
    ): List<IntRange> = buildList {
        var open = -1
        var closing: Char? = null
        var depth = 0
        var canOpenComment = onMarkup != null
        fun isProseDelimiter(index: Int): Boolean =
            !isEscaped(text, index) && delimiterExclusions.none { index in it } && dialogue.none { index in it }

        for (index in text.indices) {
            if (canOpenComment && text.startsWith(commentOpening, index) &&
                !isEscaped(text, index) && delimiterExclusions.none { index in it }
            ) {
                val end = closedCommentEnd(text, index)
                val inQuote = MarkdownCodeFences.hasQuoteContainerPrefix(markdown, lineStart(markdown, index))
                if (end != null || includeUnclosed && open < 0 && !inQuote) {
                    onMarkup?.invoke(LiteralMarkup(index until (end ?: text.length), isComment = true))
                    break
                }
                // The body renderer treats the rest of a completed, unclosed comment as ordinary text.
                if (open < 0 && !inQuote) canOpenComment = false
            }
            if (onMarkup != null && open < 0 && text.startsWith(cdataOpening, index) && isProseDelimiter(index)) {
                val close = text.indexOf(cdataClosing, index + cdataOpening.length)
                val inQuote = MarkdownCodeFences.hasQuoteContainerPrefix(markdown, lineStart(markdown, index))
                if (close >= 0 || includeUnclosed && !inQuote) {
                    val end = if (close >= 0) close + cdataClosing.length else text.length
                    onMarkup(LiteralMarkup(index until end, isComment = false))
                    break
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

    private fun lineStart(text: String, offset: Int): Int =
        maxOf(text.lastIndexOf('\n', offset - 1), text.lastIndexOf('\r', offset - 1)) + 1

    private fun hasCodeIndent(text: String, offset: Int, indentedParagraphLineStarts: Set<Int> = emptySet()): Boolean {
        val lineStart = lineStart(text, offset)
        if (lineStart in indentedParagraphLineStarts) return false
        val contentStart = MarkdownCodeFences.afterQuotePrefixes(text, lineStart)
        val indent = text.substring(contentStart.coerceAtMost(offset), offset).takeWhile { it == ' ' || it == '\t' }
        return indent.length >= 4 || '\t' in indent
    }
}
