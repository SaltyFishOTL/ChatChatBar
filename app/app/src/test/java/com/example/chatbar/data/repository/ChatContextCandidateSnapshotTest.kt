package com.example.chatbar.data.repository

import android.content.ContextWrapper
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ChatContextCandidateSnapshotTest {
    @get:Rule val temp = TemporaryFolder()
    private class TestContext(private val dir: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = dir
    }
    @Test fun pinnedOldTurnsAreNotCompleteSuffixEvidence() = runTest {
        val chats = ChatRepository(JsonFileStorage(TestContext(temp.newFolder())))
        chats.createSession(ChatSession("s", "card", "synthetic", createdAt = 1, updatedAt = 1))
        repeat(10) { n ->
            for (role in listOf(MessageRole.USER, MessageRole.ASSISTANT)) {
                val order = n * 2L + if (role == MessageRole.USER) 1 else 2
                chats.addMessage(ChatMessage("$n-${role.name}", "s", role, "synthetic body $n",
                    orderKey = order * MESSAGE_ORDER_STEP, createdAt = order, updatedAt = order,
                    sourceTurnId = "turn-$n", sourceTurnOrder = n.toLong()))
            }
        }
        val snapshot = chats.getContextCandidateSnapshot("s", 2, setOf("turn-0"))
        assertTrue(snapshot.messages.any { it.id == "0-ASSISTANT" })
        assertFalse("0-ASSISTANT" in snapshot.completeRecentMessageIds)
        assertEquals(setOf("8-USER", "8-ASSISTANT", "9-USER", "9-ASSISTANT"), snapshot.completeRecentMessageIds)
        assertEquals(chats.getContextCandidateMessages("s", 2, setOf("turn-0")), snapshot.messages)
        val cold = ChatRepository(JsonFileStorage(TestContext(temp.root.listFiles()!!.single())))
        assertEquals(snapshot, cold.getContextCandidateSnapshot("s", 2, setOf("turn-0")))
    }
    @Test fun unreadableRecentRecordCannotProveCompleteSuffix() = runTest {
        val root = temp.newFolder()
        val chats = ChatRepository(JsonFileStorage(TestContext(root)))
        chats.createSession(ChatSession("s", "card", "synthetic", createdAt = 1, updatedAt = 1))
        chats.addMessage(ChatMessage("a", "s", MessageRole.ASSISTANT, "known", createdAt = 1, updatedAt = 1))
        chats.getContextCandidateSnapshot("s", 2) // establish the indexed universe
        val file = File(root, "entities/chat_messages/s_a.json")
        assertTrue(file.isFile)
        file.writeText("{not valid JSON")
        val snapshot = chats.getContextCandidateSnapshot("s", 2)
        assertTrue(snapshot.completeRecentMessageIds.isEmpty())
        assertEquals("{not valid JSON", file.readText())
    }
}
