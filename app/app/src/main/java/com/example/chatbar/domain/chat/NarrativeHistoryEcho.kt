package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatNarrativeMode

/** Removes request-only history wrappers when the model echoes them around its new reply. */
object NarrativeHistoryEcho {
    private data class Wrapper(val name: String, val opening: String, val closings: List<String>)
    private data class ReplyLiterals(val ranges: List<IntRange>, val lazyQuoteLineStarts: Set<Int>)

    private val wrappers = ChatNarrativeMode.entries.map { mode ->
        Wrapper("历史回复", "<历史回复 模式=\"${mode.shortLabel}\">", listOf("</历史回复>"))
    } + Wrapper("聚焦模式", "<聚焦模式=\"沉浸\">", listOf("</聚焦模式>", "</聚焦模式=\"沉浸\">"))
    private val wrapperFamilyTokens = wrappers.associate { wrapper ->
        val name = Regex.escape(wrapper.name)
        wrapper.name to Regex("</?$name(?=[\\s=>/])[^<>]*>")
    }
    private val backtickRuns = Regex("`+")
    private val paragraphBreak = Regex("\\r?\\n[ \\t]*\\r?\\n")
    private val bareCarriageReturn = Regex("\\r(?!\\n)")
    private val proseDelimiters = mapOf('「' to '」', '『' to '』', '“' to '”', '‘' to '’', '"' to '"', '\'' to '\'')

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
            val lazyQuoteLineStarts = mutableSetOf<Int>()
            val fences = MarkdownCodeFences.blocks(markdown) { lazyQuoteLineStarts += it }.map { it.range }
            val ranges = codeLiteralRanges(markdown, fences)
            val quoteDelimitersToSkip = ranges + familyTags.map { it.range }
            val quotes = proseQuoteRanges(end, quoteDelimitersToSkip, includeUnclosed = streaming || interrupted)
            ReplyLiterals(ranges + quotes, lazyQuoteLineStarts)
        }
        fun isMarkdownQuoteLine(offset: Int): Boolean {
            val lineStart = lineStart(end, offset)
            // A standalone closing marker also ends the wrapper directly after a quoted paragraph.
            return MarkdownCodeFences.hasQuoteContainerPrefix(end, lineStart) ||
                (end.subSequence(lineStart, offset).isNotBlank() && lineStart in literals.lazyQuoteLineStarts)
        }
        var innerDepth = 0
        var unmatchedClosing = false
        for (match in familyTags) {
            val position = match.range.first
            val quote = end.getOrNull(position - 1)
            val asciiQuoted = (quote == '"' || quote == '\'') && end.getOrNull(match.range.last + 1) == quote
            if (asciiQuoted || isEscaped(end, position) || isMarkdownQuoteLine(position) || literals.ranges.any { position in it } ||
                isIndentedCodeLine(end, position)
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
                literals.ranges.none { lastTag in it } && !isIndentedCodeLine(end, lastTag)
            ) return end.substring(0, lastTag).trimEnd()
        }
        return body
    }

    private fun codeLiteralRanges(body: String, fences: List<IntRange>): List<IntRange> {
        if ('`' !in body) return fences
        val boundaries = (fences + paragraphBreak.findAll(body).map { it.range }.toList())
            .sortedBy { it.first }
        val inline = buildList {
            var start = 0
            for (boundary in boundaries) {
                if (start < boundary.first) addAll(inlineCodeRanges(body, start, boundary.first))
                start = maxOf(start, boundary.last + 1)
            }
            if (start < body.length) addAll(inlineCodeRanges(body, start, body.length))
        }
        return fences + inline
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
        text: String, delimiterExclusions: List<IntRange>, includeUnclosed: Boolean,
    ): List<IntRange> = buildList {
        var open = -1
        var closing: Char? = null
        var depth = 0
        for (index in text.indices) {
            val symbol = text[index]
            val matchingClose = proseDelimiters[symbol]
            if (matchingClose == null && symbol != closing) continue
            if (isEscaped(text, index) || delimiterExclusions.any { index in it }) continue
            val followsWord = text.getOrNull(index - 1)?.isLetterOrDigit() == true
            val precedesWord = text.getOrNull(index + 1)?.isLetterOrDigit() == true
            // Apostrophes in contractions and possessives do not start a prose quote.
            if (symbol == '\'' && followsWord && (precedesWord || open < 0)) continue
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
