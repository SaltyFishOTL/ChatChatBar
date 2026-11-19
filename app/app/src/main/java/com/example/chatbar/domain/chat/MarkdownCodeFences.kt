package com.example.chatbar.domain.chat

/** Raw source ranges for Markdown fenced code, including an unfinished fence. */
object MarkdownCodeFences {
    private val fenceLine = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")
    private val quotePrefix = Regex(" {0,3}>[ \\t]?")
    private val listPrefix = Regex(" {0,3}(?:[-+*]|[0-9]{1,9}[.)]) {1,4}(?=[^ ])")

    internal data class QuotePrefix(val depth: Int, val end: Int)
    private data class Fence(val start: Int, val marker: String, val quoteDepth: Int, val listIndent: Int)

    internal fun quotePrefixAt(text: String, start: Int, maxDepth: Int = Int.MAX_VALUE): QuotePrefix {
        var end = start
        var depth = 0
        while (depth < maxDepth) {
            val prefix = quotePrefix.matchAt(text, end) ?: break
            end = prefix.range.last + 1
            depth++
        }
        return QuotePrefix(depth, end)
    }

    fun ranges(content: String, includeContainers: Boolean = false): List<IntRange> = buildList {
        var offset = 0
        var open: Fence? = null
        for (line in content.splitToSequence('\n')) {
            val start = offset
            offset += line.length + 1
            var quote = if (includeContainers) quotePrefixAt(line, 0, open?.quoteDepth ?: Int.MAX_VALUE)
                else QuotePrefix(0, 0)
            var contentStart = quote.end
            val active = open
            if (active != null) {
                val continuation = contentStart + active.listIndent
                if (quote.depth < active.quoteDepth ||
                    (active.listIndent > 0 && line.drop(contentStart).isNotBlank() &&
                        (continuation > line.length || (contentStart until continuation).any { line[it] != ' ' }))
                ) {
                    // An unfinished fence ends with its containing quote or list item.
                    add(active.start until start)
                    open = null
                    quote = quotePrefixAt(line, 0)
                    contentStart = quote.end
                } else {
                    contentStart = continuation.coerceAtMost(line.length)
                }
            }
            var listIndent = 0
            if (includeContainers && open == null) {
                while (true) {
                    val prefix = listPrefix.matchAt(line, contentStart) ?: break
                    contentStart = prefix.range.last + 1
                }
                listIndent = contentStart - quote.end
            }
            val match = fenceLine.matchEntire(line.substring(contentStart).removeSuffix("\r")) ?: continue
            val marker = match.groupValues[1]
            val rest = match.groupValues[2]
            val existing = open
            if (existing == null) {
                if (marker[0] == '~' || '`' !in rest) open = Fence(start, marker, quote.depth, listIndent)
            } else if (marker[0] == existing.marker[0] &&
                marker.length >= existing.marker.length && rest.isBlank()
            ) {
                add(existing.start until (start + line.length))
                open = null
            }
        }
        open?.let { add(it.start until content.length) }
    }
}
