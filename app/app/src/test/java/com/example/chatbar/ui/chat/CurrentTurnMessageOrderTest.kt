package com.example.chatbar.ui.chat

import com.example.chatbar.data.local.entity.FormatCardUserToolConfig
import com.example.chatbar.data.local.entity.FormatCardUserToolType
import com.example.chatbar.data.local.entity.FormatPromptPosition
import com.example.chatbar.domain.card.FormatCardUserToolPolicy
import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.chat.PromptCacheKeyFactory
import com.example.chatbar.domain.prompt.PromptTemplates
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CurrentTurnMessageOrderTest {
    @Test
    fun startPositionPlacesRequirementsAfterCcbApprovalAndBeforeHistory() {
        val messages = buildCcbStablePrefixMessages(
            coreSystemPrompt = "主系统提示",
            stableContextSystemPrompt = "角色资料",
            positionedRequirementsSystemPrompt = "格式要求",
            formatPromptPosition = FormatPromptPosition.START,
            hasHistoryMessages = true
        )

        assertEquals(
            listOf("system", "assistant", "user", "assistant", "system", "assistant", "system"),
            messages.map { it.role }
        )
        assertTrue(messages[0].content.jsonText().contains("主系统提示"))
        assertTrue(messages[0].content.jsonText().contains("CCB大师"))
        assertEquals("角色资料", messages[4].content.jsonText())
        assertTrue(messages[6].content.jsonText().startsWith("格式要求"))
        assertTrue(messages[6].content.jsonText().endsWith("【聊天记录】"))
    }

    @Test
    fun endPositionPlacesRequirementsInsideTailBeforeCurrentUser() {
        val messages = buildCcbStablePrefixMessages(
            coreSystemPrompt = "主系统提示",
            stableContextSystemPrompt = "角色资料",
            positionedRequirementsSystemPrompt = "格式要求",
            formatPromptPosition = FormatPromptPosition.END,
            hasHistoryMessages = false
        ).toMutableList()
        messages += ChatApiMessage.text(
            "system",
            buildCcbFinalTailSystemPrompt(
                postHistorySystemPrompt = "JailBreak尾缀",
                positionedRequirementsSystemPrompt = "格式要求",
                formatPromptPosition = FormatPromptPosition.END
            )
        )
        appendCurrentUserAndStrongPromptSystemMessage(
            messages = messages,
            userMessage = ChatApiMessage.text("user", "真实用户输入"),
            strongPromptSystemSuffix = ""
        )

        assertEquals("system", messages[messages.lastIndex - 1].role)
        assertEquals("user", messages.last().role)
        val tail = messages[messages.lastIndex - 1].content.jsonText()
        assertTrue(tail.indexOf("JailBreak尾缀") < tail.indexOf("下一条 user 消息"))
        assertTrue(tail.indexOf("下一条 user 消息") < tail.indexOf("格式要求"))
        assertEquals(1, messages.count { it.content.jsonText() == "真实用户输入" })
    }

    @Test
    fun bothPositionUsesSameRequirementsAtStartAndEnd() {
        val requirements = PromptTemplates.currentTurnOutputRequirementsSystemPrompt(
            formatCardContent = "格式正文",
            replyLength = 300,
            includeFormatHistoryContinuityNotice = true
        )
        val prefix = buildCcbStablePrefixMessages(
            coreSystemPrompt = "主系统提示",
            stableContextSystemPrompt = "角色资料",
            positionedRequirementsSystemPrompt = requirements,
            formatPromptPosition = FormatPromptPosition.BOTH,
            hasHistoryMessages = false
        )
        val tail = buildCcbFinalTailSystemPrompt(
            postHistorySystemPrompt = "JailBreak尾缀",
            positionedRequirementsSystemPrompt = requirements,
            formatPromptPosition = FormatPromptPosition.BOTH
        )

        assertEquals(requirements, prefix.last().content.jsonText())
        assertTrue(tail.endsWith(requirements))
        assertTrue(prefix.last().content.jsonText().contains(PromptTemplates.FORMAT_HISTORY_CONTINUITY_NOTICE))
    }

    @Test
    fun strongPromptSuffixIsOnlyMessageAllowedAfterCurrentUser() {
        val messages = mutableListOf(ChatApiMessage.text("system", "尾部规则"))

        appendCurrentUserAndStrongPromptSystemMessage(
            messages = messages,
            userMessage = ChatApiMessage.text("user", "真实用户输入"),
            strongPromptSystemSuffix = "强提示 A\n\n强提示 B"
        )

        assertEquals(listOf("system", "user", "system"), messages.map { it.role })
        assertEquals("真实用户输入", messages[1].content.jsonText())
        assertEquals("强提示 A\n\n强提示 B", messages[2].content.jsonText())
    }

    @Test
    fun randomToolStaysInUserWhileStrongPromptBecomesSystem() {
        val tools = listOf(
            FormatCardUserToolConfig(
                type = FormatCardUserToolType.STRONG_PROMPT_SUFFIX,
                text = "强提示"
            ),
            FormatCardUserToolConfig.randomNumber()
        )
        val userContent = FormatCardUserToolPolicy.appendRequestSuffix(
            userContent = "用户原文",
            tools = tools,
            nextIntInclusive = { _, _ -> 42 }
        )
        val messages = mutableListOf<ChatApiMessage>()

        appendCurrentUserAndStrongPromptSystemMessage(
            messages = messages,
            userMessage = ChatApiMessage.text("user", userContent),
            strongPromptSystemSuffix = FormatCardUserToolPolicy.strongPromptSystemSuffix(tools)
        )

        assertEquals("用户原文\n{\n下一轮使用随机数：42\n}", messages[0].content.jsonText())
        assertFalse(messages[0].content.jsonText().contains("强提示"))
        assertEquals("强提示", messages[1].content.jsonText())
    }

    @Test
    fun cacheKeyCoversEntireStableRoleSequence() {
        val prefixA = buildCcbStablePrefixMessages(
            coreSystemPrompt = "主系统提示",
            stableContextSystemPrompt = "角色资料 A",
            positionedRequirementsSystemPrompt = "格式要求",
            formatPromptPosition = FormatPromptPosition.START,
            hasHistoryMessages = true
        )
        val prefixB = buildCcbStablePrefixMessages(
            coreSystemPrompt = "主系统提示",
            stableContextSystemPrompt = "角色资料 B",
            positionedRequirementsSystemPrompt = "格式要求",
            formatPromptPosition = FormatPromptPosition.START,
            hasHistoryMessages = true
        )

        assertNotEquals(
            PromptCacheKeyFactory.cacheKey(prefixA),
            PromptCacheKeyFactory.cacheKey(prefixB)
        )
        assertNotEquals(
            PromptCacheKeyFactory.cacheKey(listOf(ChatApiMessage.text("system", "相同内容"))),
            PromptCacheKeyFactory.cacheKey(listOf(ChatApiMessage.text("assistant", "相同内容")))
        )
    }

    @Test
    fun endOnlyRequirementsStayOutsideStableCachePrefix() {
        val prefixA = buildCcbStablePrefixMessages(
            coreSystemPrompt = "主系统提示",
            stableContextSystemPrompt = "角色资料",
            positionedRequirementsSystemPrompt = "格式要求 A",
            formatPromptPosition = FormatPromptPosition.END,
            hasHistoryMessages = false
        )
        val prefixB = buildCcbStablePrefixMessages(
            coreSystemPrompt = "主系统提示",
            stableContextSystemPrompt = "角色资料",
            positionedRequirementsSystemPrompt = "格式要求 B",
            formatPromptPosition = FormatPromptPosition.END,
            hasHistoryMessages = false
        )

        assertEquals(
            PromptCacheKeyFactory.cacheKey(prefixA),
            PromptCacheKeyFactory.cacheKey(prefixB)
        )
    }

    private fun JsonElement.jsonText(): String = (this as JsonPrimitive).content
}
