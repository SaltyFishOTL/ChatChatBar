package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole

enum class RoleplaySegmentKind {
    NARRATION,
    DIALOGUE,
    THOUGHT,
    STATUS
}

data class RoleplayTextSegment(
    val kind: RoleplaySegmentKind,
    val rawText: String,
    val displayText: String,
    val start: Int,
    val endExclusive: Int
)

data class RoleplaySegmentEditOutcome(
    val message: ChatMessage?,
    val deleteMemoryForMessage: Boolean
)

private data class VisibleRoleplayText(
    val text: String,
    val rawIndexes: IntArray
)

private data class VisibleRange(
    val start: Int,
    val endExclusive: Int
)

fun parseRoleplayTextSegments(content: String): List<RoleplayTextSegment> {
    val visible = visibleRoleplayText(content)
    if (visible.text.isBlank()) return emptyList()
    return splitCodeFenceSegments(visible)
        .flatMap { segment ->
            if (segment.kind == RoleplaySegmentKind.STATUS) {
                listOf(segment)
            } else {
                splitDashFenceSegments(segment, visible)
            }
        }
        .flatMap { segment ->
            if (segment.kind == RoleplaySegmentKind.STATUS) {
                listOf(segment)
            } else {
                splitLongDashNarrationSegments(segment, visible)
            }
        }
        .flatMap { segment ->
            if (segment.kind == RoleplaySegmentKind.STATUS || isLongDashWrappedText(segment.rawText)) {
                listOf(segment)
            } else {
                splitRoleplayMarkers(segment, visible)
            }
        }
        .filter { it.rawText.isNotBlank() || it.displayText.isNotBlank() }
}

fun roleplayImageBlockId(messageId: String, imageIndex: Int): String =
    "$messageId::image::$imageIndex"

fun roleplayTextBlockId(messageId: String, segmentIndex: Int): String =
    "$messageId::text::$segmentIndex"

fun roleplayLegacyTextBlockId(messageId: String): String =
    "$messageId::text::legacy"

fun roleplayBlockMessageId(blockId: String): String =
    blockId.substringBefore("::")

fun roleplayScreenshotBlockIds(
    message: ChatMessage,
    assistantSegmentedBubblesEnabled: Boolean = true
): List<String> {
    if (message.role != MessageRole.USER && message.role != MessageRole.ASSISTANT) return emptyList()
    val ids = mutableListOf<String>()
    message.images.forEachIndexed { index, _ -> ids += roleplayImageBlockId(message.id, index) }
    if (message.role == MessageRole.ASSISTANT && assistantSegmentedBubblesEnabled) {
        parseRoleplayTextSegments(message.displayContent).forEachIndexed { index, _ ->
            ids += roleplayTextBlockId(message.id, index)
        }
    } else if (message.displayContent.isNotBlank()) {
        ids += roleplayLegacyTextBlockId(message.id)
    }
    return ids
}

fun roleplayMessageContainsBlock(
    message: ChatMessage,
    blockId: String,
    assistantSegmentedBubblesEnabled: Boolean = true
): Boolean =
    blockId in roleplayScreenshotBlockIds(message, assistantSegmentedBubblesEnabled)

fun replaceRoleplaySegmentContent(
    content: String,
    start: Int,
    endExclusive: Int,
    replacement: String
): String {
    val safeStart = start.coerceIn(0, content.length)
    val safeEnd = endExclusive.coerceIn(safeStart, content.length)
    val updated = content.substring(0, safeStart) + replacement + content.substring(safeEnd)
    return if (replacement.isBlank()) cleanupAfterRoleplaySegmentDeletion(updated) else updated
}

fun cleanupAfterRoleplaySegmentDeletion(content: String): String =
    content
        .replace(Regex("[ \t]+\n"), "\n")
        .replace(Regex("\n{3,}"), "\n\n")
        .trim()

