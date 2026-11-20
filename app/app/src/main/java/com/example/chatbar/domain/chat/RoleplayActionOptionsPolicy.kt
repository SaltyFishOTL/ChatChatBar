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
        if ("```" in title) return "区块标题不能包含反引号围栏"
        val startFence = fenceMarker(opening)
        val endFence = fenceMarker(closing)
        if (startFence != null || endFence != null) {
            if (startFence == null || !startFence.isClosedBy(endFence) ||
                (startFence.run.startsWith('`') && '`' in startFence.info)
            ) return "代码围栏须使用同一种符号，结尾不得带文字且不能比开头短"
            if ("```" in startFence.info) return "代码围栏名称不能包含另一个围栏"
            if (startFence.info.isEmpty() && title.isEmpty()) return "没有名称的代码围栏须填写区块标题"
        } else if ("```" in opening || "```" in closing) {
            // The existing message parser also treats embedded backtick runs as code.
            return "反引号围栏须独立成行，不能嵌入普通标记"
        }
        if ((rule(opening) || rule(closing)) && title.isEmpty()) return "使用横线作为标记时，请填写区块标题"
        return null
    }

    fun blocks(content: String, format: ActionOptionsFormat? = null): List<RoleplayActionOptionsBlock> =
        parseBlocks(content, format, allowHeadingTypo = false)

    /** Format repair may fix a typo in a declared title while retaining the existing items. */
    fun blocksBeforeRepair(content: String, format: ActionOptionsFormat?): List<RoleplayActionOptionsBlock> =
        parseBlocks(content, format, allowHeadingTypo = true)

    private fun parseBlocks(
        content: String,
        format: ActionOptionsFormat?,
        allowHeadingTypo: Boolean,
    ): List<RoleplayActionOptionsBlock> {
        if (format != null && validationError(format) != null) return emptyList()
        if (content.isBlank()) return emptyList()
        val containerLines = mutableSetOf<Int>()
        val markdownFences = MarkdownCodeFences.findBlocks(content) { containerLines += it }
        val declaresFence = format != null && fenceMarker(format.opening.trim()) != null
        val fenced = if (format == null || declaresFence) {
            markdownFences.mapNotNull { fence ->
                if (fence.topLevel) fencedBlock(content, fence.range, format, allowHeadingTypo) else null
            }
        } else emptyList()
        if (declaresFence) return fenced
        val codeRanges = parseRoleplayTextSegments(content).filter {
            it.kind == RoleplaySegmentKind.STATUS && it.rawText.trimStart().startsWith("```")
        }.map { it.start until it.endExclusive } + markdownFences.map { it.range }

        val literal = format ?: defaultFormat
        val ranges = literalRanges(content, literal, codeRanges, containerLines)
        val legacy = if (format == null) roleplayLongDashRawRanges(content) else emptyList()
        val plain = (ranges + legacy).distinct().mapNotNull { range ->
            if (range.first in containerLines || content.lastIndexOf('\n', range.last) + 1 in containerLines) return@mapNotNull null
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
            if (hasHeading && !(if (format == null) heading(lines[1])
                else matchesDeclaredHeading(lines[1], literal.heading, allowHeadingTypo))
            ) {
                return@mapNotNull null
            }
            parseBlockItems(lines.subList(if (hasHeading) 2 else 1, lines.lastIndex), format)
                ?.let { RoleplayActionOptionsBlock(range, it) }
        }
        return (plain + fenced).sortedBy { it.range.first }
    }

    private fun fencedBlock(
        content: String,
        sourceRange: IntRange,
        format: ActionOptionsFormat?,
        allowHeadingTypo: Boolean,
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
                if (!matchesDeclaredHeading(body.firstOrNull().orEmpty(), format.heading, allowHeadingTypo)) return null
                body = body.drop(1)
            }
        } else {
            // Keep the pre-existing Desktop convention. Ordinary fence languages such as
            // text, markdown and python do not turn their contents into actions.
            if (!first.run.startsWith('`') || (first.info.isNotEmpty() && !heading(first.info))) return null
            if (body.firstOrNull()?.let(::heading) == true) body = body.drop(1)
            else if (first.info.isEmpty()) return null
        }

        // A second fence inside an option would also change legacy speech segmentation.
        if (body.any { "```" in it }) return null
        val options = parseBlockItems(body, format) ?: return null
        return RoleplayActionOptionsBlock(range, options)
    }

    private fun parseBlockItems(
        lines: List<String>,
        format: ActionOptionsFormat?
    ): List<RoleplayActionOption>? = parseItems(lines, format?.labels ?: defaultFormat.labels)?.takeIf { options ->
        format != null || (options.map { it.text }.distinct().size == options.size && options.none {
            markdownLink.containsMatchIn(it.text) || "```" in it.text || '<' in it.text || '>' in it.text
        })
    }

    private fun matchesDeclaredHeading(actual: String, expected: String, allowTypo: Boolean): Boolean {
        val value = actual.trim()
        val title = expected.trim()
        if (value == title) return true
        if (!allowTypo || title.length < 3 || value.length != title.length) return false
        val differing = title.indices.filter { title[it] != value[it] }
        return differing.size == 1 || (differing.size == 2 && differing[1] == differing[0] + 1 &&
            title[differing[0]] == value[differing[1]] && title[differing[1]] == value[differing[0]])
    }

    private fun fenceMarker(line: String): FenceMarker? {
        val mark = line.firstOrNull()?.takeIf { it == '`' || it == '~' } ?: return null
        val run = line.takeWhile { it == mark }.takeIf { it.length >= 3 } ?: return null
        return FenceMarker(run, line.substring(run.length).trim())
    }

    fun withoutExplicitOptions(content: String, format: ActionOptionsFormat? = null): String =
        textOutsideBlocks(content, blocks(content, format))

    private val chineseImageChoice = Regex(
        "^(?:(?:请(?:帮我)?|帮我)\\s*)?(?:画|绘制|生成)\\s*" +
            "(?:第\\s*([1-9][0-9]?|[一二三四五六七八九十]+)\\s*(?:项|条|个选项|个行动选项)|" +
            "(?:行动选项|选项)\\s*([A-Ta-t]))(?=$|\\s|[，。,.！!：:])",
    )
    private val englishImageChoice = Regex(
        "^(?:please\\s+)?(?:draw|illustrate|depict)\\s+(?:the\\s+)?(?:option|choice)\\s+" +
            "([A-T]|[1-9][0-9]?)(?=$|\\s|[,.!:])", RegexOption.IGNORE_CASE,
    )
    private val chineseOrdinals = "一 二 三 四 五 六 七 八 九 十 十一 十二 十三 十四 十五 十六 十七 十八 十九 二十".split(' ')

    fun imageScene(content: String, format: ActionOptionsFormat?, hint: String): String {
        val blocks = blocks(content, format)
        val scene = textOutsideBlocks(content, blocks)
        val trimmedHint = hint.trim()
        val label = chineseImageChoice.find(trimmedHint)?.let { it.groupValues[1].ifEmpty { it.groupValues[2] } }
            ?: englishImageChoice.find(trimmedHint)?.groupValues?.get(1)
            ?: return scene
        val ordinal = label.toIntOrNull() ?: (chineseOrdinals.indexOf(label) + 1).takeIf { it > 0 }
        val selected = blocks.singleOrNull()?.options?.singleOrNull { option ->
            if (ordinal != null) option.ordinal == ordinal else option.label.equals(label, ignoreCase = true)
        } ?: return scene
        return listOf(scene.trimEnd(), selected.text).filter(String::isNotBlank).joinToString("\n")
    }

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
        val closing = (format ?: defaultFormat).closing.trim()
        val last = content.trimEnd()
        val lastLine = last.substringAfterLast('\n')
        if (!isCodeIndented(lastLine) && lastLine.trim() == closing) return content
        val candidate = "$last\n$closing"
        val tail = blocks(candidate, format).lastOrNull() ?: return content
        return if (tail.range.last == candidate.lastIndex) candidate else content
    }

    private fun literalRanges(
        content: String,
        format: ActionOptionsFormat,
        codeRanges: List<IntRange>,
        containerLines: Set<Int>,
    ): List<IntRange> {
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
            if (isCodeIndented(line)) continue
            val marker = line.trim()
            if (lineStart in containerLines && (marker == opening || marker == closing)) continue
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
