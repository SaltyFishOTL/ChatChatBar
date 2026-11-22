package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatNarrativeMode

/** Removes request-only history wrappers when the model echoes them around its new reply. */
object NarrativeHistoryEcho {
    private data class Wrapper(val name: String, val opening: String, val closings: List<String>)
    private data class ReplyLiterals(
        val ranges: List<IntRange>,
        val lazyQuoteLineStarts: Set<Int>,
        val markdown: String,
        val commentRanges: List<IntRange>,
    )

    private val wrappers = ChatNarrativeMode.entries.map { mode ->
        Wrapper("历史回复", "<历史回复 模式=\"${mode.shortLabel}\">", listOf("</历史回复>"))
    } + Wrapper("聚焦模式", "<聚焦模式=\"沉浸\">", listOf("</聚焦模式>", "</聚焦模式=\"沉浸\">"))
    private val wrapperFamilyTokens = wrappers.associate { wrapper ->
        val name = Regex.escape(wrapper.name)
        wrapper.name to Regex("</?$name(?=[\\s=>/])[^<>]*>")
    }
    private val backtickRuns = Regex("`+")
    private val commentDelimiters = Regex("<!--|-->")
    private val paragraphBreak = Regex("\\r?\\n[ \\t]*\\r?\\n")
    private val bareCarriageReturn = Regex("\\r(?!\\n)")
    private val proseDelimiters = mapOf('「' to '」', '『' to '』', '“' to '”', '‘' to '’', '"' to '"', '\'' to '\'')
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

    fun appliesToRequest(narrativeMode: ChatNarrativeMode?, formatCard: String?): Boolean =
        narrativeMode != null && (formatCard == null || wrappers.none { it.opening in formatCard })

    fun completedBody(
        text: String,
        enabled: Boolean,
        interrupted: Boolean = false,
        partLengths: List<Int> = emptyList(),
        streaming: Boolean = false,
    ): String {
        if (!enabled) return text
        fun clean(part: String) = replyBody(part, streaming = streaming, interrupted = interrupted)
        if (partLengths.size > 1 && partLengths.sumOf(Int::toLong) + partLengths.size - 1 == text.length.toLong()) {
            var offset = 0
            val parts = partLengths.map { length ->
                val end = offset + length
                if (length < 0 || end !in offset..text.length ||
                    (end < text.length && text[end] != '\n')
                ) return clean(text)
                text.substring(offset, end).also { offset = end + 1 }
            }
            return parts.joinToString("\n", transform = ::clean)
        }
        return clean(text)
    }