fun editRoleplayMessageSegment(
    message: ChatMessage,
    start: Int,
    endExclusive: Int,
    replacement: String,
    updatedAt: Long = System.currentTimeMillis()
): RoleplaySegmentEditOutcome {
    val updatedContent = replaceRoleplaySegmentContent(
        content = message.displayContent,
        start = start,
        endExclusive = endExclusive,
        replacement = replacement
    )
    if (updatedContent.isBlank() && message.images.isEmpty()) {
        return RoleplaySegmentEditOutcome(message = null, deleteMemoryForMessage = false)
    }
    return RoleplaySegmentEditOutcome(
        message = message.copy(
            content = updatedContent,
            alternatives = emptyList(),
            currentAlternativeIndex = 0,
            updatedAt = updatedAt
        ),
        deleteMemoryForMessage = updatedContent.isBlank()
    )
}

private fun visibleRoleplayText(content: String): VisibleRoleplayText {
    val result = StringBuilder(content.length)
    val indexes = mutableListOf<Int>()
    var cursor = 0
    while (cursor < content.length) {
        val open = content.indexOf(HIDDEN_COMMENT_OPEN, cursor)
        if (open < 0) {
            appendVisibleRange(content, cursor, content.length, result, indexes)
            break
        }

        var depth = 1
        var search = open + HIDDEN_COMMENT_OPEN.length
        var closedAt = -1

        while (search < content.length) {
            val nextOpen = content.indexOf(HIDDEN_COMMENT_OPEN, search)
            val nextClose = content.indexOf(HIDDEN_COMMENT_CLOSE, search)
            if (nextClose < 0) break

            if (nextOpen >= 0 && nextOpen < nextClose) {
                depth++
                search = nextOpen + HIDDEN_COMMENT_OPEN.length
            } else {
                depth--
                search = nextClose + HIDDEN_COMMENT_CLOSE.length
                if (depth == 0) {
                    closedAt = search
                    break
                }
            }
        }

        if (closedAt < 0) {
            appendVisibleRange(content, cursor, content.length, result, indexes)
            break
        }

        val lineStart = content.lastIndexOf('\n', open - 1).let { if (it < 0) 0 else it + 1 }
        val openerStartsLine = content.substring(lineStart, open).all(::isHorizontalWhitespace)
        var afterCloseLineEnd = closedAt
        while (afterCloseLineEnd < content.length && isHorizontalWhitespace(content[afterCloseLineEnd])) {
            afterCloseLineEnd++
        }
        val closesLine = afterCloseLineEnd >= content.length || content[afterCloseLineEnd] == '\n'
        val standaloneBlock = openerStartsLine && closesLine

        appendVisibleRange(content, cursor, if (standaloneBlock) lineStart else open, result, indexes)
        cursor = if (standaloneBlock && afterCloseLineEnd < content.length) {
            afterCloseLineEnd + 1
        } else {
            closedAt
        }
    }
    return VisibleRoleplayText(result.toString(), indexes.toIntArray())
}

private fun appendVisibleRange(
    content: String,
    start: Int,
    endExclusive: Int,
    result: StringBuilder,
    indexes: MutableList<Int>
) {
    for (index in start until endExclusive) {
        result.append(content[index])
        indexes += index
    }
}

private fun splitCodeFenceSegments(visible: VisibleRoleplayText): List<RoleplayTextSegment> {
    val marker = "```"
    val segments = mutableListOf<RoleplayTextSegment>()
    var cursor = 0
    while (cursor < visible.text.length) {
        val open = visible.text.indexOf(marker, cursor)
        if (open < 0) {
            addTextSegment(segments, visible, RoleplaySegmentKind.NARRATION, cursor, visible.text.length)
            break
        }
        addTextSegment(segments, visible, RoleplaySegmentKind.NARRATION, cursor, open)
        val headerEnd = visible.text.indexOf('\n', open + marker.length)
        val bodyStart = if (headerEnd >= 0) headerEnd + 1 else open + marker.length
        val close = visible.text.indexOf(marker, bodyStart)
        if (close < 0) {
            addTextSegment(segments, visible, RoleplaySegmentKind.STATUS, open, visible.text.length)
            break
        }
        addStatusSegment(segments, visible, open, close + marker.length, bodyStart, close)
        cursor = close + marker.length
    }
    return segments
}

