package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.*
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class PresetCharacterDependenciesTest {
    private val entry = PresetEntry("character", PresetType.CHARACTER, 1, "fixture", "fixture",
        worldBookPresetKeys = listOf("world"), defaultFormatPresetKey = "format")
    private val entries = listOf(entry,
        PresetEntry("world", PresetType.WORLD_BOOK, 2, "fixture", "fixture"),
        PresetEntry("format", PresetType.FORMAT, 3, "fixture", "fixture"))
    private val original = CharacterCardPackage(card = PackagedCharacterCard("synthetic"))

    @Test fun declaredDependenciesAreIncludedWithStablePresetIdentity() = runTest {
        val result = resolvePresetCharacterDependencies(original, entry, entries,
            { WorldBookPackage(book = WorldBook("world-id", "synthetic world")) },
            { FormatCardPackage(name = "synthetic format", content = "fixture") })
        assertEquals("world", result.worldBooks.single().sourcePresetKey)
        assertEquals(2, result.worldBooks.single().sourcePresetVersion)
        assertEquals("format", result.defaultFormatCard?.sourcePresetKey)
        assertEquals(3, result.defaultFormatCard?.sourcePresetVersion)
        val embedded = resolvePresetCharacterDependencies(result, entry, entries,
            { WorldBookPackage(book = WorldBook("another-id", "synthetic world")) },
            { error("embedded format should win") })
        assertEquals(result, embedded)
    }

    @Test fun missingDependencyFailsBeforeImportInsteadOfDroppingBinding() = runTest {
        assertTrue(runCatching {
            resolvePresetCharacterDependencies(original, entry, listOf(entry),
                { error("unreachable") }, { error("unreachable") })
        }.isFailure)
    }
}
