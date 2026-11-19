package com.example.chatbar.domain.chat

/** Raw source ranges for Markdown fenced code, including an unfinished fence. */
object MarkdownCodeFences {
    private val fenceLine = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")

    private data class Fence(val start: Int, val marker: String)

    fun ranges(content: String): List<IntRange> = buildList {
        var offset = 0
        var open: Fence? = null
        for (line in content.splitToSequence('\n')) {
            val start = offset
            offset += line.length + 1
            val match = fenceLine.matchEntire(line.removeSuffix("\r")) ?: continue
            val marker = match.groupValues[1]
            val rest = match.groupValues[2]
            val existing = open
            if (existing == null) {
                if (marker[0] == '~' || '`' !in rest) open = Fence(start, marker)
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
