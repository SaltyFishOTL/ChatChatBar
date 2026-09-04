package com.example.chatbar.domain.worldbook

import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.CharacterResearchSourceMode
import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.data.local.entity.WorldBookEntry
import com.example.chatbar.data.local.entity.WorldBookPosition
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.json.Json
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class WorldBookAiServiceTest {
    @Test
    fun `created batch is capped at five and total limit is fifty`() {
        val raw = (1..8).map { CreateEntryResponse("条目 $it", listOf("词 $it")) }

        val result = WorldBookAiService.constrainCreatedEntries(
            book = book(),
            completed = emptyList(),
            rawEntries = raw,
            remaining = WORLD_BOOK_AI_CREATE_LIMIT,
            idFactory = { "candidate" }
        )

        assertEquals(5, result.size)
        assertEquals(5, WORLD_BOOK_AI_BATCH_SIZE)
        assertEquals(50, WORLD_BOOK_AI_CREATE_LIMIT)
    }

    @Test
    fun `create continuation follows hasMore cap and no progress guards`() {
        assertFalse(WorldBookAiService.decideCreateContinuation(false, 5, 5).shouldContinue)
        assertTrue(WorldBookAiService.decideCreateContinuation(true, 5, 5).shouldContinue)
        assertTrue(WorldBookAiService.decideCreateContinuation(true, 0, 5).warning.contains("没有产生新的有效候选"))
        assertTrue(WorldBookAiService.decideCreateContinuation(true, 5, 50).warning.contains("50 条上限"))
    }

    @Test
    fun `created entries normalize keys and remove duplicate names and key sets`() {
        val current = book(
            entries = listOf(WorldBookEntry(id = "old", name = "王都", keys = listOf("首都")))
        )
        val result = WorldBookAiService.constrainCreatedEntries(
            book = current,
            completed = listOf(WorldBookEntryPlanCandidate("done", "魔 法 塔", listOf("法师塔"))),
            rawEntries = listOf(
                CreateEntryResponse(" 王都 ", listOf("城市")),
                CreateEntryResponse("魔法塔", listOf("另一触发词")),
                CreateEntryResponse("议会", listOf(" 首都 ")),
                CreateEntryResponse("学院", listOf("学院", " 学院 ", "ACADEMY", "academy")),
                CreateEntryResponse("", listOf("无效")),
                CreateEntryResponse("荒野", emptyList())
            ),
            remaining = 10,
            idFactory = { "new" }
        )

        assertEquals(listOf("学院"), result.map { it.name })
        assertEquals(listOf("学院", "ACADEMY"), result.single().keys)
    }

    @Test
    fun `fill constraints reject unknown duplicate nonempty and blank targets`() {
        val targets = listOf(
            WorldBookEntry(id = "empty", name = "空条目", keys = listOf("空")),
            WorldBookEntry(id = "filled", name = "已有条目", content = "已有正文")
        )

        val result = WorldBookAiService.constrainFilledEntries(
            targets,
            listOf(
                FillEntryResponse("unknown", "未知"),
                FillEntryResponse("empty", "  新正文  "),
                FillEntryResponse("empty", "重复正文"),
                FillEntryResponse("filled", "覆盖正文"),
                FillEntryResponse("empty", "   ")
            )
        )

        assertEquals(listOf("empty"), result.candidates.map { it.targetId })
        assertEquals("新正文", result.candidates.single().content)
        assertTrue(result.rejections.any { it.contains("未知目标") })
        assertTrue(result.rejections.any { it.contains("重复目标") })
        assertTrue(result.rejections.any { it.contains("正文非空") })
    }

    @Test
    fun `apply created entries uses defaults and ignores unchecked candidates`() {
        val current = listOf(WorldBookEntry(id = "old", name = "旧条目", content = "保留"))
        val candidates = listOf(
            WorldBookEntryPlanCandidate("c1", "不应用", listOf("x")),
            WorldBookEntryPlanCandidate("c2", "新条目", listOf("新", "条目"))
        )

        val result = WorldBookAiService.applyCreatedEntries(
            current,
            candidates,
            selectedIds = setOf("c2"),
            idFactory = { "new-id" }
        )

        assertEquals(current.single(), result.first())
        assertEquals(
            WorldBookEntry(id = "new-id", name = "新条目", keys = listOf("新", "条目")),
            result.last()
        )
    }

    @Test
    fun `apply fill changes only selected still-empty content and preserves advanced fields`() {
        val empty = WorldBookEntry(
            id = "empty",
            name = "遗迹",
            keys = listOf("遗迹"),
            enabled = false,
            insertionOrder = 7,
            priority = 42,
            constant = true,
            position = WorldBookPosition.AFTER_CHAR,
            secondaryKeys = listOf("古城"),
            probability = 63,
            group = "地点",
            sticky = 2,
            useRegex = true,
            extensions = "{\"custom\":true}"
        )
        val nonEmpty = WorldBookEntry(id = "filled", name = "王都", content = "原正文", probability = 25)
        val unchecked = WorldBookEntry(id = "unchecked", name = "森林")
        val result = WorldBookAiService.applyFilledContents(
            current = listOf(empty, nonEmpty, unchecked),
            candidates = listOf(
                WorldBookContentCandidate("empty", "错误名称", listOf("错误词"), "遗迹正文"),
                WorldBookContentCandidate("filled", "王都", emptyList(), "不得覆盖"),
                WorldBookContentCandidate("unchecked", "森林", emptyList(), "未选择")
            ),
            selectedIds = setOf("empty", "filled")
        )

        assertEquals("遗迹正文", result[0].content)
        assertEquals(empty, result[0].copy(content = ""))
        assertEquals(nonEmpty, result[1])
        assertEquals(unchecked, result[2])
    }

    @Test
    fun `checkpoint retains current batch candidates research and raw output`() {
        val candidate = WorldBookEntryPlanCandidate("c", "条目", listOf("触发词"))
        val checkpoint = WorldBookCreateCheckpoint(
            candidates = listOf(candidate),
            batchNumber = 3,
            rawOutputs = listOf(WorldBookAiRawOutput("batch-3", "第三批", "partial"))
        )

        assertEquals(3, checkpoint.batchNumber)
        assertEquals(candidate, checkpoint.candidates.single())
        assertEquals("partial", checkpoint.rawOutputs.single().text)
        assertFalse(checkpoint.warning.isNotBlank())
    }

    @Test
    fun `checkpoint becomes incompatible when any input signature changes`() {
        assertTrue(isWorldBookAiCheckpointCompatible("same", "same"))
        assertFalse(isWorldBookAiCheckpointCompatible("old", "new"))
        assertFalse(isWorldBookAiCheckpointCompatible("", ""))
    }

    @Test
    fun `old app settings json receives world book encyclopedia default`() {
        val settings = Json { ignoreUnknownKeys = true }.decodeFromString<AppSettings>("{}")

        assertEquals(
            CharacterResearchSourceMode.ENCYCLOPEDIA_SEARCH,
            settings.worldBookAiResearchSourceMode
        )
    }

    private fun book(entries: List<WorldBookEntry> = emptyList()) = WorldBook(
        id = "book",
        name = "测试世界",
        entries = entries
    )
}
