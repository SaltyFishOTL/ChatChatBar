package com.example.chatbar.domain.voice

import com.example.chatbar.data.local.entity.VoiceAnchor
import com.example.chatbar.data.local.entity.VoiceAnchorState
import com.example.chatbar.domain.chat.RoleplaySegmentKind
import com.example.chatbar.domain.chat.RoleplayTextSegment
import com.example.chatbar.domain.chat.stripRoleplaySpeakerMarkers
import java.util.UUID
import kotlin.math.abs

enum class VoiceSourceScope {
    SEGMENT,
    WHOLE_MESSAGE
}

data class CurrentVoiceSegment(
    val segmentIndex: Int,
    val kind: RoleplaySegmentKind,
    val speakerName: String?,
    val spokenText: String,
    val start: Int,
    val endExclusive: Int,
    val sourceScope: VoiceSourceScope = VoiceSourceScope.SEGMENT
)

data class VoiceAnchorReconciliation(
    val state: VoiceAnchorState,
    val anchorReplacement: Map<String, String?>
)

object VoiceAnchorPolicy {
    fun eligibleSegments(
        content: String,
        includeNarration: Boolean = false
    ): List<CurrentVoiceSegment> =
        com.example.chatbar.domain.chat.parseRoleplayTextSegments(content)
            .mapIndexedNotNull { index, segment ->
                val eligible = segment.kind == RoleplaySegmentKind.DIALOGUE ||
                    segment.kind == RoleplaySegmentKind.THOUGHT ||
                    (includeNarration && segment.kind == RoleplaySegmentKind.NARRATION)
                if (!eligible) {
                    null
                } else {
                    val spokenText = spokenText(segment).takeIf(String::isNotBlank)
                        ?: return@mapIndexedNotNull null
                    CurrentVoiceSegment(
                        segmentIndex = index,
                        kind = segment.kind,
                        speakerName = segment.speakerName,
                        spokenText = spokenText,
                        start = segment.start,
                        endExclusive = segment.endExclusive
                    )
                }
            }

    fun promptContextText(content: String): String =
        com.example.chatbar.domain.chat.parseRoleplayTextSegments(content)
            .mapNotNull { segment ->
                val text = promptContextText(segment)
                text.takeIf(String::isNotBlank)
            }
            .joinToString("\n")
            .trim()

    fun wholeMessageSegment(content: String): CurrentVoiceSegment? {
        val spokenText = eligibleSegments(content, includeNarration = true)
            .joinToString("\n") { it.spokenText }
            .trim()
            .takeIf(String::isNotBlank)
            ?: return null
        return CurrentVoiceSegment(
            segmentIndex = WHOLE_MESSAGE_SEGMENT_INDEX,
            kind = RoleplaySegmentKind.NARRATION,
            speakerName = null,
            spokenText = spokenText,
            start = 0,
            endExclusive = content.length,
            sourceScope = VoiceSourceScope.WHOLE_MESSAGE
        )
    }

    fun initialState(
        messageId: String,
        content: String,
        messageVersionId: String? = null,
        sessionId: String = "",
        includeNarration: Boolean = false
    ): VoiceAnchorState =
        VoiceAnchorState(
            messageId = messageId,
            messageVersionId = messageVersionId,
            sessionId = sessionId,
            displayContentSnapshot = content,
            anchors = eligibleSegments(content, includeNarration).map(::newAnchor)
        )

