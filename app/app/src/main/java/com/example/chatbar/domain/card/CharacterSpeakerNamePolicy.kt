package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.CharacterInfo

object CharacterSpeakerNamePolicy {
    fun normalizedKey(value: String): String = NamePolicy.normalize(value).lowercase()

    fun duplicateNames(characters: List<CharacterInfo>): List<String> {
        val seen = mutableSetOf<String>()
        return characters.mapNotNull { character ->
            val name = NamePolicy.normalize(character.name)
            if (name.isEmpty()) return@mapNotNull null
            val key = normalizedKey(name)
            if (seen.add(key)) null else name
        }
    }

    fun normalizeUnique(characters: List<CharacterInfo>): List<CharacterInfo> {
        val used = mutableListOf<String>()
        return characters.map { character ->
            val normalized = NamePolicy.normalize(character.name)
            if (normalized.isEmpty()) {
                character.copy(name = "")
            } else {
                val unique = if (used.any { NamePolicy.isSame(it, normalized) }) {
                    NamePolicy.nextCopyName(normalized, used)
                } else {
                    normalized
                }
                used += unique
                character.copy(name = unique)
            }
        }
    }
}
