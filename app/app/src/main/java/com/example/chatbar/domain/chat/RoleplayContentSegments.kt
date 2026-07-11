package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.SpeakerTagRename

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
    val endExclusive: Int,
    val speakerName: String? = null
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

private data class RoleplaySpeakerPrefix(
    val start: Int,
    val speakerName: String?
)

fun parseRoleplayTextSegments(content: String): List<RoleplayTextSegment> {
    val visible = visibleRoleplayText(content)
    if (visible.text.isBlank()) return emptyList()
    val segments = splitCodeFenceSegments(visible)
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
    return attachRoleplaySpeakerPrefixes(content, segments)
}

fun stripRoleplaySpeakerMarkers(content: String): String =
    roleplaySpeakerMarkerPattern.replace(content, "")

/** 移除状态栏与横线包裹的选项块，保留叙事、对白、心理和人物标记。 */
fun stripRoleplayStatusSegments(content: String): String =
    parseRoleplayTextSegments(content)
        .asSequence()
        .filter { it.kind == RoleplaySegmentKind.STATUS }
        .map { it.start to it.endExclusive }
        .distinct()
        .sortedByDescending { (start, _) -> start }
        .fold(content) { text, (start, endExclusive) ->
            replaceRoleplaySegmentContent(text, start, endExclusive, "")
        }

fun renameRoleplaySpeakerMarkers(
    content: String,
    renames: List<SpeakerTagRename>
): String {
    if (renames.isEmpty() || content.isEmpty()) return content
    return roleplaySpeakerMarkerPattern.replace(content) { match ->
        val currentName = match.groupValues[1].trim()
        val rename = renames.firstOrNull { item ->
            item.oldName.trim().equals(currentName, ignoreCase = true)
        } ?: return@replace match.value
        "<n=\"${rename.newName.trim()}\"/>"
    }
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
        val nextThought = findNextThoughtOpen(visible.text, cursor, end)
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
            val segmentEnd = findThoughtClose(visible.text, next, end)
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
            return index
        }
        index = text.indexOf('[', index + 1)
    }
    return -1
}

private fun findDialogueClose(text: String, open: Int, end: Int): Int {
    val bracketClose = text.indexOf(']', open + 1)
    if (bracketClose < 0 || bracketClose >= end) return -1
    if (bracketClose + 1 >= end || text[bracketClose + 1] != '(') return bracketClose + 1
    val parenClose = text.indexOf(')', bracketClose + 2)
    if (parenClose < 0 || parenClose >= end) return bracketClose + 1
    return parenClose + 1
}

private fun findNextThoughtOpen(text: String, start: Int, end: Int): Int {
    val open = text.indexOf('『', start)
    return open.takeIf { it >= 0 && it < end } ?: -1
}

private fun findThoughtClose(text: String, open: Int, end: Int): Int {
    val close = text.indexOf('』', open + 1)
    return if (close >= 0 && close < end) close + 1 else end
}

