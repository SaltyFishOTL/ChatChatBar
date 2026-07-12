package com.example.chatbar.domain.rag

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Test

class ChatMemoryIndexPolicyTest {
    @Test
    fun buildPairs_onlyUsesStrictAdjacentUserAssistantTurns() {
        val messages = listOf(
            message("greeting", MessageRole.ASSISTANT, "欢迎"),
            message("interrupted-user", MessageRole.USER, "这条被系统消息中断"),
            message("system", MessageRole.SYSTEM, "状态"),
            message("image", MessageRole.ASSISTANT, "", images = listOf("image.png")),
            message("user-1", MessageRole.USER, "钥匙放在哪里？"),
            message("assistant-1", MessageRole.ASSISTANT, "她把钥匙放进左侧抽屉。")
        )

        val pairs = ChatMemoryIndexPolicy.buildPairs(messages)

        assertEquals(1, pairs.size)
        assertEquals("user-1", pairs.single().userMessage.id)
        assertEquals("assistant-1", pairs.single().assistantMessage.id)
    }

    @Test
    fun pairContent_containsOnlyUserAndAssistantMessages() {
        val pair = ChatMemoryMessagePair(
            message("user", MessageRole.USER, "  明早几点见？  "),
            message("assistant", MessageRole.ASSISTANT, " 七点。 ")
        )

        assertEquals(
            "user:\n明早几点见？\n\nassistant:\n七点。",
            ChatMemoryIndexPolicy.contentForIndex(pair)
        )
        assertFalse(ChatMemoryIndexPolicy.contentForIndex(pair).contains("Recent context"))
        assertTrue(ChatMemoryIndexPolicy.shouldIndex(pair))
    }

    @Test
    fun pairContent_excludesAssistantStatusAndLongDashOptions() {
        val dash = "\u2014".repeat(12)
        val assistant = "正文\n```status\n状态栏\n```\n$dash\n[选项一]()\n$dash"
        val pair = ChatMemoryMessagePair(
            message("user", MessageRole.USER, "接下来怎么办？"),
            message("assistant", MessageRole.ASSISTANT, assistant)
        )

        assertEquals(
            "user:\n接下来怎么办？\n\nassistant:\n正文",
            ChatMemoryIndexPolicy.contentForIndex(pair)
        )
    }

    @Test
    fun pairWithOnlyLowInformationReplies_isSkipped() {
        val pair = ChatMemoryMessagePair(
            message("user", MessageRole.USER, "继续吧"),
            message("assistant", MessageRole.ASSISTANT, "好的")
        )

        assertFalse(ChatMemoryIndexPolicy.shouldIndex(pair))
    }

    private fun message(
        id: String,
        role: MessageRole,
        content: String,
        images: List<String> = emptyList()
    ) = ChatMessage(
        id = id,
        sessionId = "session",
        role = role,
        content = content,
        images = images,
        createdAt = id.hashCode().toLong(),
        updatedAt = id.hashCode().toLong()
    )
}
