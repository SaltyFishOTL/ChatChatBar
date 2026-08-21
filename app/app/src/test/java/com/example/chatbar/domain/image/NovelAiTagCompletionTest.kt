package com.example.chatbar.domain.image

import org.junit.Assert.assertEquals
import org.junit.Test

class NovelAiTagCompletionTest {
    @Test
    fun `fragment starts after chinese comma and newline`() {
        assertEquals("blue ha", NovelAiTagCompletion.activeFragment("1girl，blue ha", 13)?.query)
        assertEquals("night", NovelAiTagCompletion.activeFragment("1girl\nnight", 11)?.query)
    }

    @Test
    fun `insert preserves weights brackets and following tags`() {
        val source = "1girl, {{1.2::blue ha::}}, solo"
        val cursor = source.indexOf("::}}")
        val result = NovelAiTagCompletion.insert(source, cursor, "blue_hair")
        assertEquals("1girl, {{1.2::blue_hair::}}, solo", result.text)
        assertEquals(result.text.indexOf("blue_hair") + "blue_hair".length, result.cursor)
    }
}
