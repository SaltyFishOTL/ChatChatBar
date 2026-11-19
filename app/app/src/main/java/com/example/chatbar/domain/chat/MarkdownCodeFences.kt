package com.example.chatbar.domain.chat

/** Raw source ranges for Markdown fenced code, including an unfinished fence. */
object MarkdownCodeFences {
    private val fenceLine = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")
    private val quotePrefix = Regex(" {0,3}>[ \\t]?")
    private val listPrefix = Regex(" {0,3}([-+*]|[0-9]{1,9}[.)])(?: {1,4}(?=[^ \\t\\r])|[ \\t]*\\r?$)")
    private val thematicBreak = Regex(" {0,3}(?:(?:-[ \\t]*){3,}|(?:\\*[ \\t]*){3,}|(?:_[ \\t]*){3,})\\r?")
    private val headingOrUnderline = Regex("^ {0,3}(?:#{1,6}(?:[ \\t]|$)|[=-]+[ \\t]*$)")

    data class Block(val range: IntRange, val topLevel: Boolean)

    // A null indent identifies a block quote. A list item continues at its content indent.
    private data class Container(val indent: Int? = null, val awaitingContent: Boolean = false)
    private data class Fence(val start: Int, val marker: String, val topLevel: Boolean) {
        fun to(endExclusive: Int) = Block(start until endExclusive, topLevel)
    }

    internal fun afterQuotePrefixes(text: String, start: Int): Int {
        var end = start
        while (true) {
            val prefix = quotePrefix.matchAt(text, end) ?: return end
            end = prefix.range.last + 1
        }
    }

    fun ranges(content: String): List<IntRange> = blocks(content).filter(Block::topLevel).map(Block::range)

    fun blocks(content: String): List<Block> = buildList {
        val containers = mutableListOf<Container>()
        var offset = 0
        var open: Fence? = null
        var paragraph = false
        for (line in content.splitToSequence('\n')) {
            val start = offset
            offset += line.length + 1
            var cursor = 0
            var continued = 0
            for (container in containers) {
                cursor = continueContainer(line, cursor, container) ?: break
                if (container.awaitingContent) containers[continued] = container.copy(awaitingContent = false)
                continued++
            }
            if (continued < containers.size) {
                open?.let { add(it.to(start)) }
                open = null
                containers.subList(continued, containers.size).clear()
                paragraph = false
            }

            if (open == null) {
                while (true) {
                    val quote = quotePrefix.matchAt(line, cursor)
                    if (quote != null) {
                        containers.add(Container())
                        cursor = quote.range.last + 1
                    } else {
                        val list = listPrefix.matchAt(line, cursor) ?: break
                        val marker = requireNotNull(list.groups[1])
                        if (marker.value.length == 1 && thematicBreak.matches(line.substring(cursor))) break
                        val end = list.range.last + 1
                        val emptyItem = end == line.length
                        if (paragraph && (emptyItem || marker.value.length > 1 && marker.value.dropLast(1).toInt() != 1)) {
                            break
                        }
                        // An empty marker line sets the indent from the marker width, ignoring trailing spaces.
                        val contentColumn = if (emptyItem) marker.range.last + 2 else end
                        containers.add(Container(indent = contentColumn - cursor, awaitingContent = emptyItem))
                        cursor = end
                    }
                    paragraph = false
                }
            }

            val remainder = line.substring(cursor).removeSuffix("\r")
            val match = fenceLine.matchEntire(remainder)
            val existing = open
            if (match == null) {
                if (existing == null) {
                    paragraph = remainder.isNotBlank() && !thematicBreak.matches(remainder) &&
                        !headingOrUnderline.containsMatchIn(remainder)
                }
                continue
            }

            val marker = match.groupValues[1]
            val rest = match.groupValues[2]
            if (existing == null) {
                if (marker[0] == '~' || '`' !in rest) {
                    open = Fence(start, marker, topLevel = containers.isEmpty())
                    paragraph = false
                }
            } else if (marker[0] == existing.marker[0] &&
                marker.length >= existing.marker.length && rest.isBlank()
            ) {
                add(existing.to(start + line.length))
                open = null
            }
        }
        open?.let { add(it.to(content.length)) }
    }

    private fun continueContainer(line: String, start: Int, container: Container): Int? {
        val indent = container.indent
        if (indent == null) return quotePrefix.matchAt(line, start)?.range?.last?.plus(1)
        // A second blank line ends an item that has not had any content yet.
        if (container.awaitingContent && line.substring(start).isBlank()) return null
        val limit = start + indent
        if (limit <= line.length && (start until limit).all { line[it] == ' ' }) return limit
        return line.length.takeIf { line.substring(start).isBlank() }
    }
}
