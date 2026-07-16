package com.example.chatbar.domain.rag

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChunkSourceType
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.VectorChunk
import com.example.chatbar.domain.chat.ContextWindowManager
import junit.framework.TestCase.assertEquals
import junit.framework.TestCase.assertFalse
import junit.framework.TestCase.assertTrue
import org.junit.Test

class ChatMemoryIndexPolicyTest {
    @Test
    fun buildTurns_groupsEveryReplyInSameSourceTurn() {
        val messages = listOf(
            message("user", MessageRole.USER, "我们去码头。", sourceTurnId = "turn-7"),
            message("system", MessageRole.SYSTEM, "内部状态，不应进入检索", sourceTurnId = null),
            message("assistant", MessageRole.ASSISTANT, "她答应同行。", sourceTurnId = "turn-7"),
            message("append", MessageRole.ASSISTANT, "临走前，她拿上旧地图。", sourceTurnId = "turn-7")
        )

        val turn = ChatMemoryIndexPolicy.buildTurns(messages).single()

        assertEquals("turn-7", turn.sourceTurnId)
        assertEquals(setOf("user", "assistant", "append"), turn.messageIds)
        assertEquals(
            "user:\n我们去码头。\n\nassistant:\n她答应同行。\n\nassistant:\n临走前，她拿上旧地图。",
            ChatMemoryIndexPolicy.contentForIndex(turn)
        )
        assertTrue(ChatMemoryIndexPolicy.shouldIndex(turn))
    }

    @Test
    fun buildTurns_indexesAssistantOnlyOpeningTurnWhenItHasStableIdentity() {
        val opening = message(
            "opening",
            MessageRole.ASSISTANT,
            "雨夜里，她在车站第一次见到你。",
            sourceTurnId = "turn-0"
        )

        val turn = ChatMemoryIndexPolicy.buildTurns(listOf(opening)).single()

        assertEquals("turn-0", turn.sourceTurnId)
        assertEquals("assistant:\n雨夜里，她在车站第一次见到你。", ChatMemoryIndexPolicy.contentForIndex(turn))
        assertTrue(ChatMemoryIndexPolicy.shouldIndex(turn))
    }

    @Test
    fun buildIndexableTurns_includesEveryCompleteTOutsideActiveContext() {
        val messages = listOf(
            message("opening", MessageRole.ASSISTANT, "她在旧车站等待。", sourceTurnId = "turn-0"),
            message("user-1", MessageRole.USER, "我们去码头。", sourceTurnId = "turn-1"),
            message("assistant-1", MessageRole.ASSISTANT, "她答应同行。", sourceTurnId = "turn-1"),
            message("append-1", MessageRole.ASSISTANT, "她还带上旧地图。", sourceTurnId = "turn-1"),
            message("user-2", MessageRole.USER, "现在出发。", sourceTurnId = "turn-2"),
            message("assistant-2", MessageRole.ASSISTANT, "两人走出旅店。", sourceTurnId = "turn-2")
        )

        val turns = ChatMemoryIndexPolicy.buildIndexableTurns(
            messages = messages,
            activeMessageIds = setOf("user-2", "assistant-2")
        )

        assertEquals(listOf("turn-0", "turn-1"), turns.map { it.sourceTurnId })
        assertEquals(setOf("user-1", "assistant-1", "append-1"), turns.last().messageIds)
    }

    @Test
    fun ragAndDirectContext_shareSameSourceTurnBoundary() {
        val messages = (0L..23L).flatMap { turn ->
            buildList {
                if (turn > 0L) {
                    add(message("user-$turn", MessageRole.USER, "用户在第${turn}轮推进剧情。", sourceTurnId = "turn-$turn"))
                }
                add(message("assistant-$turn", MessageRole.ASSISTANT, "助手完成第${turn}轮剧情。", sourceTurnId = "turn-$turn"))
                if (turn % 2L == 0L) {
                    add(message("append-$turn", MessageRole.ASSISTANT, "第${turn}轮追加细节。", sourceTurnId = "turn-$turn"))
                }
            }
        }
        val activeIds = ContextWindowManager()
            .getRecentMessages(messages, windowSize = 15)
            .mapTo(mutableSetOf()) { it.id }

        val turns = ChatMemoryIndexPolicy.buildIndexableTurns(messages, activeIds)

        assertEquals((0L..7L).map { "turn-$it" }, turns.map { it.sourceTurnId })
    }

