package com.example.chatbar.domain.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoleplayContentSegmentsTest {
    @Test
    fun `unmarked dialogue inherits previous speaker marker`() {
        val segments = parseRoleplayTextSegments(
            """
            <n="林雾"/>[第一句]()
            [第二句]()
            """.trimIndent()
        ).filter { it.kind == RoleplaySegmentKind.DIALOGUE }

        assertEquals(listOf("林雾", "林雾"), segments.map { it.speakerName })
        assertEquals("[第二句]()", segments[1].rawText)
    }

    @Test
    fun `unmarked thought inherits previous speaker marker after narration`() {
        val segments = parseRoleplayTextSegments(
            """
            <n="林雾"/>[第一句]()
            她停顿了一下。
            『**第二段内心**』
            """.trimIndent()
        )

        val thought = segments.single { it.kind == RoleplaySegmentKind.THOUGHT }
        assertEquals("林雾", thought.speakerName)
        assertEquals("『**第二段内心**』", thought.rawText)
    }

    @Test
    fun `first unmarked dialogue keeps legacy speaker fallback`() {
        val dialogue = parseRoleplayTextSegments("[第一句]()")
            .single { it.kind == RoleplaySegmentKind.DIALOGUE }

        assertNull(dialogue.speakerName)
    }

    @Test
    fun `empty speaker marker does not replace previous valid inherited speaker`() {
        val dialogues = parseRoleplayTextSegments(
            """
            <n="林雾"/>[第一句]()
            <n=""/>[第二句]()
            [第三句]()
            """.trimIndent()
        ).filter { it.kind == RoleplaySegmentKind.DIALOGUE }

        assertEquals("林雾", dialogues[0].speakerName)
        assertEquals("", dialogues[1].speakerName)
        assertEquals("林雾", dialogues[2].speakerName)
    }
}
