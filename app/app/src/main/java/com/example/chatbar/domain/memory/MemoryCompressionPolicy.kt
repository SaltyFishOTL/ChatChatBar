package com.example.chatbar.domain.memory

import com.example.chatbar.data.local.entity.MemoryGap
import com.example.chatbar.data.local.entity.MemoryNode
import com.example.chatbar.data.local.entity.MemoryTier
import com.example.chatbar.data.local.entity.MemoryTimelineEntry

data class MemoryCompressionCandidate(
    val candidates: List<MemoryNode>,
    val minConsume: Int,
    val maxConsume: Int
)

object MemoryCompressionPolicy {
    const val LOWER_MIN_CONSUME = 4
    const val LOWER_MAX_CONSUME = 20
    const val LOWER_MAX_CANDIDATES = 25
    const val ERA_MIN_CONSUME = 3
    const val ERA_MAX_CONSUME = 10
    const val ERA_MAX_CANDIDATES = 15

    fun oldestContinuousLowerCandidate(
        nodes: List<MemoryNode>,
        expectedTier: MemoryTier,
        timeline: List<MemoryTimelineEntry>,
        gaps: List<MemoryGap>,
        newestRetainedOutsideCandidates: Boolean = false
    ): MemoryCompressionCandidate? {
        val sorted = MemoryTimelinePolicy.sortNodes(
            nodes.filter {
                it.tier == expectedTier &&
                    MemoryTimelinePolicy.isContinuous(it.sourceTurnIds, timeline, gaps)
            },
            timeline
        )
        val retainedCount = if (newestRetainedOutsideCandidates) 0 else 1
        val segment = firstContinuousSegment(
            nodes = sorted,
            timeline = timeline,
            gaps = gaps,
            maxSize = LOWER_MAX_CANDIDATES,
            minSize = LOWER_MIN_CONSUME + retainedCount
        )
        if (segment.size < LOWER_MIN_CONSUME + retainedCount) return null
        return MemoryCompressionCandidate(
            candidates = segment,
            minConsume = LOWER_MIN_CONSUME,
            maxConsume = minOf(LOWER_MAX_CONSUME, segment.size - retainedCount)
        )
    }

    /** Era由程序指定3–10条；尽量一次消费同等最低磨损级别连续前缀。 */
    fun forcedEraPrefix(candidate: MemoryCompressionCandidate): List<MemoryNode> {
        if (candidate.candidates.size < ERA_MIN_CONSUME) return emptyList()
        val baselineLevel = candidate.candidates.take(ERA_MIN_CONSUME)
            .maxOf { it.compressionLevel }
        val sameWearPrefix = candidate.candidates.takeWhile { it.compressionLevel <= baselineLevel }
        return sameWearPrefix.take(candidate.maxConsume)
            .takeIf { it.size >= candidate.minConsume }
            ?: candidate.candidates.take(candidate.minConsume)
    }

    fun eraCandidate(
        nodes: List<MemoryNode>,
        timeline: List<MemoryTimelineEntry>,
        gaps: List<MemoryGap>
    ): MemoryCompressionCandidate? {
        val sorted = MemoryTimelinePolicy.sortNodes(
            nodes.filter {
                it.tier == MemoryTier.ERA &&
                    MemoryTimelinePolicy.isContinuous(it.sourceTurnIds, timeline, gaps)
            },
            timeline
        )
        val segments = continuousSegments(sorted, timeline, gaps)
            .filter { it.size >= ERA_MIN_CONSUME }
        if (segments.isEmpty()) return null

        val fresh = segments.firstNotNullOfOrNull { segment ->
            segment.windowed(ERA_MIN_CONSUME, 1, partialWindows = false)
                .firstOrNull { window -> window.all { it.compressionLevel == 0 } }
                ?.let { window ->
                    val start = segment.indexOfFirst { it.id == window.first().id }
                    segment.drop(start).take(ERA_MAX_CANDIDATES)
                }
        }
        val selected = fresh ?: segments
            .flatMap { segment ->
                segment.windowed(ERA_MIN_CONSUME, 1, partialWindows = false).map { window ->
                    Triple(window.maxOf { it.compressionLevel }, rangeStart(window, timeline), segment to window)
                }
            }
            .minWithOrNull(compareBy<Triple<Int, Long, Pair<List<MemoryNode>, List<MemoryNode>>>> { it.first }
                .thenBy { it.second })
            ?.third
            ?.let { (segment, window) ->
                val start = segment.indexOfFirst { it.id == window.first().id }
                segment.drop(start).take(ERA_MAX_CANDIDATES)
            }
            .orEmpty()
        if (selected.size < ERA_MIN_CONSUME) return null
        return MemoryCompressionCandidate(
            candidates = selected,
            minConsume = ERA_MIN_CONSUME,
            maxConsume = minOf(ERA_MAX_CONSUME, selected.size)
        )
    }

    fun validateConsumedPrefix(
        candidate: MemoryCompressionCandidate,
        consumedIds: List<String>
    ): MemoryValidationResult {
        if (consumedIds.size !in candidate.minConsume..candidate.maxConsume) {
            return MemoryValidationResult.invalid("消费节点数不合法")
        }
        if (consumedIds.distinct().size != consumedIds.size) {
            return MemoryValidationResult.invalid("消费节点重复")
        }
        if (candidate.candidates.take(consumedIds.size).map { it.id } != consumedIds) {
            return MemoryValidationResult.invalid("只能消费候选最老连续前缀")
        }
        return MemoryValidationResult.Valid
    }

    private fun firstContinuousSegment(
        nodes: List<MemoryNode>,
        timeline: List<MemoryTimelineEntry>,
        gaps: List<MemoryGap>,
        maxSize: Int,
        minSize: Int
    ): List<MemoryNode> = continuousSegments(nodes, timeline, gaps)
        .firstOrNull { it.size >= minSize }
        .orEmpty()
        .take(maxSize)

    private fun continuousSegments(
        nodes: List<MemoryNode>,
        timeline: List<MemoryTimelineEntry>,
        gaps: List<MemoryGap>
    ): List<List<MemoryNode>> {
        if (nodes.isEmpty()) return emptyList()
        val result = mutableListOf<MutableList<MemoryNode>>()
        nodes.forEach { node ->
            val current = result.lastOrNull()
            val previous = current?.lastOrNull()
            val continuous = previous != null && areAdjacent(previous, node, timeline, gaps)
            if (current == null || !continuous) result += mutableListOf(node) else current += node
        }
        return result
    }

    private fun areAdjacent(
        left: MemoryNode,
        right: MemoryNode,
        timeline: List<MemoryTimelineEntry>,
        gaps: List<MemoryGap>
    ): Boolean {
        val leftRange = MemoryTimelinePolicy.range(left, timeline) ?: return false
        val rightRange = MemoryTimelinePolicy.range(right, timeline) ?: return false
        if (rightRange.startT != leftRange.endT + 1) return false
        return MemoryTimelinePolicy.isContinuous(
            listOf(left.sourceTurnIds.last(), right.sourceTurnIds.first()),
            timeline,
            gaps
        )
    }

    private fun rangeStart(nodes: List<MemoryNode>, timeline: List<MemoryTimelineEntry>): Long =
        nodes.firstOrNull()?.let { MemoryTimelinePolicy.range(it, timeline)?.startT } ?: Long.MAX_VALUE
}