private fun attachRoleplaySpeakerPrefixes(
    content: String,
    segments: List<RoleplayTextSegment>
): List<RoleplayTextSegment> {
    val result = mutableListOf<RoleplayTextSegment>()
    val visibleRawIndexes = visibleRoleplayText(content).rawIndexes.toHashSet()
    var lastSpeakerName: String? = null
    var pendingSpeakerPrefix: RoleplaySpeakerPrefix? = null
    segments.forEach { segment ->
        if (segment.kind != RoleplaySegmentKind.DIALOGUE && segment.kind != RoleplaySegmentKind.THOUGHT) {
            result += segment
            if (segment.kind == RoleplaySegmentKind.NARRATION && !isLongDashWrappedText(segment.rawText)) {
                findLastRoleplaySpeakerPrefix(
                    content = content,
                    start = segment.start,
                    endExclusive = segment.endExclusive,
                    visibleRawIndexes = visibleRawIndexes
                )?.let { prefix ->
                    pendingSpeakerPrefix?.let { pending ->
                        hideRoleplaySpeakerMarkerInNarration(result, pending)
                    }
                    pendingSpeakerPrefix = prefix
                }
            }
            return@forEach
        }

        val directPrefix = findRoleplaySpeakerPrefix(content, segment.start)
        val prefix = directPrefix ?: pendingSpeakerPrefix
        if (prefix == null) {
            result += segment.copy(speakerName = lastSpeakerName)
            return@forEach
        }

        if (directPrefix != null) {
            val previous = result.lastOrNull()
            if (previous?.kind == RoleplaySegmentKind.NARRATION && previous.endExclusive == segment.start) {
                val removedLength = segment.start - prefix.start
                val trimmedRaw = previous.rawText.dropLast(removedLength.coerceAtMost(previous.rawText.length))
                val trimmedDisplay = previous.displayText.dropLast(removedLength.coerceAtMost(previous.displayText.length))
                result.removeAt(result.lastIndex)
                if (trimmedRaw.isNotBlank() || trimmedDisplay.isNotBlank()) {
                    result += previous.copy(
                        rawText = trimmedRaw,
                        displayText = trimmedDisplay,
                        endExclusive = prefix.start
                    )
                }
            }
        } else {
            hideRoleplaySpeakerMarkerInNarration(result, prefix)
        }

        result += segment.copy(
            rawText = if (directPrefix != null) content.substring(prefix.start, segment.endExclusive) else segment.rawText,
            start = if (directPrefix != null) prefix.start else segment.start,
            speakerName = prefix.speakerName
        )
        prefix.speakerName
            ?.takeIf(String::isNotEmpty)
            ?.let { lastSpeakerName = it }
        pendingSpeakerPrefix = null
    }
    return result
}

private fun hideRoleplaySpeakerMarkerInNarration(
    segments: MutableList<RoleplayTextSegment>,
    prefix: RoleplaySpeakerPrefix
) {
    val index = segments.indexOfLast { segment ->
        segment.kind == RoleplaySegmentKind.NARRATION &&
            prefix.start in segment.start until segment.endExclusive
    }
    if (index < 0) return
    val segment = segments[index]
    segments[index] = segment.copy(displayText = stripRoleplaySpeakerMarkers(segment.displayText))
}

private fun findLastRoleplaySpeakerPrefix(
    content: String,
    start: Int,
    endExclusive: Int,
    visibleRawIndexes: Set<Int>
): RoleplaySpeakerPrefix? {
    val safeStart = start.coerceIn(0, content.length)
    val safeEnd = endExclusive.coerceIn(safeStart, content.length)
    val match = roleplaySpeakerMarkerPattern
        .findAll(content.substring(safeStart, safeEnd))
        .lastOrNull { match -> safeStart + match.range.first in visibleRawIndexes }
        ?: return null
    return RoleplaySpeakerPrefix(
        start = safeStart + match.range.first,
        speakerName = match.groupValues[1].trim()
    )
}

private fun findRoleplaySpeakerPrefix(content: String, segmentStart: Int): RoleplaySpeakerPrefix? {
    var markerEnd = segmentStart.coerceIn(0, content.length)
    while (markerEnd > 0 && content[markerEnd - 1].isWhitespace()) markerEnd--
    if (markerEnd <= 0) return null

    val match = roleplaySpeakerMarkerPattern.findAll(content.substring(0, markerEnd)).lastOrNull()
        ?: return null
    if (match.range.last + 1 != markerEnd) return null
    return RoleplaySpeakerPrefix(
        start = match.range.first,
        speakerName = match.groupValues[1].trim()
    )
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
private val roleplaySpeakerMarkerPattern = Regex(
    pattern = "[<＜]\\s*[nｎ]\\s*[=＝]\\s*[\"“”＂]([^\\r\\n\"“”＂]*?)[\"“”＂]\\s*[/／]?\\s*[>＞]",
    option = RegexOption.IGNORE_CASE
)
