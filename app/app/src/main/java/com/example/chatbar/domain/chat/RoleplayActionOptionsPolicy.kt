package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ActionOptionLabels
import com.example.chatbar.data.local.entity.ActionOptionsFormat

/** Derived projection only; original replies, versions, status and user text are unchanged. */
data class RoleplayActionOption(val ordinal: Int, val text: String, val label: String = ordinal.toString())
data class RoleplayActionOptionsBlock(val range: IntRange, val options: List<RoleplayActionOption>)

object RoleplayActionOptionsPolicy {
    val defaultFormat = ActionOptionsFormat()
    private val numbered = Regex("^([1-9][0-9]?)[.、)）][ \\t]*(.+)$")
    private val lettered = Regex("^([A-Ta-t])[.、)）][ \\t]*(.+)$")

    private fun rule(line: String): Boolean = line.trim().let {
        it.length >= 3 && it.all { char -> char in "-─━—" }
    }
    private fun heading(line: String): Boolean = line.trim()
        .trim('#', '*', ' ', '[', ']', '【', '】', ':', '：').lowercase() in
        setOf("行动选项", "可选行动", "行动选择", "action choices", "action options")

    fun validationError(format: ActionOptionsFormat): String? = when {
        format.opening.isBlank() || format.closing.isBlank() -> "请填写区块开头和结尾"
        listOf(format.opening, format.closing, format.heading).any { line ->
            line.length > 80 || line.any(Char::isISOControl)
        } -> "每个标记须为单行，最多 80 个字符"
        (rule(format.opening) || rule(format.closing)) && format.heading.isBlank() ->
            "使用横线作为标记时，请填写区块标题"
        else -> null
    }

    fun blocks(content: String, format: ActionOptionsFormat? = null): List<RoleplayActionOptionsBlock> {
        if (format != null && validationError(format) != null) return emptyList()
        if (content.isBlank()) return emptyList()
        val codeRanges = parseRoleplayTextSegments(content).filter {
            it.kind == RoleplaySegmentKind.STATUS && it.rawText.trimStart().startsWith("```")
        }.map { it.start until it.endExclusive }

        val literal = format ?: defaultFormat
        val ranges = literalRanges(content, literal, codeRanges)
        val legacy = if (format == null) roleplayLongDashRawRanges(content) else emptyList()
        return (ranges + legacy).distinct().sortedBy { it.first }.mapNotNull { range ->
            if (codeRanges.any { it.overlaps(range) }) return@mapNotNull null
            val lines = content.substring(range).lineSequence().filter(String::isNotBlank).toList()
            if (lines.size < 4) return@mapNotNull null
            val literalBoundary = lines.first().trim() == literal.opening.trim() &&
                lines.last().trim() == literal.closing.trim()
            if (!literalBoundary && (format != null || !rule(lines.first()) || !rule(lines.last()))) {
                return@mapNotNull null
            }
            val hasHeading = literal.heading.isNotBlank()
            if (hasHeading && !(if (format == null) heading(lines[1]) else lines[1].trim() == literal.heading.trim())) {
                return@mapNotNull null
            }
            parseItems(lines.subList(if (hasHeading) 2 else 1, lines.lastIndex), literal.labels)
                ?.let { RoleplayActionOptionsBlock(range, it) }
        }
    }

    fun withoutExplicitOptions(content: String, format: ActionOptionsFormat? = null): String =
        textOutsideBlocks(content, blocks(content, format))

    /** Slice a presentation or speech fragment using its original source coordinates. */
    fun textOutsideBlocks(
        content: String,
        blocks: List<RoleplayActionOptionsBlock>,
        start: Int = 0,
        endExclusive: Int = content.length
    ): String = buildString {
        var cursor = start
        for (block in blocks) {
            if (block.range.last < cursor || block.range.first >= endExclusive) continue
            if (cursor < block.range.first) append(content, cursor, block.range.first)
            cursor = (block.range.last + 1).coerceAtMost(endExclusive)
        }
        if (cursor < endExclusive) append(content, cursor, endExclusive)
    }

    /** Only restore a missing closing line when the declared opener and every option are present. */
    fun completeTrailingBlock(content: String, format: ActionOptionsFormat?): String {
        val active = format ?: defaultFormat
        val last = content.trimEnd()
        if (last.endsWith(active.closing.trim())) return content
        val candidate = last + "\n" + active.closing.trim()
        val tail = blocks(candidate, format).lastOrNull() ?: return content
        return if (tail.range.last == candidate.lastIndex) candidate else content
    }

    private fun literalRanges(content: String, format: ActionOptionsFormat, codeRanges: List<IntRange>): List<IntRange> {
        val opening = format.opening.trim()
        val closing = format.closing.trim()
        val ranges = mutableListOf<IntRange>()
        var offset = 0
        var start: Int? = null
        for (line in content.splitToSequence('\n')) {
            val lineStart = offset
            val lineEnd = offset + line.removeSuffix("\r").length
            offset += line.length + 1
            if (codeRanges.any { lineStart in it }) {
                start = null
                continue
            }
            val marker = line.trim()
            val openStart = start
            when {
                openStart != null && marker == closing -> {
                    ranges += openStart until lineEnd
                    start = lineStart.takeIf { opening == closing }
                }
                marker == opening -> start = lineStart
            }
        }
        return ranges
    }

    private fun parseItems(lines: List<String>, labels: ActionOptionLabels): List<RoleplayActionOption>? {
        val options = mutableListOf<RoleplayActionOption>()
        val pattern = if (labels == ActionOptionLabels.NUMBERS) numbered else lettered
        for (line in lines) {
            val match = pattern.matchEntire(line.trim())
            if (match != null) {
                val label = match.groupValues[1]
                val ordinal = if (labels == ActionOptionLabels.NUMBERS) label.toInt()
                    else label.single().uppercaseChar() - 'A' + 1
                if (ordinal != options.size + 1 || ordinal > 20) return null
                options += RoleplayActionOption(ordinal, match.groupValues[2], label)
            } else if (options.isNotEmpty() && (line.startsWith("  ") || line.startsWith("\t"))) {
                val previous = options.removeAt(options.lastIndex)
                options += previous.copy(text = previous.text + "\n" + line.trim())
            } else return null
        }
        return options.takeIf { it.size >= 2 }
    }

    private fun IntRange.overlaps(other: IntRange): Boolean = first <= other.last && other.first <= last
}

data class ActionOptionInsertion(val text: String, val cursor: Int)

fun insertActionOption(text: String, selectionEnd: Int?, option: String): ActionOptionInsertion {
    val cursor = selectionEnd?.coerceIn(0, text.length) ?: text.length
    val before = text.substring(0, cursor)
    val after = text.substring(cursor)
    val prefix = if (before.isNotEmpty() && !before.endsWith('\n') && !before.last().isWhitespace()) "\n" else ""
    val suffix = if (after.isNotEmpty() && !after.first().isWhitespace()) "\n" else ""
    val inserted = before + prefix + option
    return ActionOptionInsertion(inserted + suffix + after, inserted.length)
}