    fun reconcile(
        old: VoiceAnchorState,
        newContent: String,
        includeNarration: Boolean = false
    ): VoiceAnchorReconciliation {
        val nextSegments = eligibleSegments(newContent, includeNarration)
        val anchorsAlreadyCurrent = old.displayContentSnapshot == newContent &&
            old.anchors.size == nextSegments.size &&
            old.anchors.zip(nextSegments).all { (anchor, segment) ->
                anchor.segmentKind == segment.kind.name &&
                    anchor.speakerName == segment.speakerName &&
                    anchor.sourceText == segment.spokenText &&
                    anchor.start == segment.start &&
                    anchor.endExclusive == segment.endExclusive
            }
        if (anchorsAlreadyCurrent) {
            return VoiceAnchorReconciliation(
                old,
                old.anchors.associate { it.id to it.id }
            )
        }
        val matches = align(
            old = old.anchors,
            next = nextSegments,
            offsetMapper = CharacterDiffOffsetMapper(
                oldText = old.displayContentSnapshot,
                newText = newContent
            )
        )
        val nextAnchors = ArrayList<VoiceAnchor>(nextSegments.size)
        val oldToNew = mutableMapOf<String, String>()
        nextSegments.forEachIndexed { nextIndex, segment ->
            val oldIndex = matches.entries.firstOrNull { it.value == nextIndex }?.key
            val anchor = oldIndex?.let { index ->
                old.anchors[index].copy(
                    segmentKind = segment.kind.name,
                    speakerName = segment.speakerName,
                    sourceText = segment.spokenText,
                    start = segment.start,
                    endExclusive = segment.endExclusive
                )
            } ?: newAnchor(segment)
            nextAnchors += anchor
            if (oldIndex != null) oldToNew[old.anchors[oldIndex].id] = anchor.id
        }

        val replacement = mutableMapOf<String, String?>()
        var previousSurvivor: String? = null
        old.anchors.forEach { oldAnchor ->
            val survivor = oldToNew[oldAnchor.id]
            if (survivor != null) {
                previousSurvivor = survivor
                replacement[oldAnchor.id] = survivor
            } else {
                replacement[oldAnchor.id] = previousSurvivor
            }
        }
        return VoiceAnchorReconciliation(
            state = VoiceAnchorState(
                messageId = old.messageId,
                messageVersionId = old.messageVersionId,
                sessionId = old.sessionId,
                displayContentSnapshot = newContent,
                anchors = nextAnchors,
                updatedAt = System.currentTimeMillis()
            ),
            anchorReplacement = replacement
        )
    }

    private fun align(
        old: List<VoiceAnchor>,
        next: List<CurrentVoiceSegment>,
        offsetMapper: CharacterDiffOffsetMapper
    ): Map<Int, Int> {
        val rows = old.size + 1
        val columns = next.size + 1
        val cost = Array(rows) { IntArray(columns) }
        for (i in 1 until rows) cost[i][0] = i * GAP_COST
        for (j in 1 until columns) cost[0][j] = j * GAP_COST
        for (i in 1 until rows) {
            for (j in 1 until columns) {
                val substitute = cost[i - 1][j - 1] +
                    substitutionCost(old[i - 1], next[j - 1], offsetMapper)
                val delete = cost[i - 1][j] + GAP_COST
                val insert = cost[i][j - 1] + GAP_COST
                cost[i][j] = minOf(substitute, delete, insert)
            }
        }

        val result = mutableMapOf<Int, Int>()
        var i = old.size
        var j = next.size
        while (i > 0 || j > 0) {
            val diagonalCost = if (i > 0 && j > 0) {
                cost[i - 1][j - 1] +
                    substitutionCost(old[i - 1], next[j - 1], offsetMapper)
            } else {
                Int.MAX_VALUE
            }
            val exactDiagonal = i > 0 && j > 0 &&
                substitutionCost(old[i - 1], next[j - 1], offsetMapper) == 0 &&
                cost[i][j] == diagonalCost
            val ordinaryDiagonal = i > 0 && j > 0 &&
                cost[i][j] == diagonalCost &&
                substitutionCost(old[i - 1], next[j - 1], offsetMapper) < GAP_COST * 2
            when {
                exactDiagonal || ordinaryDiagonal -> {
                    result[i - 1] = j - 1
                    i--
                    j--
                }
                i > 0 && cost[i][j] == cost[i - 1][j] + GAP_COST -> i--
                j > 0 -> j--
            }
        }
        return result
    }

    private fun substitutionCost(
        old: VoiceAnchor,
        next: CurrentVoiceSegment,
        offsetMapper: CharacterDiffOffsetMapper
    ): Int {
        if (old.segmentKind != next.kind.name) return GAP_COST * 2 + 1
        val textCost = if (normalize(old.sourceText) == normalize(next.spokenText)) 0 else 2
        val mappedStart = offsetMapper.map(old.start)
        val distance = abs(mappedStart - next.start)
        val positionCost = when {
            distance <= 8 -> 0
            distance <= 64 -> 1
            else -> 2
        }
        return textCost + positionCost
    }

    private fun newAnchor(segment: CurrentVoiceSegment): VoiceAnchor =
        VoiceAnchor(
            id = UUID.randomUUID().toString(),
            segmentKind = segment.kind.name,
            speakerName = segment.speakerName,
            sourceText = segment.spokenText,
            start = segment.start,
            endExclusive = segment.endExclusive,
            sourceOrder = segment.start.toLong()
        )

