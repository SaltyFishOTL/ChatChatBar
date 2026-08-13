package com.example.chatbar.domain.card

import com.example.chatbar.data.local.entity.FormatCardUserToolConfig
import com.example.chatbar.data.local.entity.FormatCardUserToolType
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class FormatCardUserToolPolicyTest {
    @Test
    fun emptyToolsKeepUserContentUnchanged() {
        assertEquals(
            "原消息\n",
            FormatCardUserToolPolicy.appendRequestSuffix("原消息\n", emptyList())
        )
    }

    @Test
    fun adjacentRandomToolsMergeAndKeepInterleavedOrder() {
        val calls = mutableListOf<Pair<Int, Int>>()
        val values = ArrayDeque(listOf(12, 34, -2))
        val rendered = FormatCardUserToolPolicy.appendRequestSuffix(
            userContent = "用户消息",
            tools = listOf(
                strong("强提示 A"),
                random("1", "20"),
                random("30", "40"),
                strong("强提示 B"),
                random("-5", "-1")
            ),
            nextIntInclusive = { minimum, maximum ->
                calls += minimum to maximum
                values.removeFirst()
            }
        )

        assertEquals(listOf(1 to 20, 30 to 40, -5 to -1), calls)
        assertEquals(
            """
            用户消息
            {
            强提示 A
            下一轮按顺序使用随机数：12；34；
            强提示 B
            下一轮使用随机数：-2
            }
            """.trimIndent(),
            rendered
        )
    }

    @Test
    fun strongSuffixPreservesMultilineTextExactly() {
        assertEquals(
            "消息\n{\n第一行\n  第二行  \n}",
            FormatCardUserToolPolicy.appendRequestSuffix(
                "消息",
                listOf(strong("第一行\n  第二行  "))
            )
        )
    }

    @Test
    fun equalBoundsUseInclusiveValue() {
        val rendered = FormatCardUserToolPolicy.appendRequestSuffix(
            userContent = "",
            tools = listOf(random(Int.MAX_VALUE.toString(), Int.MAX_VALUE.toString()))
        )

        assertEquals("{\n下一轮使用随机数：${Int.MAX_VALUE}\n}", rendered)
    }

    @Test
    fun invalidToolsReportIndexedError() {
        val error = assertThrows(IllegalArgumentException::class.java) {
            FormatCardUserToolPolicy.appendRequestSuffix(
                "消息",
                listOf(strong("有效"), random("10", "2"))
            )
        }

        assertTrue(error.message.orEmpty().contains("第 2 个用户工具"))
        assertTrue(error.message.orEmpty().contains("最大值不能小于最小值"))
    }

    private fun random(minimum: String, maximum: String) = FormatCardUserToolConfig(
        type = FormatCardUserToolType.RANDOM_NUMBER,
        minimum = minimum,
        maximum = maximum
    )

    private fun strong(text: String) = FormatCardUserToolConfig(
        type = FormatCardUserToolType.STRONG_PROMPT_SUFFIX,
        text = text
    )
}