    fun replyBody(
        text: String,
        streaming: Boolean = false,
        enabled: Boolean = true,
        interrupted: Boolean = false,
    ): String {
        if (!enabled) return text
        val start = text.trimStart()
        if (isIndentedCodeLine(text, text.length - start.length)) return text
        val wrapper = wrappers.firstOrNull { start.startsWith(it.opening) }
        if (wrapper == null) {
            val identifiable = start.startsWith("<历史回复 模式=") || start.startsWith("<聚焦模式=")
            if ((streaming || identifiable || interrupted && start.length > 1) && start.isNotEmpty() &&
                wrappers.any { it.opening.startsWith(start) }
            ) return ""
            return text
        }

        val body = start.removePrefix(wrapper.opening).trimStart('\r', '\n')
        val end = body.trimEnd()
        val familyTags = wrapperFamilyTokens.getValue(wrapper.name).findAll(end).toList()
        val literals by lazy(LazyThreadSafetyMode.NONE) {
            val markdown = bareCarriageReturn.replace(end, "\n")
            val includeUnclosed = streaming || interrupted
            val comments = mutableListOf<IntRange>()
            var nextComment: IntRange? = null

            fun inspect(source: String, onComment: ((IntRange) -> Unit)?): ReplyLiterals {
                val lazyQuoteLineStarts = mutableSetOf<Int>()
                val codeBlocks = mutableListOf<IntRange>()
                MarkdownCodeFences.blocks(
                    source,
                    onLazyQuoteLine = { lazyQuoteLineStarts += it },
                    onIndentedCodeLine = { codeBlocks += it },
                ).mapTo(codeBlocks) { it.range }
                val ranges = codeLiteralRanges(source, codeBlocks) + comments
                val quoteDelimitersToSkip = ranges + familyTags.map { it.range }
                val interiorClosings = if (includeUnclosed) familyTags.mapIndexedNotNull { index, tag ->
                    val position = tag.range.first
                    val linePrefix = source.subSequence(lineStart(source, position), position)
                    val nextTag = familyTags.getOrNull(index + 1)?.range?.first ?: end.length
                    val continuationLines = end.subSequence(tag.range.last + 1, nextTag).lineSequence().iterator()
                    position.takeIf {
                        tag.value in wrapper.closings && linePrefix.isBlank() && !isIndentedCodeLine(source, position) &&
                            ranges.none { position in it } && continuationLines.next().isBlank() &&
                            continuationLines.hasNext() && hanIdeograph.containsMatchIn(continuationLines.next())
                    }
                } else emptyList()
                val quotes = proseQuoteRanges(end, source, quoteDelimitersToSkip, includeUnclosed, interiorClosings, onComment)
                return ReplyLiterals(ranges + quotes, lazyQuoteLineStarts, source, comments)
            }

            val capture = { range: IntRange -> nextComment = range }
            var result = inspect(markdown, capture.takeIf { "<!--" in markdown })
            while (true) {
                val range = nextComment ?: break
                comments += range
                nextComment = null
                // A comment can itself contain a fence opener, so rescan the masked Markdown.
                result = inspect(maskHtmlComments(markdown, comments), capture)
            }
            result
        }
        fun isMarkdownQuoteLine(offset: Int): Boolean {
            val markdown = literals.markdown
            val lineStart = lineStart(markdown, offset)
            // A standalone closing marker also ends the wrapper directly after a quoted paragraph.
            return MarkdownCodeFences.hasQuoteContainerPrefix(markdown, lineStart) ||
                (lineStart in literals.lazyQuoteLineStarts && (lineStart until offset).any { index ->
                    !markdown[index].isWhitespace() && literals.commentRanges.none { index in it }
                })
        }
        var innerDepth = 0
        var unmatchedClosing = false
        for (match in familyTags) {
            val position = match.range.first
            val quote = end.getOrNull(position - 1)
            val asciiQuoted = (quote == '"' || quote == '\'') && end.getOrNull(match.range.last + 1) == quote
            if (asciiQuoted || isEscaped(end, position) || isMarkdownQuoteLine(position) || literals.ranges.any { position in it } ||
                isIndentedCodeLine(literals.markdown, position)
            ) continue

            if (match.value.startsWith("</")) {
                if (innerDepth > 0) {
                    innerDepth--
                } else if (!unmatchedClosing && match.value in wrapper.closings) {
                    // A complete leading pair followed by prose is an example.
                    return if (match.range.last == end.lastIndex) end.substring(0, position).trimEnd() else text
                } else {
                    unmatchedClosing = true
                }
            } else if (!match.value.dropLast(1).trimEnd().endsWith('/')) {
                innerDepth++
            }
        }

        val lastTag = end.lastIndexOf('<')
        if (lastTag >= 0 && innerDepth == 0 && !unmatchedClosing) {
            val suffix = end.substring(lastTag)
            val identifiable = suffix.startsWith("</历史回复") || suffix.startsWith("</聚焦模式")
            val aloneOnLine = end.substring(0, lastTag).substringAfterLast('\n').substringAfterLast('\r').isBlank()
            if ((streaming || identifiable || interrupted && (suffix.length > 1 || aloneOnLine)) &&
                wrapper.closings.any { suffix.length < it.length && it.startsWith(suffix) } &&
                !isEscaped(end, lastTag) && !isMarkdownQuoteLine(lastTag) &&
                literals.ranges.none { lastTag in it } && !isIndentedCodeLine(literals.markdown, lastTag)
            ) return end.substring(0, lastTag).trimEnd()
        }
        return body
    }

