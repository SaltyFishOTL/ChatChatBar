package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatNarrativeMode

/** Removes request-only history wrappers when the model echoes them around its new reply. */
object NarrativeHistoryEcho {
    private data class Wrapper(val name: String, val opening: String, val closings: List<String>)

    private val wrappers = ChatNarrativeMode.entries.map { mode ->
        Wrapper("历史回复", "<历史回复 模式=\"${mode.shortLabel}\">", listOf("</历史回复>"))
    } + Wrapper("聚焦模式", "<聚焦模式=\"沉浸\">", listOf("</聚焦模式>", "</聚焦模式=\"沉浸\">"))
    private val wrapperFamilyTokens = wrappers.associate { wrapper ->
        val name = Regex.escape(wrapper.name)
        wrapper.name to Regex("</?$name(?=[\\s=>/])[^<>]*>")
    }
    private val backtickRuns = Regex("`+")
    private val paragraphBreak = Regex("\\r?\\n[ \\t]*\\r?\\n")

    fun replyBody(text: String, streaming: Boolean = false): String {
        val start = text.trimStart()
        if (isIndentedCodeLine(text, text.length - start.length)) return text
        val wrapper = wrappers.firstOrNull { start.startsWith(it.opening) }
        if (wrapper == null) {
            val identifiable = start.startsWith("<历史回复 模式=") || start.startsWith("<聚焦模式=")
            if ((streaming || identifiable) && start.isNotEmpty() &&
                wrappers.any { it.opening.startsWith(start) }
            ) return ""
            return text
        }

        val body = start.removePrefix(wrapper.opening).trimStart('\r', '\n')
        val end = body.trimEnd()
        val literalRanges by lazy(LazyThreadSafetyMode.NONE) { codeLiteralRanges(end) }
        var innerDepth = 0
        var unmatchedClosing = false
        for (match in wrapperFamilyTokens.getValue(wrapper.name).findAll(end)) {
            val position = match.range.first
            if (literalRanges.any { position in it } || isIndentedCodeLine(end, position)) continue

            if (match.value.startsWith("</")) {
                if (innerDepth > 0) {
                    innerDepth--
                } else if (!unmatchedClosing && match.value in wrapper.closings) {
                    // A complete pair followed by prose is a leading example, not a reply wrapper.
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
            // While receiving deltas, withhold a potential closing tag until it can be classified.
            // At completion, only discard a truncated tag if its name already identifies it.
            val identifiable = suffix.startsWith("</历史回复") || suffix.startsWith("</聚焦模式")
            if ((streaming || identifiable) && wrapper.closings.any { suffix.length < it.length && it.startsWith(suffix) } &&
                literalRanges.none { lastTag in it } && !isIndentedCodeLine(end, lastTag)
            ) {
                return end.substring(0, lastTag).trimEnd()
            }
        }
        return body
    }

    private fun codeLiteralRanges(body: String): List<IntRange> {
        val fences = MarkdownCodeFences.ranges(body)
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
            var preceding = position
            while (preceding > 0 && text[preceding - 1] == '\\') preceding--
            val unescapedStart = position + (position - preceding) % 2
            val length = opening.range.last + 1 - unescapedStart
            if (length == 0) continue
            // A span closes with the next run of equal length. Backslashes inside it are literal.
            val close = (index until runs.size).firstOrNull { runs[it].value.length == length } ?: continue
            add(unescapedStart..runs[close].range.last)
            index = close + 1
        }
    }

    private fun isIndentedCodeLine(text: String, offset: Int): Boolean {
        val lineStart = text.lastIndexOf('\n', offset - 1) + 1
        val indent = text.substring(lineStart, offset).takeWhile { it == ' ' || it == '\t' }
        return indent.length >= 4 || '\t' in indent
    }
}