private val dashFencePattern = Regex("(?m)^[ \t]*---[ \t]*$")

private fun splitDashFenceSegments(
    segment: RoleplayTextSegment,
    visible: VisibleRoleplayText
): List<RoleplayTextSegment> {
    val text = visible.text.substring(segmentVisibleStart(segment, visible), segmentVisibleEnd(segment, visible))
    val matches = dashFencePattern.findAll(text).toList()
    if (matches.size < 2) return listOf(segment)

    val segments = mutableListOf<RoleplayTextSegment>()
    val offset = segmentVisibleStart(segment, visible)
    var cursor = 0
    var index = 0
    while (index < matches.size) {
        val fence = matches[index]
        if (index % 2 == 0) {
            addTextSegment(segments, visible, RoleplaySegmentKind.NARRATION, offset + cursor, offset + fence.range.first)
            cursor = fence.range.last + 1
        } else {
            val bodyStart = offset + cursor
            val bodyEnd = offset + fence.range.first
            val blockStart = offset + matches[index - 1].range.first
            val blockEnd = offset + fence.range.last + 1
            addStatusSegment(segments, visible, blockStart, blockEnd, bodyStart, bodyEnd)
            cursor = fence.range.last + 1
        }
        index++
    }
    if (cursor < text.length) {
        if (matches.size % 2 == 1) {
            val blockStart = offset + matches.last().range.first
            addStatusSegment(segments, visible, blockStart, offset + text.length, offset + cursor, offset + text.length)
        } else {
            addTextSegment(segments, visible, RoleplaySegmentKind.NARRATION, offset + cursor, offset + text.length)
        }
    }
    return segments.ifEmpty { listOf(segment) }
}

private fun splitLongDashNarrationSegments(
    segment: RoleplayTextSegment,
    visible: VisibleRoleplayText
): List<RoleplayTextSegment> {
    val start = segmentVisibleStart(segment, visible)
    val end = segmentVisibleEnd(segment, visible)
    val ranges = findLongDashWrappedRanges(visible.text, start, end)
    if (ranges.isEmpty()) return listOf(segment)

    val segments = mutableListOf<RoleplayTextSegment>()
    var cursor = start
    ranges.forEach { range ->
        addTextSegment(segments, visible, RoleplaySegmentKind.NARRATION, cursor, range.start)
        addTextSegment(segments, visible, RoleplaySegmentKind.NARRATION, range.start, range.endExclusive)
        cursor = range.endExclusive
    }
    addTextSegment(segments, visible, RoleplaySegmentKind.NARRATION, cursor, end)
    return segments.ifEmpty { listOf(segment) }
}

private fun findLongDashWrappedRanges(text: String, start: Int, end: Int): List<VisibleRange> {
    val ranges = mutableListOf<VisibleRange>()
    var lineStart = start
    while (lineStart < end) {
        val lineEnd = text.indexOf('\n', lineStart).takeIf { it >= 0 && it < end } ?: end
        val sameLineRanges = findSameLineLongDashWrappedRanges(text, lineStart, lineEnd)
        if (sameLineRanges.isNotEmpty()) {
            ranges += sameLineRanges
            lineStart = nextLineStart(lineEnd, end)
            continue
        }

        val openLine = lineOnlyLongDashRun(text, lineStart, lineEnd)
        if (openLine != null) {
            var searchLineStart = nextLineStart(lineEnd, end)
            var foundClose = false
            while (searchLineStart < end) {
                val searchLineEnd = text.indexOf('\n', searchLineStart).takeIf { it >= 0 && it < end } ?: end
                val closeLine = lineOnlyLongDashRun(text, searchLineStart, searchLineEnd)
                if (closeLine != null && hasLongDashWrappedBody(text, lineEnd, searchLineStart)) {
                    ranges += VisibleRange(lineStart, searchLineEnd)
                    lineStart = nextLineStart(searchLineEnd, end)
                    foundClose = true
                    break
                }
                searchLineStart = nextLineStart(searchLineEnd, end)
            }
            if (foundClose) continue
            if (hasLongDashWrappedBody(text, lineEnd, end)) {
                ranges += VisibleRange(lineStart, end)
                lineStart = end
                continue
            }
        }

        lineStart = nextLineStart(lineEnd, end)
    }
    return ranges
}