    private fun codeLiteralRanges(body: String, codeBlocks: List<IntRange>): List<IntRange> {
        if ('`' !in body) return codeBlocks
        val boundaries = (codeBlocks + paragraphBreak.findAll(body).map { it.range }.toList())
            .sortedBy { it.first }
        val inline = buildList {
            var start = 0
            for (boundary in boundaries) {
                if (start < boundary.first) addAll(inlineCodeRanges(body, start, boundary.first))
                start = maxOf(start, boundary.last + 1)
            }
            if (start < body.length) addAll(inlineCodeRanges(body, start, body.length))
        }
        return codeBlocks + inline
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
    ): List<IntRange> = buildList {
        var open = -1
        var closing: Char? = null
        var depth = 0
        var canOpenComment = onComment != null
        for (index in text.indices) {
            if (canOpenComment && open < 0 && text.startsWith("<!--", index) &&
                !isEscaped(text, index) && delimiterExclusions.none { index in it } &&
                !MarkdownCodeFences.hasQuoteContainerPrefix(markdown, lineStart(markdown, index))
            ) {
                val end = closedCommentEnd(text, index)
                if (end != null || includeUnclosed) {
                    onComment?.invoke(index until (end ?: text.length))
                    break
                }
                // The body renderer treats the rest of a completed, unclosed comment as ordinary text.
                canOpenComment = false
            }
            val symbol = text[index]
            val matchingClose = proseDelimiters[symbol]
            if (matchingClose == null && symbol != closing) continue
            if (isEscaped(text, index) || delimiterExclusions.any { index in it }) continue
            val followsWord = text.getOrNull(index - 1)?.isLetterOrDigit() == true
            val precedesWord = text.getOrNull(index + 1)?.isLetterOrDigit() == true
            // Apostrophes in contractions and possessives do not start a prose quote.
            if (symbol == '\'' && followsWord && (precedesWord || open < 0)) continue
            if (symbol == '’' && followsWord && (precedesWord ||
                    isCurlyPossessive(text, open, index, interiorClosings))
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
        text: String, quoteStart: Int, position: Int, interiorClosings: List<Int>,
    ): Boolean {
        if (!text[position - 1].equals('s', ignoreCase = true) ||
            text.getOrNull(position + 1)?.isWhitespace() != true
        ) return false
        val wordStart = (position + 2 until text.length).firstOrNull { !text[it].isWhitespace() } ?: return false
        if (!text[wordStart].isLetter()) return false
        val nextClose = text.indexOf('’', position + 1)
        val nextOpen = text.indexOf('‘', position + 1)
        if (nextClose >= 0) return nextOpen < 0 || nextClose < nextOpen
        if (nextOpen >= 0) return false
        // Require a speech cue before widening a quote with an ambiguous English closing mark.
        if (!chineseSpeechLead.containsMatchIn(text.subSequence(lineStart(text, quoteStart), quoteStart))) return false
        if (!unfinishedPluralPossessor.matches(text.subSequence(quoteStart + 1, position))) return false
        val tagLine = interiorClosings.firstOrNull { it > wordStart }?.let { lineStart(text, it) } ?: return false
        if (tagLine <= wordStart || lineStart(text, wordStart) != lineStart(text, position)) return false
        val possessed = text.substring(wordStart, tagLine).trimEnd()
        return unfinishedPossessedWord.matches(possessed) && possessed !in ordinaryProseFollowers && !possessed.endsWith("ly")
    }

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
        for (token in commentDelimiters.findAll(text, start + "<!--".length)) {
            if (token.value == "<!--") depth++
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
