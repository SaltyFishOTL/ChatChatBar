package com.example.chatbar.domain.chat

/** Raw source ranges for Markdown fenced code, including an unfinished fence. */
object MarkdownCodeFences {
    private val fenceLine = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")
    private val quotePrefix = Regex(" {0,3}>[ \\t]?")

    private data class Fence(val start: Int, val marker: String, val quoteDepth: Int)

    fun ranges(content: String, includeQuoted: Boolean = false): List<IntRange> = buildList {
        var offset = 0
        var open: Fence? = null
        for (line in content.splitToSequence('\n')) {
            val start = offset
            offset += line.length + 1
            var contentStart = 0
            var quoteDepth = 0
            if (includeQuoted && open?.quoteDepth != 0) {
                while (true) {
                    val prefix = quotePrefix.matchAt(line, contentStart) ?: break
                    contentStart = prefix.range.last + 1
                    quoteDepth++
                }
            }
            val active = open
            if (active != null && quoteDepth != active.quoteDepth) {
                // A fence cannot close at a deeper quote level or continue outside its quote.
                if (quoteDepth > active.quoteDepth) continue
                add(active.start until start)
                open = null
            }
            val match = fenceLine.matchEntire(line.substring(contentStart).removeSuffix("\r")) ?: continue
            val marker = match.groupValues[1]
            val rest = match.groupValues[2]
            val existing = open
            if (existing == null) {
                if (marker[0] == '~' || '`' !in rest) open = Fence(start, marker, quoteDepth)
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
