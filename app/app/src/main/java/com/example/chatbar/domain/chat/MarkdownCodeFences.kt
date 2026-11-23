package com.example.chatbar.domain.chat

/** Raw source ranges for Markdown fenced code, including an unfinished fence. */
object MarkdownCodeFences {
    private val fenceLine = Regex("^ {0,3}(`{3,}|~{3,})(.*)$")
    private val quotePrefix = Regex(" {0,3}>[ \\t]?")
    private val listPrefix = Regex(" {0,3}([-+*]|[0-9]{1,9}[.)])(?: {1,4}(?=[^ \\t\\r])|[ \\t]*\\r?$)")
    private val thematicBreak = Regex(" {0,3}(?:(?:-[ \\t]*){3,}|(?:\\*[ \\t]*){3,}|(?:_[ \\t]*){3,})\\r?")
    private val heading = Regex("^ {0,3}#{1,6}(?:[ \\t]|$)")
    private val setextUnderline = Regex(" {0,3}(?:=+|-+)[ \\t]*")
    internal const val legacyStatusLabel = "（三个点为状态包裹格式，必须完整输出）"
    private const val legacyStatusInfo = "text$legacyStatusLabel"
    internal const val legacyStatusOpening = "```$legacyStatusInfo"
    private val legacyStatusClose = Regex(" {0,3}$legacyStatusLabel``` *")

    data class Block(
        val range: IntRange,
        val topLevel: Boolean,
        val marker: Char,
        val info: String,
        internal val closingLineStart: Int? = null,
        internal val legacyStatus: Boolean = false,
    )

    // A null indent identifies a block quote. A list item continues at its content indent.
    private data class Container(val indent: Int? = null, val awaitingContent: Boolean = false)
    private data class Fence(val start: Int, val marker: String, val topLevel: Boolean, val info: String) {
        val legacyStatus = marker == "```" && info == legacyStatusInfo

        fun to(endExclusive: Int, closingLineStart: Int? = null) =
            Block(start until endExclusive, topLevel, marker[0], info, closingLineStart, legacyStatus)
    }

    internal fun afterQuotePrefixes(text: String, start: Int): Int {
        var end = start
        while (true) {
            val prefix = quotePrefix.matchAt(text, end) ?: return end
            end = prefix.range.last + 1
        }
    }

    internal fun hasQuoteContainerPrefix(text: String, start: Int): Boolean {
        var offset = start
        while (true) {
            if (quotePrefix.matchAt(text, offset) != null) return true
            val list = listPrefix.matchAt(text, offset) ?: return false
            offset = list.range.last + 1
        }
    }

    fun ranges(content: String): List<IntRange> = blocks(content).filter(Block::topLevel).map(Block::range)

    fun blocks(content: String): List<Block> = blocks(content, null)

    internal fun findBlocks(content: String, onContainerLine: (Int) -> Unit): List<Block> =
        blocks(content, onLazyQuoteLine = null, onContainerLine = onContainerLine)

    internal fun blocks(
        content: String,
        onLazyQuoteLine: ((Int) -> Unit)?,
        onIndentedCodeLine: ((IntRange) -> Unit)? = null,
        onContainerLine: ((Int) -> Unit)? = null,
        onListItemLine: ((Int) -> Unit)? = null,
    ): List<Block> = buildList {
        val containers = mutableListOf<Container>()
        var offset = 0
        var open: Fence? = null
        var paragraph = false
        for (rawLine in content.splitToSequence('\n')) {
            val start = offset
            offset += rawLine.length + 1
            val line = expandTabs(rawLine)
            var cursor = 0
            var continued = 0
            var dedentedParagraph = emptyList<Container>()
            for (container in containers) {
                cursor = continueContainer(line, cursor, container) ?: break
                if (container.awaitingContent) containers[continued] = container.copy(awaitingContent = false)
                continued++
            }
            if (continued < containers.size) {
                val endedContainers = containers.subList(continued, containers.size)
                if (paragraph) dedentedParagraph = endedContainers.toList()
                open?.let { add(it.to(start)) }
                open = null
                endedContainers.clear()
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
                    dedentedParagraph = emptyList()
                }
            }

            if (containers.isNotEmpty()) onContainerLine?.invoke(start)
            // Record explicit membership before a dedented line can lazily resume a paragraph.
            if (onListItemLine != null && containers.any { it.indent != null }) onListItemLine(start)
            val remainder = line.substring(cursor).removeSuffix("\r")
            val existing = open
            // The built-in legacy status template puts its closing backticks after the label.
            if (existing?.legacyStatus == true && legacyStatusClose.matches(remainder)) {
                add(existing.to(start + rawLine.length, closingLineStart = start))
                open = null
                continue
            }
            val match = fenceLine.matchEntire(remainder)
            if (match == null) {
                if (existing == null) {
                    val startsIndentedCode = !paragraph && dedentedParagraph.isEmpty() && remainder.startsWith("    ")
                    if (startsIndentedCode && remainder.isNotBlank()) {
                        onIndentedCodeLine?.invoke(start until start + rawLine.length)
                    }
                    val endsSetextHeading = paragraph && setextUnderline.matches(remainder)
                    paragraph = !startsIndentedCode && remainder.isNotBlank() && !thematicBreak.matches(remainder) &&
                        !endsSetextHeading && !heading.containsMatchIn(remainder)
                    // A lazy paragraph line keeps its quote or list context for subsequent lines.
                    if (paragraph) {
                        if (onLazyQuoteLine != null && dedentedParagraph.any { it.indent == null }) {
                            onLazyQuoteLine(start)
                        }
                        containers.addAll(dedentedParagraph)
                    }
                }
                continue
            }

            val marker = match.groupValues[1]
            val rest = match.groupValues[2]
            if (existing == null) {
                if (marker[0] == '~' || '`' !in rest) {
                    open = Fence(start, marker, topLevel = containers.isEmpty(), info = rest.trim())
                    paragraph = false
                }
            } else if (marker[0] == existing.marker[0] &&
                marker.length >= existing.marker.length && rest.isBlank()
            ) {
                add(existing.to(start + rawLine.length, closingLineStart = start))
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

    private fun expandTabs(line: String): String {
        if ('\t' !in line) return line
        return buildString(line.length) {
            for (character in line) {
                if (character == '\t') repeat(4 - length % 4) { append(' ') } else append(character)
            }
        }
    }
}
