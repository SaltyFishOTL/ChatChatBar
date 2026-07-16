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
            legacyReferenceNodes.mapNotNullTo(this) { node ->
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

    private fun normalizeBody(body: String): String? = body
        .lineSequence()
        .map(String::trim)
        .filter(String::isNotEmpty)
        .joinToString(" ")
        .takeIf(String::isNotEmpty)
}
