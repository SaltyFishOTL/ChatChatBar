package com.example.chatbar.ui.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.MessageRole
import org.junit.Assert.assertEquals
import org.junit.Test

class ChatMessageRefreshPolicyTest {
    @Test
    fun `repository refresh keeps regeneration target hidden while image result arrives`() {
        val messages = listOf(
            message("before"),
            message("regenerating"),
            message("generated-image")
        )

        val visible = filterRegenerationTargetMessage(
            messages = messages,
            regeneratingMessageId = "regenerating"
        )

        assertEquals(listOf("before", "generated-image"), visible.map(ChatMessage::id))
    }

    @Test
    fun `repository refresh keeps persisted interrupted draft visible`() {
        val messages = listOf(message("before"), message("interrupted"))

        val visible = filterRegenerationTargetMessage(
            messages = messages,
            regeneratingMessageId = null
        )

        assertEquals(listOf("before", "interrupted"), visible.map(ChatMessage::id))
    }

    @Test
    fun `streaming and persisted versions share one stable timeline item`() {
        val before = message("before")
        val persisted = message("assistant").copy(content = "persisted")
        val streaming = message("assistant").copy(content = "streaming")

        val beforePersistence = mergeStreamingMessageIntoTimeline(
            messages = listOf(before),
            streamingMessage = streaming
        )
        val duringPersistence = mergeStreamingMessageIntoTimeline(
            messages = listOf(before, persisted),
            streamingMessage = streaming
        )
        val afterPersistence = mergeStreamingMessageIntoTimeline(
            messages = listOf(before, persisted),
            streamingMessage = null
        )

        assertEquals(listOf("before", "assistant"), beforePersistence.map(ChatMessage::id))
        assertEquals(beforePersistence.map(ChatMessage::id), duringPersistence.map(ChatMessage::id))
        assertEquals(duringPersistence.map(ChatMessage::id), afterPersistence.map(ChatMessage::id))
        assertEquals("streaming", duringPersistence.last().content)
        assertEquals("persisted", afterPersistence.last().content)
    }

    @Test
    fun `streaming message inserts by stable order key`() {
        val timeline = mergeStreamingMessageIntoTimeline(
            messages = listOf(message("before"), message("after").copy(orderKey = 3)),
            streamingMessage = message("streaming").copy(orderKey = 2)
        )

        assertEquals(listOf("before", "streaming", "after"), timeline.map(ChatMessage::id))
    }

    private fun message(id: String) = ChatMessage(
        id = id,
        sessionId = "session",
        role = MessageRole.ASSISTANT,
        content = id,
        createdAt = when (id) {
            "before" -> 1
            "streaming" -> 2
            else -> 3
        },
        updatedAt = 1,
        orderKey = when (id) {
            "before" -> 1
            "streaming" -> 2
            else -> 3
        }
    )
}
