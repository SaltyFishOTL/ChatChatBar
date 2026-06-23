package com.example.chatbar.domain.card

object NamePolicy {
    fun normalize(value: String): String = value.trim()

    fun isSame(left: String, right: String): Boolean =
        normalize(left).equals(normalize(right), ignoreCase = true)

    fun conflict(name: String, existing: Iterable<Pair<String, String>>, excludingId: String? = null): Pair<String, String>? =
        existing.firstOrNull { (id, existingName) -> id != excludingId && isSame(name, existingName) }

    fun nextCopyName(baseName: String, existingNames: Iterable<String>): String {
        val normalized = normalize(baseName)
        val used = existingNames.map(::normalize).toSet()
        var index = 2
        while (used.any { it.equals("$normalized ($index)", ignoreCase = true) }) index++
        return "$normalized ($index)"
    }
}
