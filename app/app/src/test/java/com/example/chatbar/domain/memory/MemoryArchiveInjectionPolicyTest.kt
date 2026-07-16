package com.example.chatbar.domain.memory

import com.example.chatbar.data.local.entity.MemoryAuthor
import com.example.chatbar.data.local.entity.MemoryNode
import com.example.chatbar.data.local.entity.MemoryTier
import com.example.chatbar.data.local.entity.MemoryTimelineEntry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class MemoryArchiveInjectionPolicyTest {
    @Test
    fun rendersOrderedBodiesWithoutTierOrTRangeLabels() {
        val timeline = listOf(
            timeline("turn-0", 0),
            timeline("turn-1", 1),
            timeline("turn-2", 2)
        )

        val rendered = MemoryArchiveInjectionPolicy.render(
            activeNodes = listOf(
                node("era", MemoryTier.ERA, listOf("turn-2"), "第三段"),
                node("episode", MemoryTier.EPISODE, listOf("turn-0"), "第一段\n继续"),
                node("arc", MemoryTier.ARC, listOf("turn-1"), "第二段")
            ),
            legacyReferenceNodes = emptyList(),
            timeline = timeline
        )

        assertEquals(
            "【ARCHIVE｜历史档案】\n第一段 继续\n第二段\n第三段",
            rendered
        )
        assertFalse(rendered.contains("Episode"))
        assertFalse(rendered.contains("Arc"))
        assertFalse(rendered.contains("Era"))
        assertFalse(rendered.contains("T0"))
    }

    @Test
    fun keepsOnlySemanticWarningForLegacyReferences() {
        val rendered = MemoryArchiveInjectionPolicy.render(
            activeNodes = emptyList(),
            legacyReferenceNodes = listOf(
                node("legacy", MemoryTier.LEGACY_REFERENCE, emptyList(), "旧摘要")
            ),
            timeline = emptyList()
        )

        assertEquals(
            "【ARCHIVE｜历史档案】\n时间未知｜不代表当前进展 旧摘要",
            rendered
        )
        assertFalse(rendered.contains("Legacy"))
        assertFalse(rendered.contains("旧记忆参考 T"))
    }

    @Test
    fun omitsUnverifiableActiveNodesAndBlankBodies() {
        val rendered = MemoryArchiveInjectionPolicy.render(
            activeNodes = listOf(
                node("missing-range", MemoryTier.EPISODE, listOf("missing"), "不可注入")
            ),
            legacyReferenceNodes = listOf(
                node("blank", MemoryTier.LEGACY_REFERENCE, emptyList(), "   ")
            ),
            timeline = emptyList()
        )

        assertEquals("", rendered)
    }

    private fun timeline(sourceTurnId: String, displayT: Long) = MemoryTimelineEntry(
        sourceTurnId = sourceTurnId,
        sourceOrder = displayT,
        displayT = displayT
    )

    private fun node(
        id: String,
        tier: MemoryTier,
        sourceTurnIds: List<String>,
        content: String
    ) = MemoryNode(
        id = id,
        sessionId = "session",
        tier = tier,
        sourceTurnIds = sourceTurnIds,
        content = content,
        author = MemoryAuthor.AI,
        createdAt = sourceTurnIds.firstOrNull()?.substringAfterLast('-')?.toLongOrNull() ?: 0L
    )
}