private fun findSameLineLongDashWrappedRanges(
    text: String,
    lineStart: Int,
    lineEnd: Int
): List<VisibleRange> {
    val ranges = mutableListOf<VisibleRange>()
    var cursor = lineStart
    while (cursor < lineEnd) {
        val open = nextLongDashRun(text, cursor, lineEnd) ?: break
        var close = nextLongDashRun(text, open.endExclusive, lineEnd)
        var found = false
        while (close != null) {
            if (hasLongDashWrappedBody(text, open.endExclusive, close.start)) {
                ranges += VisibleRange(open.start, close.endExclusive)
                cursor = close.endExclusive
                found = true
                break
            }
            close = nextLongDashRun(text, close.endExclusive, lineEnd)
        }
        if (!found) cursor = open.endExclusive
    }
    return ranges
}

private fun lineOnlyLongDashRun(text: String, lineStart: Int, lineEnd: Int): VisibleRange? {
    var start = lineStart
    while (start < lineEnd && isHorizontalWhitespace(text[start])) {
        start++
    }
    var end = lineEnd
    while (end > start && isHorizontalWhitespace(text[end - 1])) {
        end--
    }
    val run = nextLongDashRun(text, start, end) ?: return null
    return if (run.start == start && run.endExclusive == end) run else null
}

private fun nextLineStart(lineEnd: Int, end: Int): Int =
    if (lineEnd < end) lineEnd + 1 else end

private fun nextLongDashRun(text: String, start: Int, end: Int): VisibleRange? {
    var cursor = start
    while (cursor < end) {
        if (!isLongWrapperDash(text[cursor])) {
            cursor++
            continue
        }
        val runStart = cursor
        while (cursor < end && isLongWrapperDash(text[cursor])) {
            cursor++
        }
        if (cursor - runStart >= minLongWrapperDashCount(text[runStart])) {
            return VisibleRange(runStart, cursor)
        }
    }
    return null
}

private fun hasLongDashWrappedBody(text: String, start: Int, end: Int): Boolean {
    if (end <= start) return false
    return text.substring(start, end).any { !it.isWhitespace() && !isLongWrapperDash(it) }
}

private fun isLongDashWrappedText(text: String): Boolean {
    val trimmed = text.trim()
    val range = findLongDashWrappedRanges(trimmed, 0, trimmed.length).singleOrNull() ?: return false
    return range.start == 0 && range.endExclusive == trimmed.length
}

private fun splitRoleplayMarkers(
    segment: RoleplayTextSegment,
    visible: VisibleRoleplayText
): List<RoleplayTextSegment> {
    val start = segmentVisibleStart(segment, visible)
    val end = segmentVisibleEnd(segment, visible)
    val segments = mutableListOf<RoleplayTextSegment>()
    var cursor = start
    while (cursor < end) {
        val nextDialogue = findNextDialogueOpen(visible.text, cursor, end)
        val nextThought = visible.text.indexOf("『**", cursor).takeIf { it >= 0 && it < end } ?: -1
        val next = listOf(nextDialogue, nextThought).filter { it >= 0 }.minOrNull() ?: -1
        if (next < 0) {
            addTextSegment(segments, visible, RoleplaySegmentKind.NARRATION, cursor, end)
            break
        }
        addTextSegment(segments, visible, RoleplaySegmentKind.NARRATION, cursor, next)
        if (next == nextDialogue) {
            val close = findDialogueClose(visible.text, next, end)
            val segmentEnd = if (close >= 0) close else end
            addTextSegment(segments, visible, RoleplaySegmentKind.DIALOGUE, next, segmentEnd)
            cursor = segmentEnd
        } else {
            val close = visible.text.indexOf("**』", next + 3).takeIf { it >= 0 && it < end } ?: -1
            val segmentEnd = if (close >= 0) close + 3 else end
            addTextSegment(segments, visible, RoleplaySegmentKind.THOUGHT, next, segmentEnd)
            cursor = segmentEnd
        }
    }
    return segments.ifEmpty { listOf(segment) }
}

