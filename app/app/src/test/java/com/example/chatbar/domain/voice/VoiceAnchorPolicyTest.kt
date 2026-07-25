package com.example.chatbar.domain.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VoiceAnchorPolicyTest {
    @Test
    fun `eligible segments contain only dialogue and thought with plain spoken text`() {
        val segments = VoiceAnchorPolicy.eligibleSegments(
            """
            旁白。
            <n="林雾"/>[**第一句**](轻声)
            『*第二段内心*』
            <status>状态</status>
            """.trimIndent()
        )

        assertEquals(2, segments.size)
        assertEquals(listOf("第一句", "第二段内心"), segments.map { it.spokenText })
        assertEquals(listOf("林雾", "林雾"), segments.map { it.speakerName })
    }

    @Test
    fun `front insertion keeps existing anchor identities`() {
        val old = VoiceAnchorPolicy.initialState(
            "m1",
            "<n=\"林雾\"/>[第一句]()\n[第二句]()"
        )

        val result = VoiceAnchorPolicy.reconcile(
            old,
            "<n=\"林雾\"/>[新增]()\n[第一句]()\n[第二句]()"
        )

        assertEquals(3, result.state.anchors.size)
        assertEquals(old.anchors[0].id, result.state.anchors[1].id)
        assertEquals(old.anchors[1].id, result.state.anchors[2].id)
    }

    @Test
    fun `front deletion keeps later anchor identities`() {
        val old = VoiceAnchorPolicy.initialState(
            "m1",
            "<n=\"林雾\"/>[第一句]()\n[第二句]()\n[第三句]()"
        )

        val result = VoiceAnchorPolicy.reconcile(
            old,
            "<n=\"林雾\"/>[第二句]()\n[第三句]()"
        )

        assertNull(result.anchorReplacement[old.anchors[0].id])
        assertEquals(old.anchors[1].id, result.state.anchors[0].id)
        assertEquals(old.anchors[2].id, result.state.anchors[1].id)
    }

    @Test
    fun `text edit and speaker change preserve target anchor`() {
        val old = VoiceAnchorPolicy.initialState("m1", "<n=\"甲\"/>[旧文字]()")

        val result = VoiceAnchorPolicy.reconcile(old, "<n=\"乙\"/>[新文字]()")

        assertEquals(old.anchors.single().id, result.state.anchors.single().id)
        assertEquals("乙", result.state.anchors.single().speakerName)
    }

    @Test
    fun `deleted middle target reattaches to previous surviving anchor`() {
        val old = VoiceAnchorPolicy.initialState(
            "m1",
            "<n=\"甲\"/>[第一]()\n[第二]()\n[第三]()"
        )

        val result = VoiceAnchorPolicy.reconcile(
            old,
            "<n=\"甲\"/>[第一]()\n[第三]()"
        )

        assertEquals(old.anchors[0].id, result.anchorReplacement[old.anchors[1].id])
        assertEquals(old.anchors[2].id, result.state.anchors[1].id)
    }

    @Test
    fun `deleted first target becomes message start orphan`() {
        val old = VoiceAnchorPolicy.initialState(
            "m1",
            "<n=\"甲\"/>[第一]()\n[第二]()"
        )

        val result = VoiceAnchorPolicy.reconcile(old, "<n=\"甲\"/>[第二]()")

        assertTrue(result.anchorReplacement.containsKey(old.anchors[0].id))
        assertNull(result.anchorReplacement[old.anchors[0].id])
    }
}
