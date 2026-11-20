package com.example.chatbar.domain.memory

import com.example.chatbar.data.local.entity.MemoryNode
import com.example.chatbar.data.local.entity.MemoryTimelineEntry
import com.example.chatbar.domain.prompt.PromptTemplates

object MemoryArchiveInjectionPolicy {
    fun render(
        activeNodes: List<MemoryNode>,
        legacyReferenceNodes: List<MemoryNode>,
        timeline: List<MemoryTimelineEntry>
    ): String {
        val lines = buildList {
            MemoryTimelinePolicy.sortNodes(activeNodes, timeline).mapNotNullTo(this) { node ->
                normalizeBody(node.body)
            }
            legacyReferenceNodes.filterNot(MemoryNode::actionProjectionWithheld).mapNotNullTo(this) { node ->
                normalizeBody(node.body)?.let { body ->
                    "${PromptTemplates.MEMORY_LEGACY_REFERENCE_WARNING} $body"
                }
            }
        }
        if (lines.isEmpty()) return ""
        return buildString {
            appendLine("【${PromptTemplates.SECTION_MEMORY_ARCHIVE}】")
            append(lines.joinToString("\n"))
        }
    }

    /** A legacy source marker is a through-boundary, and absent evidence cannot establish scope. */
    internal fun withholdUnverifiedActionReferences(
        references: List<MemoryNode>,
        timeline: List<MemoryTimelineEntry>,
        projectedActionSources: Set<String>,
    ): List<MemoryNode> {
        if (references.isEmpty() || projectedActionSources.isEmpty()) return references
        val orderBySourceId = timeline.associate { it.sourceTurnId to it.sourceOrder }
        val earliestAffectedOrder = if (projectedActionSources.all { it in orderBySourceId }) {
            projectedActionSources.minOf(orderBySourceId::getValue)
        } else null
        return references.map { reference ->
            if (reference.actionProjectionWithheld) return@map reference
            val throughOrder = if (reference.sourceTurnIds.all { it in orderBySourceId }) {
                reference.sourceTurnIds.maxOfOrNull(orderBySourceId::getValue)
            } else null
            if (throughOrder != null && earliestAffectedOrder != null && throughOrder < earliestAffectedOrder) reference
            else reference.copy(actionProjectionWithheld = true)
        }
    }

    private fun normalizeBody(body: String): String? = body
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .joinToString(" ")
        .takeIf(String::isNotEmpty)
}
