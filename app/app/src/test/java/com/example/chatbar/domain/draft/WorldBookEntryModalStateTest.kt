package com.example.chatbar.domain.draft

import com.example.chatbar.data.local.entity.WorldBookEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldBookEntryModalStateTest {
    @Test
    fun `entry can save with name keys or content and rejects fully blank state`() {
        assertFalse(WorldBookEntryModalState().hasMeaningfulEntryData())
        assertTrue(WorldBookEntryModalState(name = "条目").hasMeaningfulEntryData())
        assertTrue(WorldBookEntryModalState(keys = "触发词").hasMeaningfulEntryData())
        assertTrue(WorldBookEntryModalState(content = "正文").hasMeaningfulEntryData())
    }

    @Test
    fun `editing preserves fields not exposed by modal`() {
        val original = WorldBookEntry(
            id = "entry",
            name = "旧名称",
            keys = listOf("旧词"),
            content = "",
            priority = 42,
            role = "system",
            comment = "导入注释",
            recursionLevel = 3,
            originalPosition = "before_char",
            matchCharacterDescription = true,
            characterFilter = listOf("角色A"),
            extensions = "{\"custom\":true}"
        )
        val state = WorldBookEntryModalState.from(0, original).copy(
            name = "新名称",
            keys = "新词, 别名"
        )

        val result = state.materialize(original)

        assertEquals("新名称", result.name)
        assertEquals(listOf("新词", "别名"), result.keys)
        assertEquals(42, result.priority)
        assertEquals("system", result.role)
        assertEquals("导入注释", result.comment)
        assertEquals(3, result.recursionLevel)
        assertEquals("before_char", result.originalPosition)
        assertTrue(result.matchCharacterDescription)
        assertEquals(listOf("角色A"), result.characterFilter)
        assertEquals("{\"custom\":true}", result.extensions)
    }
}