    private fun spokenText(segment: RoleplayTextSegment): String {
        val trimmed = segment.displayText.trim()
        val spoken = when (segment.kind) {
            RoleplaySegmentKind.DIALOGUE -> dialogueParts(trimmed).first
            RoleplaySegmentKind.THOUGHT ->
                stripThoughtBubbleBorders(trimmed)
            RoleplaySegmentKind.NARRATION ->
                stripRoleplaySpeakerMarkers(trimmed)
            RoleplaySegmentKind.STATUS -> ""
        }
        return cleanVoiceMarkup(spoken)
    }

    private fun promptContextText(segment: RoleplayTextSegment): String {
        val trimmed = segment.displayText.trim()
        val text = when (segment.kind) {
            RoleplaySegmentKind.DIALOGUE -> {
                val (spoken, direction) = dialogueParts(trimmed)
                listOfNotNull(spoken, direction)
                    .filter(String::isNotBlank)
                    .joinToString("\n")
            }
            RoleplaySegmentKind.THOUGHT -> stripThoughtBubbleBorders(trimmed)
            RoleplaySegmentKind.NARRATION -> stripRoleplaySpeakerMarkers(trimmed)
            RoleplaySegmentKind.STATUS -> trimmed
        }
        return cleanVoiceMarkup(text)
    }

    private fun dialogueParts(value: String): Pair<String, String?> {
        if (value.firstOrNull() != '[' && value.firstOrNull() != '［') {
            return value to null
        }
        val closing = value.indexOfFirst { it == ']' || it == '］' }
        if (closing <= 0) return value.drop(1) to null
        val spoken = value.substring(1, closing)
        val suffix = value.substring(closing + 1).trim()
        if (suffix.length < 2) return spoken to null
        val wrappedDirection =
            (suffix.first() == '(' || suffix.first() == '（') &&
                (suffix.last() == ')' || suffix.last() == '）')
        val direction = if (wrappedDirection) suffix.drop(1).dropLast(1).trim() else suffix
        return spoken to direction.takeIf(String::isNotBlank)
    }

    private fun stripThoughtBubbleBorders(value: String): String {
        val hasOpening = value.firstOrNull()?.let { it in THOUGHT_OPENINGS } == true
        val withoutOpening = if (hasOpening) value.drop(1) else value
        val hasClosing = withoutOpening.lastOrNull()?.let { it in THOUGHT_CLOSINGS } == true
        return if (hasClosing) {
            withoutOpening.dropLast(1).trim()
        } else {
            withoutOpening.trim()
        }
    }

    private fun cleanVoiceMarkup(value: String): String =
        value
            .replace(Regex("!\\[([^]]*)]\\([^)]*\\)"), "$1")
            .replace(Regex("\\[([^]]+)]\\([^)]*\\)"), "$1")
            .replace(Regex("[*_~`]"), "")
            .trim()

    private fun normalize(value: String): String =
        value.replace(Regex("\\s+"), " ").trim()

    private const val GAP_COST = 3
    private val THOUGHT_OPENINGS = setOf('『', '「', '｢')
    private val THOUGHT_CLOSINGS = setOf('』', '」', '｣')
    const val WHOLE_MESSAGE_SEGMENT_INDEX = -1
}

private class CharacterDiffOffsetMapper(
    oldText: String,
    newText: String
) {
    private val oldLength = oldText.length
    private val newLength = newText.length
    private val commonPrefix = oldText.commonPrefixWith(newText).length
    private val commonSuffix = oldText
        .substring(commonPrefix)
        .commonSuffixWith(newText.substring(commonPrefix))
        .length
    private val oldChangedEnd = oldLength - commonSuffix
    private val newChangedEnd = newLength - commonSuffix

    fun map(oldOffset: Int): Int {
        val safeOffset = oldOffset.coerceIn(0, oldLength)
        if (oldChangedEnd == commonPrefix && safeOffset == commonPrefix) {
            return newChangedEnd
        }
        if (safeOffset <= commonPrefix) return safeOffset
        if (safeOffset >= oldChangedEnd) {
            return (newChangedEnd + safeOffset - oldChangedEnd).coerceIn(0, newLength)
        }
        val oldChangedLength = (oldChangedEnd - commonPrefix).coerceAtLeast(1)
        val newChangedLength = (newChangedEnd - commonPrefix).coerceAtLeast(0)
        val relative = (safeOffset - commonPrefix).toDouble() / oldChangedLength
        return (commonPrefix + relative * newChangedLength)
            .toInt()
            .coerceIn(0, newLength)
    }
}
