package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.WorldBook

object WorldBookReusePolicy {
    fun findReusable(source: WorldBook, existing: Iterable<WorldBook>): WorldBook? {
        val sourcePresetKey = source.sourcePresetKey?.takeIf { it.isNotBlank() }
        if (sourcePresetKey != null) {
            existing.firstOrNull { it.sourcePresetKey == sourcePresetKey }?.let { return it }
        }

        val sourceName = source.name.takeIf { it.isNotBlank() } ?: return null
        return existing.firstOrNull { NamePolicy.isSame(it.name, sourceName) }
    }
}
