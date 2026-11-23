package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatNarrativeMode

/** Removes request-only history wrappers when the model echoes them around its new reply. */
object NarrativeHistoryEcho {
    private data class Wrapper(val name: String, val opening: String, val closings: List<String>)
    private data class WrapperBoundary(val closing: MatchResult?, val canEndWithPartialClose: Boolean)
    private data class CleanedReply(val body: String, val matchedOuterPair: Boolean = false)
    private data class ReplyLiterals(
        val ranges: List<IntRange>,
        val roleplayLiteralCode: List<IntRange>,
        val lazyQuoteLineStarts: Set<Int>,
        val listItemLineStarts: Set<Int>,
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
    private val formatTemplateLabel = Regex(
        " {0,3}(\\*\\*|)(?:(?:本轮)?输出(?:模板|格式)|本轮的固定输出模板|请按以下格式输出|" +
            "每次回复必须使用此固定模板)[ \\t]*[:：]\\1[ \\t]*",
    )
    private val formatTemplateAffirmation = Regex(
        "[ \\t]*(?:（[ \\t]*标签是固定输出的一部分[ \\t]*）|\\([ \\t]*标签是固定输出的一部分[ \\t]*\\))[ \\t]*[。.]?[ \\t]*",
    )
    private val fencedTemplateLead = Regex(
        "(?m)^ {0,3}请严格按照以下固定模板作答，必须逐字保留首尾标签，不要输出代码围栏[:：]" +
            "[ \\t]*(?:\\r?\\n[ \\t]*)+\\z",
    )
    private val formatCounterexampleLead = Regex(
        " {0,3}(?:以下是禁止输出的反例[。.:：]?|以下标签仅供举例，不要在回复中输出[:：])[ \\t]*",
    )
    private val formatCounterexampleTail = Regex(" {0,3}不得输出上面的标签[。.]?[ \\t]*")
    private val statusFenceInfo = Regex("(?:text|status)(?=$|[ \\t(（]).*", RegexOption.IGNORE_CASE)
    private val paragraphBreak = Regex("\\r?\\n[ \\t]*\\r?\\n")
    private val bareCarriageReturn = Regex("\\r(?!\\n)")
    private val proseDelimiters = mapOf(
        '「' to '」', '『' to '』', '｢' to '｣', '“' to '”', '‘' to '’', '"' to '"', '\'' to '\'',
    )
    private val unfinishedPluralPossessor = Regex("(?:the|these|those|our|your|their)[ \\t]+[a-z]{2,}s")
    private val unfinishedPossessedWord = Regex("[a-z]{2,}")
    private val chineseSpeechLead = Regex("[说說][ \\t]*[:：]?[ \\t]*$")
    private val hanIdeograph = Regex("[\\u3400-\\u4DBF\\u4E00-\\u9FFF\\uF900-\\uFAFF]")
    private val ordinaryProseFollowers = setOf(
        "after", "again", "all", "already", "aloud", "and", "as", "away", "before", "but",
        "elsewhere", "for", "here", "in", "instead", "later", "now", "or", "outside", "so",
        "then", "there", "through", "to", "today", "tomorrow", "until", "when", "where", "while",
        "with", "without", "yesterday",
    )

    internal class RequestCleanup internal constructor(
        private val enabled: Boolean,
        private val declaredOpenings: Set<String> = emptySet(),
    ) {
        val declaresLegacyHistoryWrapper: Boolean
            get() = legacyWrapper.opening in declaredOpenings

        fun completedBody(
            text: String,
            interrupted: Boolean = false,
            partLengths: List<Int> = emptyList(),
            streaming: Boolean = false,
        ): String = if (enabled) {
            cleanCompletedBody(text, declaredOpenings, interrupted, partLengths, streaming)
        } else text
    }

    internal fun forRequest(narrativeMode: ChatNarrativeMode?, formatCard: String?): RequestCleanup =
        if (narrativeMode == null) RequestCleanup(enabled = false)
        else RequestCleanup(enabled = true, declaredOpenings = formatCard?.let(::declaredOpenings).orEmpty())

    private fun declaredOpenings(formatCard: String): Set<String> {
        val candidates = wrappers.filter { it.opening in formatCard }
        if (candidates.isEmpty()) return emptySet()
        val tokens = candidates.map { it.name }.distinct().flatMap { name ->
            wrapperFamilyTokens.getValue(name).findAll(formatCard).toList()
        }.sortedBy { it.range.first }
        val literals = classifyLiterals(formatCard, tokens)
        val markup = tokens.filter {
            literals.isVisibleWrapperTag(formatCard, it, includeStandaloneLazyTags = true)
        }
        return buildSet {
            var matchedEnd = -1
            for ((index, opening) in markup.withIndex()) {
                val start = opening.range.first
                if (start <= matchedEnd) continue
                val wrapper = candidates.firstOrNull { it.opening == opening.value } ?: continue
                val prefixStart = lineStart(formatCard, start)
                val explicitLabel = formatTemplateLabel.matches(literals.markdown.subSequence(prefixStart, start))
                if (!explicitLabel && !formatCard.subSequence(prefixStart, start).isBlank()) continue
                val family = wrapperFamilyTokens.getValue(wrapper.name)
                val closing = wrapperBoundary(
                    wrapper, markup.asSequence().drop(index + 1).filter { family.matches(it.value) },
                ).closing ?: continue
                matchedEnd = closing.range.last
                val following = literals.markdown.substring(matchedEnd + 1).lineSequence().iterator()
                val suffix = following.next()
                if (!suffix.isBlank() && !(explicitLabel && formatTemplateAffirmation.matches(suffix))) continue

                val before = previousVisibleLine(literals.markdown, prefixStart)
                val after = following.asSequence().firstOrNull { it.isNotBlank() }
                if (before?.let(formatCounterexampleLead::matches) == true ||
                    after?.let(formatCounterexampleTail::matches) == true
                ) continue
                add(wrapper.opening)
            }
            for (fence in literals.fences) {
                val closingLine = fence.closingLineStart ?: continue
                if (!fence.topLevel || !fence.info.equals("xml", ignoreCase = true)) continue
                val lead = fencedTemplateLead.find(literals.markdown.subSequence(0, fence.range.first)) ?: continue
                if (literals.ranges.any { lead.range.first in it }) continue
                val contentStart = literals.markdown.indexOf('\n', fence.range.first) + 1
                val template = formatCard.substring(contentStart, closingLine).trim()
                val wrapper = candidates.firstOrNull {
                    template.startsWith(it.opening) && it.closings.any(template::endsWith)
                } ?: continue
                if (cleanReply(template, streaming = false, interrupted = false, requirePair = true).matchedOuterPair) {
                    add(wrapper.opening)
                }
            }
        }
    }

    private fun previousVisibleLine(markdown: String, before: Int): CharSequence? =
        markdown.subSequence(0, before).lineSequence().lastOrNull { it.isNotBlank() }

    fun completedBody(
        text: String,
        enabled: Boolean,
        interrupted: Boolean = false,
        partLengths: List<Int> = emptyList(),
        streaming: Boolean = false,
    ): String = if (enabled) {
        cleanCompletedBody(text, emptySet(), interrupted, partLengths, streaming)
    } else text

    private fun cleanCompletedBody(
        text: String,
        declaredOpenings: Set<String>,
        interrupted: Boolean,
        partLengths: List<Int>,
        streaming: Boolean,
    ): String {
        fun clean(part: String) = cleanReply(part, streaming, interrupted, declaredOpenings = declaredOpenings).body
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
                text, streaming, interrupted, requirePair = true, declaredOpenings = declaredOpenings,
            )
            if (wholeReply.matchedOuterPair) return wholeReply.body
            // Independent wrappers are still eligible, except where the joined Markdown makes a tag literal.
            val joinedLiterals by lazy(LazyThreadSafetyMode.NONE) {
                val tags = wrapperFamilyTokens.values.flatMap { it.findAll(text).toList() }.sortedBy { it.range.first }
                classifyLiterals(text, tags, wrappers.flatMap { it.closings }, includeUnclosed = streaming || interrupted)
            }
            var previousPartClosedWrapper = false
            return parts.joinToString("\n") { part ->
                val cleaned = cleanReply(
                    text.substring(part.first, part.last + 1), streaming, interrupted,
                    declaredOpenings = declaredOpenings,
                    isJoinedLiteral = { index, opening ->
                        val joinedIndex = part.first + index
                        joinedLiterals.ranges.any { joinedIndex in it } ||
                            joinedLiterals.isMarkdownContainerLine(
                                joinedIndex,
                                includeStandaloneLazyTags = opening && !previousPartClosedWrapper,
                            )
                    },
                )
                // A genuine closing tag ends that reply's quoted paragraph before the next call.
                previousPartClosedWrapper = cleaned.matchedOuterPair
                cleaned.body
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
        declaredOpenings: Set<String> = emptySet(),
        isJoinedLiteral: ((index: Int, opening: Boolean) -> Boolean)? = null,
    ): CleanedReply {
        val first = text.indexOfFirst { !it.isWhitespace() }
        if (first < 0) return CleanedReply(text)
        val leadingStatus by lazy(LazyThreadSafetyMode.NONE) {
            classifyLiterals(text, interpretProseQuotes = false).fences.filter { it.topLevel && it.legacyStatus }
        }
        var openingStart = first
        while (openingStart < text.length) {
            openingStart = when {
                text.startsWith(commentOpening, openingStart) && !isIndentedCodeLine(text, openingStart) ->
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
        if (openingStart == text.length || isIndentedCodeLine(text, openingStart)) return CleanedReply(text)
        val leadingContent = if (openingStart == first) "" else text.substring(0, openingStart)
        val start = text.substring(openingStart)
        val wrapper = wrappers.firstOrNull { start.startsWith(it.opening) }
        if (wrapper == null) {
            val identifiable = start.startsWith("<历史回复 模式=") || start.startsWith("<聚焦模式=")
            if (!requirePair && (streaming || identifiable || interrupted && start.length > 1) &&
                wrappers.any { it.opening.startsWith(start) } && declaredOpenings.none { it.startsWith(start) } &&
                isJoinedLiteral?.invoke(openingStart, true) != true
            ) return CleanedReply(leadingContent)
            return CleanedReply(text)
        }
        if (isJoinedLiteral?.invoke(openingStart, true) == true) return CleanedReply(text)
        val declared = wrapper.opening in declaredOpenings

        val afterOpening = start.substring(wrapper.opening.length)
        val body = afterOpening.trimStart('\r', '\n')
        val bodyStart = text.length - body.length
        val end = body.trimEnd()
        val familyTags = wrapperFamilyTokens.getValue(wrapper.name).findAll(end).toList()
        val literals by lazy(LazyThreadSafetyMode.NONE) {
            classifyLiterals(end, familyTags, wrapper.closings, includeUnclosed = streaming || interrupted)
        }
        val visibleTags = familyTags.filter { tag ->
            literals.isVisibleWrapperTag(end, tag) && isJoinedLiteral?.invoke(bodyStart + tag.range.first, false) != true
        }
        val suffixRanges by lazy(LazyThreadSafetyMode.NONE) {
            val status = literals.fences.filter { it.topLevel && it.legacyStatus }.map { it.range }
            (literals.commentRanges + status).sortedBy { it.first }
        }
        fun endsReply(tag: MatchResult): Boolean =
            tag.range.last + 1 == end.length ||
                hasOnlyReplySuffix(end, tag.range.last + 1, suffixRanges, streaming || interrupted)

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
        if (closing != null) {
            val position = closing.range.first
            val afterClosing = closing.range.last + 1
            // A complete leading pair followed by visible prose is an example.
            if (!endsReply(closing)) {
                return CleanedReply(text)
            }
            if (declared) return CleanedReply(text, matchedOuterPair = true)
            if (afterClosing == end.length) {
                return CleanedReply(leadingContent + end.substring(0, position).trimEnd(), matchedOuterPair = true)
            }
            val tail = body.substring(afterClosing)
            val markerLineStart = lineStart(body, position)
            val markerStartsLine = body.subSequence(markerLineStart, position).isBlank()
            val suffixSharesLine = tail.lineSequence().first().isNotBlank()
            val before = if (markerStartsLine && suffixSharesLine) {
                body.substring(0, markerLineStart)
            } else {
                body.substring(0, position).trimEnd()
            }
            return CleanedReply(leadingContent + before + tail, matchedOuterPair = true)
        }

        if (declared || requirePair) return CleanedReply(text)
        val sameLineContent = end.isNotEmpty() && !afterOpening.firstOrNull().isHistoryLineBreak()
        val lastTag = end.lastIndexOf('<')
        if (lastTag >= 0 && (!sameLineContent || lastTag == 0) && boundary.canEndWithPartialClose) {
            val suffix = end.substring(lastTag)
            val identifiable = suffix.startsWith("</历史回复") || suffix.startsWith("</聚焦模式")
            val aloneOnLine = end.substring(0, lastTag).substringAfterLast('\n').substringAfterLast('\r').isBlank()
            if ((streaming || identifiable || interrupted && (suffix.length > 1 || aloneOnLine)) &&
                wrapper.closings.any { suffix.length < it.length && it.startsWith(suffix) } &&
                !isEscaped(end, lastTag) && !literals.isMarkdownContainerLine(lastTag) &&
                literals.ranges.none { lastTag in it } && !isIndentedCodeLine(literals.markdown, lastTag) &&
                isJoinedLiteral?.invoke(bodyStart + lastTag, false) != true
            ) return CleanedReply(leadingContent + end.substring(0, lastTag).trimEnd())
        }
        return CleanedReply(if (sameLineContent) text else leadingContent + body)
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
    ): Boolean {
        val position = tag.range.first
        val quote = text.getOrNull(position - 1)
        val asciiQuoted = (quote == '"' || quote == '\'') && text.getOrNull(tag.range.last + 1) == quote
        return !asciiQuoted && !isEscaped(text, position) && ranges.none { position in it } &&
            !isMarkdownContainerLine(position, includeStandaloneLazyTags) && !isIndentedCodeLine(markdown, position)
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

    private fun hasOnlyReplySuffix(
        text: String, start: Int, suffixRanges: List<IntRange>, provisional: Boolean,
    ): Boolean {
        var next = start
        for (range in suffixRanges) {
            if (range.first < start) continue
            if (!text.subSequence(next, range.first).isBlank()) return false
            next = range.last + 1
        }
        val remainder = text.substring(next).trimStart()
        return remainder.isEmpty() || provisional && commentOpening.startsWith(remainder)
    }

    internal fun isStandaloneLegacyHistoryWrapper(text: String): Boolean {
        if (!text.startsWith(legacyWrapper.opening) ||
            !text.getOrNull(legacyWrapper.opening.length).isHistoryLineBreak() ||
            legacyWrapper.closings.none { text.endsWith(it) && text.getOrNull(text.length - it.length - 1).isHistoryLineBreak() }
        ) return false
        return cleanReply(text, streaming = false, interrupted = false, requirePair = true).matchedOuterPair
    }

    private fun Char?.isHistoryLineBreak(): Boolean = this == '\n' || this == '\r'

    /** Closed comments hidden in the bubble. Code and escaped comment openers are literal. */
    internal fun hiddenHtmlCommentRanges(text: String): List<IntRange> =
        if (commentOpening !in text) emptyList() else classifyLiterals(text, interpretProseQuotes = false).commentRanges

    /** Bare, text and status backtick fences stay eligible for roleplay parsing. */
    internal fun roleplayLiteralCodeRanges(text: String): List<IntRange> {
        if ('`' !in text && '~' !in text && '\t' !in text && "    " !in text) return emptyList()
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
        var nextComment: IntRange? = null

        fun inspect(source: String, onComment: ((IntRange) -> Unit)?): ReplyLiterals {
            val lazyQuoteLineStarts = mutableSetOf<Int>()
            val listItemLineStarts = mutableSetOf<Int>()
            val indentedCode = mutableListOf<IntRange>()
            val fences = MarkdownCodeFences.blocks(
                source,
                onLazyQuoteLine = { lazyQuoteLineStarts += it },
                onIndentedCodeLine = { indentedCode += it },
                onListItemLine = { listItemLineStarts += it },
            )
            val codeBlocks = indentedCode + fences.map { it.range }
            val inlineCode = inlineCodeRangesOutsideBlocks(source, codeBlocks)
            val literalFences = fences.filter { fence ->
                fence.marker == '~' || fence.info.isNotEmpty() && !statusFenceInfo.matches(fence.info)
            }
            val roleplayLiteralCode = indentedCode + inlineCode + literalFences.map { it.range }
            val ranges = codeBlocks + inlineCode + comments
            val quoteDelimitersToSkip = ranges + familyTags.map { it.range }
            val interiorClosings = if (includeUnclosed) familyTags.mapIndexedNotNull { index, tag ->
                val position = tag.range.first
                val linePrefix = source.subSequence(lineStart(source, position), position)
                val nextTag = familyTags.getOrNull(index + 1)?.range?.first ?: end.length
                val continuationLines = end.subSequence(tag.range.last + 1, nextTag).lineSequence().iterator()
                position.takeIf {
                    tag.value in closings && linePrefix.isBlank() && !isIndentedCodeLine(source, position) &&
                        ranges.none { position in it } && continuationLines.next().isBlank() &&
                        continuationLines.hasNext() && hanIdeograph.containsMatchIn(continuationLines.next())
                }
            } else emptyList()
            val quotes = proseQuoteRanges(
                end, source, quoteDelimitersToSkip, includeUnclosed, interiorClosings, onComment,
                interpretProseQuotes,
            )
            return ReplyLiterals(
                ranges + quotes, roleplayLiteralCode, lazyQuoteLineStarts, listItemLineStarts, source, comments, fences,
            )
        }

        val capture = { range: IntRange -> nextComment = range }
        var result = inspect(markdown, capture.takeIf { commentOpening in markdown })
        while (true) {
            val range = nextComment ?: break
            comments += range
            nextComment = null
            // A comment can itself contain a fence opener, so rescan the masked Markdown.
            result = inspect(maskHtmlComments(markdown, comments), capture)
        }
        return result
    }

    private fun inlineCodeRangesOutsideBlocks(body: String, codeBlocks: List<IntRange>): List<IntRange> {
        if ('`' !in body) return emptyList()
        val boundaries = (codeBlocks + paragraphBreak.findAll(body).map { it.range }.toList())
            .sortedBy { it.first }
        return buildList {
            var start = 0
            for (boundary in boundaries) {
                if (start < boundary.first) addAll(inlineCodeRanges(body, start, boundary.first))
                start = maxOf(start, boundary.last + 1)
            }
            if (start < body.length) addAll(inlineCodeRanges(body, start, body.length))
        }
    }

    private fun inlineCodeRanges(text: String, start: Int, end: Int): List<IntRange> = buildList {
        val runs = backtickRuns.findAll(text, start).takeWhile { it.range.first < end }.toList()
        var index = 0
        while (index < runs.size) {
            val opening = runs[index++]
            val position = opening.range.first
            if (isIndentedCodeLine(text, position)) continue
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
        onComment: ((IntRange) -> Unit)?,
        interpretProseQuotes: Boolean,
    ): List<IntRange> = buildList {
        var open = -1
        var closing: Char? = null
        var depth = 0
        var canOpenComment = onComment != null
        fun isProseDelimiter(index: Int): Boolean =
            !isEscaped(text, index) && delimiterExclusions.none { index in it }

        for (index in text.indices) {
            if (canOpenComment && text.startsWith(commentOpening, index) &&
                !isEscaped(text, index) && delimiterExclusions.none { index in it }
            ) {
                val end = closedCommentEnd(text, index)
                val inQuote = MarkdownCodeFences.hasQuoteContainerPrefix(markdown, lineStart(markdown, index))
                if (end != null || includeUnclosed && open < 0 && !inQuote) {
                    onComment?.invoke(index until (end ?: text.length))
                    break
                }
                // The body renderer treats the rest of a completed, unclosed comment as ordinary text.
                if (open < 0 && !inQuote) canOpenComment = false
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

    private fun maskHtmlComments(source: String, comments: List<IntRange>): String {
        val masked = source.toCharArray()
        val leadingComments = mutableMapOf<Int, IntRange>()
        for (comment in comments) {
            var start = lineStart(source, comment.first)
            while (start <= comment.last) {
                val end = source.indexOf('\n', start).takeIf { it >= 0 } ?: source.length
                val first = maxOf(start, comment.first)
                val limit = minOf(end, comment.last + 1)
                if (first < limit && (start until first).all { source[it] == ' ' || source[it] == '\t' }) {
                    leadingComments.putIfAbsent(start, first until end)
                }
                for (offset in first until limit) {
                    if (masked[offset] != '\r') masked[offset] = ' '
                }
                start = end + 1
            }
        }
        for (line in leadingComments.values) {
            // Preserve the original indentation of visible content on mixed lines. Comment-only
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

    private fun isIndentedCodeLine(text: String, offset: Int): Boolean {
        val lineStart = lineStart(text, offset)
        val contentStart = MarkdownCodeFences.afterQuotePrefixes(text, lineStart)
        val indent = text.substring(contentStart.coerceAtMost(offset), offset).takeWhile { it == ' ' || it == '\t' }
        return indent.length >= 4 || '\t' in indent
    }
}
