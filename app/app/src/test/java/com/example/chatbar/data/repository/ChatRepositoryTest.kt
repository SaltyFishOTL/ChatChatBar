package com.example.chatbar.data.repository

import android.content.ContextWrapper
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.ChatSession
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
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

    private class TestContext(private val dir: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = dir
    }
}
