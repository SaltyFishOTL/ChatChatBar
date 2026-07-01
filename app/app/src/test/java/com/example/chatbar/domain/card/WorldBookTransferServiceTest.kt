package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.WorldBookPosition
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldBookTransferServiceTest {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
    }
    private val service = WorldBookTransferService(json)

    @Test
    fun sillyTavernWorldInfoObjectEntriesDecodeNativeFields() {
        val pkg = service.decode(
            """
            {
              "name": "ST World",
              "entries": {
                "7": {
                  "comment": "Gate",
                  "key": ["door"],
                  "keysecondary": ["silver"],
                  "content": "Unlocked lore",
                  "order": 42,
                  "position": "after_char",
                  "disable": false,
                  "selective": true,
                  "selectiveLogic": 3,
                  "caseSensitive": true,
                  "matchWholeWords": true,
                  "depth": 4,
                  "extensions": { "group": "locks", "sticky": 2 }
                }
              }
            }
            """.trimIndent()
        )

        val entry = pkg.book.entries.single()
        assertEquals("ST World", pkg.book.name)
        assertEquals(listOf("door"), entry.keys)
        assertEquals(listOf("silver"), entry.secondaryKeys)
        assertEquals(42, entry.insertionOrder)
        assertEquals(WorldBookPosition.AFTER_CHAR, entry.position)
        assertTrue(entry.enabled)
        assertTrue(entry.selective)
        assertEquals(3, entry.selectiveLogic)
        assertTrue(entry.caseSensitive)
        assertEquals(true, entry.matchWholeWords)
        assertEquals(4, entry.scanDepth)
        assertEquals("locks", entry.group)
        assertEquals(2, entry.sticky)
        assertTrue(entry.extensions.contains("rawJson"))
    }

    @Test
    fun sillyTavernCharacterBookArrayEntriesDecodeEmbeddedFields() {
        val book = service.decodeCharacterBook(
            """
            {
              "name": "Character Book",
              "scan_depth": 6,
              "entries": [
                {
                  "name": "Moon",
                  "keys": ["moon"],
                  "secondary_keys": ["night"],
                  "content": "Moon lore",
                  "insertion_order": 9,
                  "position": "before_char",
                  "enabled": false
                }
              ]
            }
            """.trimIndent(),
            fallbackName = "Fallback"
        )

        val entry = book.entries.single()
        assertEquals("Character Book", book.name)
        assertEquals(6, book.scanDepth)
        assertEquals(listOf("moon"), entry.keys)
        assertEquals(listOf("night"), entry.secondaryKeys)
        assertEquals(9, entry.insertionOrder)
        assertEquals(WorldBookPosition.BEFORE_CHAR, entry.position)
        assertFalse(entry.enabled)
    }

    @Test
    fun unsupportedLorebookShapesReturnClearError() {
        val error = runCatching {
            service.decode("""{"kind":"risu","items":[]}""")
        }.exceptionOrNull()

        assertTrue(error is IllegalStateException)
        assertTrue(error?.message.orEmpty().contains("暂不支持"))
    }
}
