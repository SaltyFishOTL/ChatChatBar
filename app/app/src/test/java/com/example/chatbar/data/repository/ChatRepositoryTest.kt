package com.example.chatbar.data.repository

import android.content.ContextWrapper
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.MESSAGE_ORDER_STEP
import com.example.chatbar.data.local.entity.MessageRole
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatRepositoryTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `rewriteSessionTitlesForCharacterCard renames bound session titles`() = runTest {
        val storage = JsonFileStorage(TestContext(temp.newFolder("files")))
        val chats = ChatRepository(storage)
        chats.createSession(
            ChatSession(id = "s1", characterCardId = "card", title = "小明", createdAt = 1, updatedAt = 1)
        )
        chats.createSession(
            ChatSession(id = "s2", characterCardId = "card", title = "小明的回忆", createdAt = 2, updatedAt = 2)
        )
        chats.createSession(
            ChatSession(id = "other", characterCardId = "other-card", title = "小明", createdAt = 3, updatedAt = 3)
        )

        val updated = chats.rewriteSessionTitlesForCharacterCard("card", "小明", "小红")

        assertEquals(2, updated)
        assertEquals("小红", requireNotNull(chats.getSession("s1")).title)
        assertEquals("小红的回忆", requireNotNull(chats.getSession("s2")).title)
        assertEquals("小明", requireNotNull(chats.getSession("other")).title)
    }

    @Test
    fun `rewriteSessionTitlesForCharacterCard skips empty or identical rename`() = runTest {
        val storage = JsonFileStorage(TestContext(temp.newFolder("files")))
        val chats = ChatRepository(storage)
        chats.createSession(
            ChatSession(id = "s1", characterCardId = "card", title = "小明", createdAt = 1, updatedAt = 1)
        )

        assertEquals(0, chats.rewriteSessionTitlesForCharacterCard("card", "", "小红"))
        assertEquals(0, chats.rewriteSessionTitlesForCharacterCard("card", "小明", "小明"))
        assertEquals("小明", requireNotNull(chats.getSession("s1")).title)
    }

    @Test
    fun `display title override is normalized without changing source title`() = runTest {
        val storage = JsonFileStorage(TestContext(temp.newFolder("files")))
        val chats = ChatRepository(storage)
        chats.createSession(
            ChatSession(id = "s1", characterCardId = "card", title = "小明", createdAt = 1, updatedAt = 1)
        )

        chats.updateSessionDisplayTitle("s1", "  第二周目  ")

        val renamed = requireNotNull(chats.getSession("s1"))
        assertEquals("小明", renamed.title)
        assertEquals("第二周目", renamed.displayTitleOverride)
        assertEquals(listOf("s1"), chats.searchSessions("第二周目").map { it.id })

        chats.updateSessionDisplayTitle("s1", "   ")

        assertNull(requireNotNull(chats.getSession("s1")).displayTitleOverride)
    }

    @Test
    fun `character rename preserves display title override`() = runTest {
        val storage = JsonFileStorage(TestContext(temp.newFolder("files")))
        val chats = ChatRepository(storage)
        chats.createSession(
            ChatSession(
                id = "s1",
                characterCardId = "card",
                title = "小明",
                displayTitleOverride = "第二周目",
                createdAt = 1,
                updatedAt = 1
            )
        )

        chats.rewriteSessionTitlesForCharacterCard("card", "小明", "小红")

        val renamed = requireNotNull(chats.getSession("s1"))
        assertEquals("小红", renamed.title)
        assertEquals("第二周目", renamed.displayTitleOverride)
    }

    @Test
    fun `message order repair persists backup and undo restores original keys`() = runTest {
        val chats = repository("order-repair")
        val original = corruptedMessages()
        chats.createSession(
            ChatSession(id = "session", characterCardId = "card", title = "test", createdAt = 1, updatedAt = 1)
        )
        chats.replaceMessagesForSession("session", original)

        val preview = chats.previewMessageOrderRepair("session")
        val repaired = chats.repairMessageOrder("session", preview.baseline)

        assertEquals(
            listOf("user-1", "assistant-1", "image-1", "user-2", "assistant-2"),
            chats.getMessages("session").map(ChatMessage::id)
        )
        assertTrue(repaired.requiresRepair)
        assertTrue(chats.hasMessageOrderBackup("session"))
        val originalById = original.associateBy(ChatMessage::id)
        chats.getMessages("session").forEach { message ->
            assertEquals(
                message.copy(orderKey = originalById.getValue(message.id).orderKey),
                originalById.getValue(message.id)
            )
        }

        chats.restoreMessageOrderBackup("session")

        assertEquals(original.sortedWith(ChatMessage.TimelineComparator), chats.getMessages("session"))
        assertFalse(chats.hasMessageOrderBackup("session"))
    }

    @Test
    fun `message order undo refuses content changed after repair`() = runTest {
        val chats = repository("order-undo-change")
        chats.createSession(
            ChatSession(id = "session", characterCardId = "card", title = "test", createdAt = 1, updatedAt = 1)
        )
        chats.replaceMessagesForSession("session", corruptedMessages())
        val preview = chats.previewMessageOrderRepair("session")
        chats.repairMessageOrder("session", preview.baseline)
        val first = chats.getMessages("session").first()
        chats.updateMessage(first.copy(content = "changed"))

        val error = runCatching { chats.restoreMessageOrderBackup("session") }.exceptionOrNull()

        assertEquals("修复后聊天内容已变化，无法安全撤销", error?.message)
        assertTrue(chats.hasMessageOrderBackup("session"))
    }

    @Test
    fun `message order repair refuses stale preview`() = runTest {
        val chats = repository("order-stale-preview")
        chats.createSession(
            ChatSession(id = "session", characterCardId = "card", title = "test", createdAt = 1, updatedAt = 1)
        )
        chats.replaceMessagesForSession("session", corruptedMessages())
        val preview = chats.previewMessageOrderRepair("session")
        val first = chats.getMessages("session").first()
        chats.updateMessage(first.copy(content = "changed"))

        val error = runCatching {
            chats.repairMessageOrder("session", preview.baseline)
        }.exceptionOrNull()

        assertEquals("预览后聊天内容已变化，请重新生成修复预览", error?.message)
        assertFalse(chats.hasMessageOrderBackup("session"))
    }

    private fun repository(folder: String) =
        ChatRepository(JsonFileStorage(TestContext(temp.newFolder(folder))))

    private fun corruptedMessages(): List<ChatMessage> = listOf(
        message("user-1", MessageRole.USER, 10, 1, "turn-1", 0),
        message("assistant-1", MessageRole.ASSISTANT, 20, 2, "turn-1", 0),
        message("user-2", MessageRole.USER, 30, 3, "turn-2", 1),
        message("assistant-2", MessageRole.ASSISTANT, 40, 4, "turn-2", 1),
        message("image-1", MessageRole.ASSISTANT, 50, 5, "turn-1", 0, "assistant-1")
    )

    private fun message(
        id: String,
        role: MessageRole,
        createdAt: Long,
        order: Long,
        sourceTurnId: String,
        sourceTurnOrder: Long,
        generatedFromMessageId: String? = null
    ) = ChatMessage(
        id = id,
        sessionId = "session",
        role = role,
        content = id,
        images = if (id.startsWith("image")) listOf("/$id.png") else emptyList(),
        generatedFromMessageId = generatedFromMessageId,
        createdAt = createdAt,
        updatedAt = createdAt + 1,
        orderKey = order * MESSAGE_ORDER_STEP,
        sourceTurnId = sourceTurnId,
        sourceTurnOrder = sourceTurnOrder
    )

    private class TestContext(private val dir: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = dir
    }
}
