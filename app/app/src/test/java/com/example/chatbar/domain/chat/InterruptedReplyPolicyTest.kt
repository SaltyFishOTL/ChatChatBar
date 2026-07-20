package com.example.chatbar.domain.chat

import android.content.ContextWrapper
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.repository.ChatRepository
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class InterruptedReplyPolicyTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `nonblank interrupted assistant reply remains a normal source turn message`() = runTest {
        val repository = ChatRepository(
            JsonFileStorage(TestContext(temp.newFolder("files")))
        )
        repository.createSession(
            ChatSession(
                id = SESSION_ID,
                characterCardId = "character",
                title = "test",
                createdAt = 1L,
                updatedAt = 1L
            )
        )
        val user = repository.addMessage(
            message(
                id = "user",
                role = MessageRole.USER,
                content = "继续"
            )
        )
        val draft = message(
            id = "assistant",
            role = MessageRole.ASSISTANT,
            content = "已经生成的部分",
            reasoning = "部分思考"
        )

        val persisted = repository.addMessage(
            requireNotNull(InterruptedReplyPolicy.persistableDraft(draft))
        )

        assertEquals("已经生成的部分", repository.getMessage("assistant", SESSION_ID)?.content)
        assertEquals("部分思考", persisted.reasoningContent)
        assertNotNull(persisted.sourceTurnId)
        assertEquals(user.sourceTurnId, persisted.sourceTurnId)
        assertEquals(user.sourceTurnOrder, persisted.sourceTurnOrder)
    }

    @Test
    fun `blank interrupted reply is not persisted`() {
        val draft = message(
            id = "assistant",
            role = MessageRole.ASSISTANT,
            content = "",
            reasoning = "only reasoning"
        )

        assertNull(InterruptedReplyPolicy.persistableDraft(draft))
    }

    private fun message(
        id: String,
        role: MessageRole,
        content: String,
        reasoning: String? = null
    ) = ChatMessage(
        id = id,
        sessionId = SESSION_ID,
        role = role,
        content = content,
        reasoningContent = reasoning,
        createdAt = if (role == MessageRole.USER) 1L else 2L,
        updatedAt = if (role == MessageRole.USER) 1L else 2L
    )

    private class TestContext(private val dir: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = dir
    }

    private companion object {
        const val SESSION_ID = "session"
    }
}
