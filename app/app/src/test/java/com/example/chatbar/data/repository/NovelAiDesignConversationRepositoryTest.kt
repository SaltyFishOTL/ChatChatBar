package com.example.chatbar.data.repository

import android.content.ContextWrapper
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.domain.image.DesignedCharacterCenter
import com.example.chatbar.domain.image.NovelAiCharacterCaption
import com.example.chatbar.domain.image.NovelAiDesignReply
import com.example.chatbar.domain.image.NovelAiDesignTurnStatus
import com.example.chatbar.domain.image.NovelAiImageModel
import com.example.chatbar.domain.image.NovelAiPromptPlan
import java.io.File
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class NovelAiDesignConversationRepositoryTest {
    @get:Rule
    val temp = TemporaryFolder()

    @Test
    fun `first valid message creates current conversation with model snapshots`() = runTest {
        val repository = repository()
        repository.initialize()

        assertNull(repository.currentConversation())
        val (conversation, turn) = repository.createCurrentConversation(
            userText = "  雨夜街道\n第二行  ",
            designModelId = "designer-v1",
            targetImageModel = NovelAiImageModel.V5_FULL
        )

        assertEquals(conversation.id, repository.currentConversation()?.id)
        assertEquals("雨夜街道", conversation.title)
        assertEquals("雨夜街道\n第二行", turn.userText)
        assertEquals("designer-v1", turn.designModelId)
        assertEquals(NovelAiImageModel.V5_FULL, turn.targetImageModel)
        assertTrue(repository.history().isEmpty())
    }

    @Test
    fun `current pointer changes only after another conversation is created or selected`() = runTest {
        val repository = repository()
        repository.initialize()
        val first = repository.createCurrentConversation(
            "第一条",
            "designer",
            NovelAiImageModel.V4_5_FULL
        ).first

        assertEquals(first.id, repository.currentConversation()?.id)
        val second = repository.createCurrentConversation(
            "第二条",
            "designer",
            NovelAiImageModel.V5_FULL
        ).first
        assertEquals(second.id, repository.currentConversation()?.id)
        assertEquals(listOf(first.id), repository.history().map { it.id })

        repository.switchCurrent(first.id)

        assertEquals(first.id, repository.currentConversation()?.id)
        assertEquals(listOf(second.id), repository.history().map { it.id })
    }

    @Test
    fun `history pruning retains current plus one hundred historical conversations`() = runTest {
        val repository = repository()
        repository.initialize()

        repeat(102) { index ->
            repository.createCurrentConversation(
                userText = "会话 $index",
                designModelId = "designer",
                targetImageModel = NovelAiImageModel.V4_5_FULL
            )
        }

        assertEquals(100, repository.history().size)
        assertEquals(101, repository.conversations.value.size)
        assertEquals("会话 101", repository.currentConversation()?.turns?.single()?.userText)
        assertTrue(repository.conversations.value.none { it.title == "会话 0" })
    }

    @Test
    fun `failed turn blocks followup and retry can complete it`() = runTest {
        val repository = repository()
        repository.initialize()
        val (conversation, turn) = repository.createCurrentConversation(
            "初始画面",
            "designer-a",
            NovelAiImageModel.V4_5_FULL
        )
        repository.failTurn(conversation.id, turn.id, "invalid JSON")
        assertNull(repository.currentConversation()?.turns?.single()?.reply)

        val appendError = runCatching {
            repository.appendPendingTurn(
                conversation.id,
                "增加月光",
                "designer-a",
                NovelAiImageModel.V4_5_FULL
            )
        }.exceptionOrNull()
        assertTrue(appendError is IllegalArgumentException)

        repository.markTurnPending(
            conversation.id,
            turn.id,
            "designer-b",
            NovelAiImageModel.V5_FULL
        )
        repository.completeTurn(
            conversation.id,
            turn.id,
            NovelAiDesignReply(
                plan = promptPlan(),
                targetImageModel = NovelAiImageModel.V5_FULL,
                designModelId = "designer-b"
            )
        )

        val completed = repository.currentConversation()?.turns?.single()
        assertEquals(NovelAiDesignTurnStatus.COMPLETED, completed?.status)
        assertEquals("designer-b", completed?.designModelId)
        assertEquals(NovelAiImageModel.V5_FULL, completed?.targetImageModel)
        assertEquals("new base", completed?.reply?.plan?.baseCaption)
    }

    private fun repository(): NovelAiDesignConversationRepository =
        NovelAiDesignConversationRepository(
            JsonFileStorage(TestContext(temp.newFolder("files-${System.nanoTime()}")))
        )

    private fun promptPlan() = NovelAiPromptPlan(
        baseCaption = "new base",
        characterCaptions = listOf(
            NovelAiCharacterCaption("new character", DesignedCharacterCenter(0.5f, 0.5f))
        )
    )

    private class TestContext(private val dir: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = dir
    }
}
