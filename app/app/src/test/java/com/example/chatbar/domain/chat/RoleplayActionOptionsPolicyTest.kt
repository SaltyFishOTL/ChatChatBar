package com.example.chatbar.domain.chat

import org.junit.Assert.*
import org.junit.Test

class RoleplayActionOptionsPolicyTest {
    private val options = "————————\n**【行动选项】**\n1. 去图书馆\n2. 回旅店\n————————"
    @Test fun onlyExplicitCompleteListIsRemovedAndStateRemains() {
        val source = "已经到达广场。\n$options\n```text\n衣着：蓝外套\n```"
        val result = RoleplayActionOptionsPolicy.withoutExplicitOptions(source)
        assertTrue(result.contains("已经到达广场")); assertTrue(result.contains("蓝外套"))
        assertFalse(result.contains("去图书馆")); assertFalse(result.contains("回旅店"))
        assertTrue(source.contains("回旅店"))
    }
    @Test fun ordinaryNumberedNarrationAndUnwrappedHeadingsArePreserved() {
        for (text in listOf("清单\n1. 地图\n2. 水", "【行动选项】\n1. 地图\n2. 水",
            "————————\n行李清单\n1. 地图\n2. 水\n————————"))
            assertEquals(text, RoleplayActionOptionsPolicy.withoutExplicitOptions(text))
    }
    @Test fun quotedCodeAndUnclosedCodeArePreserved() {
        for (text in listOf("```text\n$options\n```", "```text\n$options"))
            assertEquals(text, RoleplayActionOptionsPolicy.withoutExplicitOptions(text))
    }
    @Test fun missingClosingRuleOrMalformedNumberingDoesNotEraseText() {
        for (text in listOf(options.substringBeforeLast("————————"), options.replace("2. 回旅店", "4. 回旅店"),
            options.replace("2. 回旅店", "  4. 回旅店")))
            assertEquals(text, RoleplayActionOptionsPolicy.withoutExplicitOptions(text))
    }
    @Test fun inlineNonOptionNarrationInsideFenceIsNotDeleted() {
        val text = options.replace("2. 回旅店", "2. 回旅店\n这是已经发生的正文。")
        assertEquals(text, RoleplayActionOptionsPolicy.withoutExplicitOptions(text))
    }
    @Test fun ordinalTextIsAvailableForLaterUserDecidedNumericPolicyWithoutChangingSource() {
        val text = options.replace("1. 去图书馆", "1. 去图书馆\n  查询地图")
        val parsed = RoleplayActionOptionsPolicy.blocks(text).single()
        assertEquals(listOf(1, 2), parsed.options.map { it.ordinal })
        assertEquals("去图书馆\n查询地图", parsed.options.first().text)
    }
    @Test fun aliasesAndMultipleBlocksUseIndependentRawRanges() {
        for (header in listOf("可选行动", "行动选择", "Action Choices", "Action Options")) {
            val text = options.replace("行动选项", header) + "\n剧情继续。\n" + options
            assertEquals(2, RoleplayActionOptionsPolicy.blocks(text).size)
            assertEquals("\n剧情继续。\n", RoleplayActionOptionsPolicy.withoutExplicitOptions(text))
        }
    }
}
