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
    private val markdownLink = Regex("!?\\[[^]]*]\\s*\\(|https?://", RegexOption.IGNORE_CASE)

    private data class FenceMarker(val run: String, val info: String) {
        fun isClosedBy(other: FenceMarker?): Boolean =
            other != null && other.info.isEmpty() && other.run.first() == run.first() &&
                other.run.length >= run.length
    }

    private fun rule(line: String): Boolean = line.trim().let {
        it.length >= 3 && it.all { char -> char in "-─━—" }
    }
    private fun heading(line: String): Boolean = line.trim()
        .trim('#', '*', ' ', '[', ']', '【', '】', ':', '：').lowercase() in
        setOf("行动选项", "可选行动", "行动选择", "action choices", "action options")

    fun validationError(format: ActionOptionsFormat): String? {
        val opening = format.opening.trim()
        val closing = format.closing.trim()
        val title = format.heading.trim()
        if (opening.isEmpty() || closing.isEmpty()) return "请填写区块开头和结尾"
        if (listOf(format.opening, format.closing, format.heading).any { line ->
            line.length > 80 || line.any(Char::isISOControl)
        }) return "每个标记须为单行，最多 80 个字符"
        if (opening.startsWith('>') || closing.startsWith('>')) return "不能用引用行作为区块标记"
        if (title == opening || title == closing || fenceMarker(title) != null) {
            return "区块标题不能同时作为围栏或开头、结尾标记"
        }
        val startFence = fenceMarker(opening)
        val endFence = fenceMarker(closing)
        if (startFence != null || endFence != null) {
            if (startFence == null || !startFence.isClosedBy(endFence) ||
                (startFence.run.startsWith('`') && '`' in startFence.info)
            ) return "代码围栏须使用同一种符号，结尾不得带文字且不能比开头短"
            if (startFence.info.isEmpty() && title.isEmpty()) return "没有名称的代码围栏须填写区块标题"
        }
        if ((rule(opening) || rule(closing)) && title.isEmpty()) return "使用横线作为标记时，请填写区块标题"
        return null
    }

    fun blocks(content: String, format: ActionOptionsFormat? = null): List<RoleplayActionOptionsBlock> {
        if (format != null && validationError(format) != null) return emptyList()
        if (content.isBlank()) return emptyList()
        val markdownFences = MarkdownCodeFences.ranges(content)
        val declaresFence = format != null && fenceMarker(format.opening.trim()) != null
        val fenced = if (format == null || declaresFence) {
            markdownFences.mapNotNull { fencedBlock(content, it, format) }
        } else emptyList()
        if (declaresFence) return fenced
        val codeRanges = parseRoleplayTextSegments(content).filter {
            it.kind == RoleplaySegmentKind.STATUS && it.rawText.trimStart().startsWith("```")
        }.map { it.start until it.endExclusive } + markdownFences

        val literal = format ?: defaultFormat
        val ranges = literalRanges(content, literal, codeRanges)
        val legacy = if (format == null) roleplayLongDashRawRanges(content) else emptyList()
        val plain = (ranges + legacy).distinct().mapNotNull { range ->
            if (codeRanges.any { it.overlaps(range) }) return@mapNotNull null
            val lines = content.substring(range).lineSequence().filter(String::isNotBlank).toList()
            if (lines.size < 4) return@mapNotNull null
            if (isCodeIndented(lines.first()) || isCodeIndented(lines.last())) return@mapNotNull null
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
        return (plain + fenced).sortedBy { it.range.first }
    }

    private fun fencedBlock(
        content: String,
        sourceRange: IntRange,
        format: ActionOptionsFormat?
    ): RoleplayActionOptionsBlock? {
        // Only inspect the full top-level fence reported by the Markdown walker. An action
        // example inside an ordinary code fence is never considered separately.
        val range = if (content[sourceRange.last] == '\r') sourceRange.first until sourceRange.last else sourceRange
        val lines = content.substring(range).lines()
        if (lines.size < 4) return null
        val opening = lines.first().trim()
        val closing = lines.last().trim()
        val first = fenceMarker(opening) ?: return null
        if (isCodeIndented(lines.first()) || isCodeIndented(lines.last()) || !first.isClosedBy(fenceMarker(closing))) {
            return null
        }

        var body = lines.subList(1, lines.lastIndex).filter(String::isNotBlank)
        if (format != null) {
            if (opening != format.opening.trim() || closing != format.closing.trim()) return null
            if (format.heading.isNotBlank()) {
                if (body.firstOrNull()?.trim() != format.heading.trim()) return null
                body = body.drop(1)
            }
        } else {
            // Keep the pre-existing Desktop convention. Ordinary fence languages such as
            // text, markdown and python do not turn their contents into actions.
            if (!first.run.startsWith('`') || (first.info.isNotEmpty() && !heading(first.info))) return null
            if (body.firstOrNull()?.let(::heading) == true) body = body.drop(1)
            else if (first.info.isEmpty()) return null
        }

        val options = parseItems(body, format?.labels ?: ActionOptionLabels.NUMBERS) ?: return null
        if (format == null && (options.map { it.text }.distinct().size != options.size || options.any {
            markdownLink.containsMatchIn(it.text) || "```" in it.text || '<' in it.text || '>' in it.text
        })) return null
        return RoleplayActionOptionsBlock(range, options)
    }

    private fun fenceMarker(line: String): FenceMarker? {
        val mark = line.firstOrNull()?.takeIf { it == '`' || it == '~' } ?: return null
        val run = line.takeWhile { it == mark }.takeIf { it.length >= 3 } ?: return null
        return FenceMarker(run, line.substring(run.length).trim())
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

    private fun isCodeIndented(line: String): Boolean {
        val prefix = line.takeWhile { it == ' ' || it == '\t' }
        return prefix.length >= 4 || '\t' in prefix
    }
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
