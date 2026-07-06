package com.example.chatbar.domain.community

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class CommunityPackagePolicyTest {

    @Test
    fun validatesCharacterPackage() {
        val raw = """
            {
              "schemaVersion": 5,
              "card": {
                "name": "Alice",
                "characters": [
                  { "name": "Alice" }
                ]
              },
              "documents": [],
              "images": {},
              "worldBooks": []
            }
        """.trimIndent()

        CommunityPackagePolicy.validate(CommunityItemType.CHARACTER, raw)

        assertEquals(5, CommunityPackagePolicy.schemaVersion(raw))
        assertEquals(64, CommunityPackagePolicy.sha256(raw.toByteArray()).length)
    }

    @Test
    fun validatesFormatPackage() {
        val raw = """
            {
              "schemaVersion": 1,
              "name": "Roleplay Format",
              "content": "{{user}}: hello"
            }
        """.trimIndent()

        CommunityPackagePolicy.validate(CommunityItemType.FORMAT, raw)

        assertEquals(1, CommunityPackagePolicy.schemaVersion(raw))
    }

    @Test
    fun validatesWorldBookPackage() {
        val raw = """
            {
              "schemaVersion": 1,
              "book": {
                "id": "world-1",
                "name": "Lore",
                "entries": []
              }
            }
        """.trimIndent()

        CommunityPackagePolicy.validate(CommunityItemType.WORLD_BOOK, raw)

        assertEquals(1, CommunityPackagePolicy.schemaVersion(raw))
    }

    @Test
    fun rejectsInvalidFormatPackage() {
        val raw = """
            {
              "schemaVersion": 1,
              "name": "Empty",
              "content": ""
            }
        """.trimIndent()

        assertThrows(IllegalArgumentException::class.java) {
            CommunityPackagePolicy.validate(CommunityItemType.FORMAT, raw)
        }
    }
}
