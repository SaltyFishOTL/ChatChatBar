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
    fun keepsUnverifiableActiveBodiesButOmitsBlankBodies() {
        val rendered = MemoryArchiveInjectionPolicy.render(
            activeNodes = listOf(
                node("missing-range", MemoryTier.EPISODE, listOf("missing"), "必须保留"),
                node("blank-active", MemoryTier.EPISODE, emptyList(), "   ")
            ),
            legacyReferenceNodes = listOf(
                node("blank", MemoryTier.LEGACY_REFERENCE, emptyList(), "   ")
            ),
            timeline = emptyList()
        )

        assertEquals("【ARCHIVE｜历史档案】\n必须保留", rendered)
    }

    @Test
    fun ordersVerifiableBodiesBeforeStableUnverifiableFallback() {
        val rendered = MemoryArchiveInjectionPolicy.render(
            activeNodes = listOf(
                node("unknown-late", MemoryTier.ARC, listOf("missing-2"), "未知二", createdAt = 20),
                node("known", MemoryTier.EPISODE, listOf("turn-0"), "已知", createdAt = 30),
                node("unknown-early", MemoryTier.ERA, emptyList(), "未知一", createdAt = 10)
            ),
            legacyReferenceNodes = emptyList(),
            timeline = listOf(timeline("turn-0", 0))
        )

        assertEquals(
            "【ARCHIVE｜历史档案】\n已知\n未知一\n未知二",
            rendered
        )
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
        content: String,
        createdAt: Long = sourceTurnIds.firstOrNull()?.substringAfterLast('-')?.toLongOrNull() ?: 0L
    ) = MemoryNode(
        id = id,
        sessionId = "session",
        tier = tier,
        sourceTurnIds = sourceTurnIds,
        content = content,
        author = MemoryAuthor.AI,
        createdAt = createdAt
    )
}
