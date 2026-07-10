package com.example.chatbar.domain.chat

import android.content.ContextWrapper
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.CharacterInfo
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.SpeakerTagRename
import com.example.chatbar.data.local.entity.SpeakerTagRenameTask
import com.example.chatbar.data.repository.CharacterRepository
import com.example.chatbar.data.repository.ChatRepository
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SpeakerTagHistoryServiceTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun executeRewritesContentAndAlternativesThenClearsDurableTask() = runTest {
        val storage = JsonFileStorage(TestContext(temp.newFolder("files")))
        val characters = CharacterRepository(storage)
        val chats = ChatRepository(storage)
        val rename = SpeakerTagRename("person", "旧名", "新名")
        val task = SpeakerTagRenameTask(
            id = "task",
            characterCardId = "card",
            expectedCardUpdatedAt = 2,
            renames = listOf(rename),
            createdAt = 3
        )
        characters.save(
            CharacterCard(
                id = "card",
                name = "测试卡",
                characters = listOf(CharacterInfo(id = "person", name = "新名")),
                pendingSpeakerRenameTasks = listOf(task),
                createdAt = 1,
                updatedAt = 2
            )
        )
        chats.createSession(
            ChatSession(
                id = "session",
                characterCardId = "card",
                title = "会话",
                createdAt = 1,
                updatedAt = 1
            )
        )
        chats.addMessage(
            ChatMessage(
                id = "message",
                sessionId = "session",
                role = MessageRole.ASSISTANT,
                content = "<n=\"旧名\"/>[正文]() <n=\"临时NPC\"/>[保留]()",
                alternatives = listOf("<n=\"旧名\"/>『**备选**』"),
                createdAt = 1,
                updatedAt = 1
            )
        )
        val service = SpeakerTagHistoryService(characters, chats)

        assertEquals(1, service.execute("card", "task"))

        val message = requireNotNull(chats.getMessage("message", "session"))
        assertEquals("<n=\"新名\"/>[正文]() <n=\"临时NPC\"/>[保留]()", message.content)
        assertEquals(listOf("<n=\"新名\"/>『**备选**』"), message.alternatives)
        assertTrue(requireNotNull(characters.getById("card")).pendingSpeakerRenameTasks.isEmpty())
        assertEquals(0, service.execute("card", "task"))
    }

    private class TestContext(private val dir: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = dir
    }
}