    @Test
    fun buildTurns_legacyMessagesStillRequireStrictAdjacentUserAssistantPair() {
        val messages = listOf(
            message("greeting", MessageRole.ASSISTANT, "欢迎"),
            message("interrupted-user", MessageRole.USER, "这条被系统消息中断"),
            message("system", MessageRole.SYSTEM, "状态"),
            message("image", MessageRole.ASSISTANT, "", images = listOf("image.png")),
            message("user-1", MessageRole.USER, "钥匙放在哪里？"),
            message("assistant-1", MessageRole.ASSISTANT, "她把钥匙放进左侧抽屉。")
        )

        val turns = ChatMemoryIndexPolicy.buildTurns(messages)

        assertEquals(1, turns.size)
        assertEquals(setOf("user-1", "assistant-1"), turns.single().messageIds)
    }

    @Test
    fun contentForIndex_excludesAssistantStatusAndLongDashOptions() {
        val dash = "\u2014".repeat(12)
        val assistant = "正文\n```status\n状态栏\n```\n$dash\n[选项一]()\n$dash"
        val turn = ChatMemoryIndexPolicy.buildTurns(
            listOf(
                message("user", MessageRole.USER, "接下来怎么办？", sourceTurnId = "turn"),
                message("assistant", MessageRole.ASSISTANT, assistant, sourceTurnId = "turn")
            )
        ).single()

        assertEquals(
            "user:\n接下来怎么办？\n\nassistant:\n正文",
            ChatMemoryIndexPolicy.contentForIndex(turn)
        )
    }

    @Test
    fun turnWithOnlyLowInformationReplies_isSkipped() {
        val turn = ChatMemoryIndexPolicy.buildTurns(
            listOf(
                message("user", MessageRole.USER, "继续吧", sourceTurnId = "turn"),
                message("assistant", MessageRole.ASSISTANT, "好的", sourceTurnId = "turn")
            )
        ).single()

        assertFalse(ChatMemoryIndexPolicy.shouldIndex(turn))
    }

    @Test
    fun rebuildPolicy_marksPairAndOldTurnChunksButNeverManualChunks() {
        assertTrue(ChatMemoryIndexPolicy.needsAutomaticRebuild(chunk("message_pair", "4")))
        assertTrue(ChatMemoryIndexPolicy.needsAutomaticRebuild(chunk("memory_node", "1")))
        assertTrue(ChatMemoryIndexPolicy.needsAutomaticRebuild(chunk("timeline_turn", "4")))
        assertFalse(ChatMemoryIndexPolicy.needsAutomaticRebuild(chunk("timeline_turn", "5")))
        assertFalse(ChatMemoryIndexPolicy.needsAutomaticRebuild(chunk("manual", "1")))
    }

    private fun chunk(indexMode: String, version: String) = VectorChunk(
        id = "$indexMode-$version",
        sourceType = ChunkSourceType.CHAT_MEMORY,
        sourceId = "session",
        content = "content",
        embedding = listOf(1f),
        metadata = mapOf("indexMode" to indexMode, "contentVersion" to version),
        createdAt = 1
    )

    private fun message(
        id: String,
        role: MessageRole,
        content: String,
        images: List<String> = emptyList(),
        sourceTurnId: String? = null
    ) = ChatMessage(
        id = id,
        sessionId = "session",
        role = role,
        content = content,
        images = images,
        createdAt = id.hashCode().toLong(),
        updatedAt = id.hashCode().toLong(),
        sourceTurnId = sourceTurnId,
        sourceTurnOrder = sourceTurnId?.substringAfterLast("-")?.toLongOrNull() ?: sourceTurnId?.hashCode()?.toLong()
    )
}
