package com.example.chatbar.domain.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RoleplayContentSegmentsTest {
    @Test
    fun `speaker markers are stripped from unsplit assistant content`() {
        val content = "<n=\"林雾\"/>旁白\n<n=“另一人”>[对白]()\n＜ｎ＝＂第三人＂／＞结尾"

        assertEquals("旁白\n[对白]()\n结尾", stripRoleplaySpeakerMarkers(content))
    }

    @Test
    fun `full width speaker marker applies to dialogue`() {
        val dialogue = parseRoleplayTextSegments("＜ｎ＝“林雾”＞[第一句]()")
            .single { it.kind == RoleplaySegmentKind.DIALOGUE }

        assertEquals("林雾", dialogue.speakerName)
        assertEquals("[第一句]()", dialogue.displayText)
    }

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
    fun `speaker marker before narration applies to next dialogue`() {
        val segments = parseRoleplayTextSegments("<n=\"林雾\"/>她看向窗外。[第一句]()")

        assertEquals(listOf(RoleplaySegmentKind.NARRATION, RoleplaySegmentKind.DIALOGUE), segments.map { it.kind })
        assertEquals("她看向窗外。", segments[0].displayText)
        assertEquals("林雾", segments[1].speakerName)
        assertEquals("[第一句]()", segments[1].rawText)
    }

    @Test
    fun `speaker marker inside hidden comment does not apply to next dialogue`() {
        val dialogue = parseRoleplayTextSegments("旁白<!-- <n=\"林雾\"/> -->文字[第一句]()")
            .single { it.kind == RoleplaySegmentKind.DIALOGUE }

        assertNull(dialogue.speakerName)
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
    fun `thought without markdown bold markers becomes thought segment`() {
        val thought = parseRoleplayTextSegments("『第二段内心』")
            .single { it.kind == RoleplaySegmentKind.THOUGHT }

        assertEquals("『第二段内心』", thought.rawText)
    }

    @Test
    fun `first unmarked dialogue keeps legacy speaker fallback`() {
        val dialogue = parseRoleplayTextSegments("[第一句]()")
            .single { it.kind == RoleplaySegmentKind.DIALOGUE }

        assertNull(dialogue.speakerName)
    }

    @Test
    fun `dialogue does not require parentheses after closing bracket`() {
        val dialogue = parseRoleplayTextSegments("[第一句]")
            .single { it.kind == RoleplaySegmentKind.DIALOGUE }

        assertEquals("[第一句]", dialogue.rawText)
    }

    @Test
    fun `dialogue keeps optional parenthetical content in same segment`() {
        val dialogue = parseRoleplayTextSegments("[第一句](轻声说)")
            .single { it.kind == RoleplaySegmentKind.DIALOGUE }

        assertEquals("[第一句](轻声说)", dialogue.rawText)
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
