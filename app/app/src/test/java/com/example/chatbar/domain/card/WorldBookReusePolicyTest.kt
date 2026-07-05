package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.WorldBook
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class WorldBookReusePolicyTest {
    @Test
    fun reusesBySourcePresetKeyBeforeName() {
        val existing = listOf(
            book("name-match", "Same Name"),
            book("preset-match", "Renamed", sourcePresetKey = "mujica")
        )

        val reusable = WorldBookReusePolicy.findReusable(
            book("incoming", "Same Name", sourcePresetKey = "mujica"),
            existing
        )

        assertEquals("preset-match", reusable?.id)
    }

    @Test
    fun reusesByNameWhenPresetKeyMissing() {
        val existing = listOf(book("existing", "  Shared Lore  "))

        val reusable = WorldBookReusePolicy.findReusable(book("incoming", "shared lore"), existing)

        assertEquals("existing", reusable?.id)
    }

    @Test
    fun returnsNullWhenNoMatchingWorldBookExists() {
        val reusable = WorldBookReusePolicy.findReusable(
            book("incoming", "New Lore", sourcePresetKey = "new"),
            listOf(book("existing", "Old Lore", sourcePresetKey = "old"))
        )

        assertNull(reusable)
    }

    private fun book(
        id: String,
        name: String,
        sourcePresetKey: String? = null
    ) = WorldBook(id = id, name = name, sourcePresetKey = sourcePresetKey)
}