private fun addTextSegment(
    segments: MutableList<RoleplayTextSegment>,
    visible: VisibleRoleplayText,
    kind: RoleplaySegmentKind,
    start: Int,
    endExclusive: Int
) {
    if (endExclusive <= start) return
    val rawStart = visible.rawIndexes.getOrNull(start) ?: return
    val rawEnd = (visible.rawIndexes.getOrNull(endExclusive - 1) ?: return) + 1
    val rawText = rawText(visible, start, endExclusive)
    if (rawText.isBlank()) return
    segments += RoleplayTextSegment(
        kind = kind,
        rawText = rawText,
        displayText = rawText,
        start = rawStart,
        endExclusive = rawEnd
    )
}

private fun addStatusSegment(
    segments: MutableList<RoleplayTextSegment>,
    visible: VisibleRoleplayText,
    blockStart: Int,
    blockEnd: Int,
    bodyStart: Int,
    bodyEnd: Int
) {
    if (blockEnd <= blockStart) return
    val rawStart = visible.rawIndexes.getOrNull(blockStart) ?: return
    val rawEnd = (visible.rawIndexes.getOrNull(blockEnd - 1) ?: return) + 1
    val body = visible.text.substring(bodyStart.coerceAtMost(bodyEnd), bodyEnd.coerceAtLeast(bodyStart)).trim()
    val rawText = rawText(visible, blockStart, blockEnd)
    if (rawText.isBlank() && body.isBlank()) return
    segments += RoleplayTextSegment(
        kind = RoleplaySegmentKind.STATUS,
        rawText = rawText,
        displayText = body,
        start = rawStart,
        endExclusive = rawEnd
    )
}

private fun rawText(visible: VisibleRoleplayText, start: Int, endExclusive: Int): String =
    visible.text.substring(start, endExclusive)

private fun segmentVisibleStart(segment: RoleplayTextSegment, visible: VisibleRoleplayText): Int =
    visible.rawIndexes.indexOf(segment.start).takeIf { it >= 0 } ?: 0

private fun segmentVisibleEnd(segment: RoleplayTextSegment, visible: VisibleRoleplayText): Int =
    visible.rawIndexes.indexOf(segment.endExclusive - 1).takeIf { it >= 0 }?.plus(1) ?: visible.text.length

private fun findNextDialogueOpen(text: String, start: Int, end: Int): Int {
    var index = text.indexOf('[', start)
    while (index >= 0 && index < end) {
        if (index == 0 || text[index - 1] != '!') {
            val bracketClose = text.indexOf(']', index + 1)
            if (bracketClose < 0 || bracketClose >= end) return index
            if (bracketClose + 1 < end && text[bracketClose + 1] == '(') return index
        }
        index = text.indexOf('[', index + 1)
    }
    return -1
}

private fun findDialogueClose(text: String, open: Int, end: Int): Int {
    val bracketClose = text.indexOf(']', open + 1)
    if (bracketClose < 0 || bracketClose >= end) return -1
    if (bracketClose + 1 >= end || text[bracketClose + 1] != '(') return -1
    val parenClose = text.indexOf(')', bracketClose + 2)
    if (parenClose < 0 || parenClose >= end) return -1
    return parenClose + 1
}

private fun isHorizontalWhitespace(char: Char): Boolean =
    char == ' ' || char == '\t' || char == '\r'

private fun isLongWrapperDash(char: Char): Boolean =
    char == '-' || char == '\u2014' || char == '\u2015' || char == '\u2500' || char == '\uFF0D'

private fun minLongWrapperDashCount(char: Char): Int =
    if (char == '-') LONG_WRAPPER_MIN_ASCII_DASHES else LONG_WRAPPER_MIN_WIDE_DASHES

private const val LONG_WRAPPER_MIN_ASCII_DASHES = 6
private const val LONG_WRAPPER_MIN_WIDE_DASHES = 4
private const val HIDDEN_COMMENT_OPEN = "<!--"
private const val HIDDEN_COMMENT_CLOSE = "-->"
