package com.example.chatbar.data.repository

import android.content.ContextWrapper
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.domain.image.DesignedCharacterCenter
import com.example.chatbar.domain.image.NovelAiCharacterCaption
import com.example.chatbar.domain.image.NovelAiDesignReply
import com.example.chatbar.domain.image.NovelAiDesignResearchSnapshot
import com.example.chatbar.domain.image.NovelAiDesignTagEvidenceSnapshot
import com.example.chatbar.domain.image.NovelAiDesignTurnStatus
import com.example.chatbar.domain.image.NovelAiImageModel
import com.example.chatbar.domain.image.NovelAiPromptPlan
import com.example.chatbar.domain.image.NovelAiPositivePromptSnapshot
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
            targetImageModel = NovelAiImageModel.V5_FULL,
            naturalLanguageMode = true
        )

        assertEquals(conversation.id, repository.currentConversation()?.id)
        assertEquals("雨夜街道", conversation.title)
        assertEquals("雨夜街道\n第二行", turn.userText)
        assertEquals("designer-v1", turn.designModelId)
        assertEquals(NovelAiImageModel.V5_FULL, turn.targetImageModel)
        assertTrue(turn.naturalLanguageMode)
        assertTrue(repository.history().isEmpty())
    }

    @Test
    fun `prompt attachment persists for create append retry and reload`() = runTest {
        val storage = JsonFileStorage(TestContext(temp.newFolder("attachment-files")))
        val repository = NovelAiDesignConversationRepository(storage)
        repository.initialize()
        val firstAttachment = NovelAiPositivePromptSnapshot(
            basePrompt = "1girl, rainy street",
            characterPrompts = listOf("girl, black hair", "boy, white hair")
        )
        val (conversation, firstTurn) = repository.createCurrentConversation(
            userText = "改成夜景",
            designModelId = "designer",
            targetImageModel = NovelAiImageModel.V5_FULL,
            attachedStudioPrompt = firstAttachment
        )
        repository.completeTurn(
            conversation.id,
            firstTurn.id,
            NovelAiDesignReply(promptPlan(), NovelAiImageModel.V5_FULL, "designer")
        )
        val secondAttachment = NovelAiPositivePromptSnapshot("2girls, beach", listOf("first", "second"))
        val secondTurn = repository.appendPendingTurn(
            conversationId = conversation.id,
            userText = "改成黄昏",
            designModelId = "designer",
            targetImageModel = NovelAiImageModel.V5_FULL,
            attachedStudioPrompt = secondAttachment
        )
        repository.failTurn(conversation.id, secondTurn.id, "failed")
        repository.markTurnPending(
            conversation.id,
            secondTurn.id,
            "designer-2",
            NovelAiImageModel.V5_FULL
        )

        val reloaded = NovelAiDesignConversationRepository(storage)
        reloaded.initialize()
        val turns = reloaded.currentConversation()?.turns.orEmpty()

        assertEquals(firstAttachment, turns[0].attachedStudioPrompt)
        assertEquals(secondAttachment, turns[1].attachedStudioPrompt)
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
            NovelAiImageModel.V5_FULL,
            naturalLanguageMode = true
        )
        repository.completeTurn(
            conversation.id,
            turn.id,
            NovelAiDesignReply(
                plan = promptPlan(),
                targetImageModel = NovelAiImageModel.V5_FULL,
                designModelId = "designer-b",
                naturalLanguageMode = true
            )
        )

        val completed = repository.currentConversation()?.turns?.single()
        assertEquals(NovelAiDesignTurnStatus.COMPLETED, completed?.status)
        assertEquals("designer-b", completed?.designModelId)
        assertEquals(NovelAiImageModel.V5_FULL, completed?.targetImageModel)
        assertTrue(completed?.naturalLanguageMode == true)
        assertEquals("new base", completed?.reply?.displayText)
        assertEquals("new base", completed?.reply?.plan?.baseCaption)
    }

    @Test
    fun `regenerating latest first reply replaces reply and initial research`() = runTest {
        val repository = repository()
        repository.initialize()
        val (conversation, turn) = repository.createCurrentConversation(
            "雨夜拥抱",
            "designer-a",
            NovelAiImageModel.V5_FULL
        )
        repository.completeTurn(
            conversation.id,
            turn.id,
            NovelAiDesignReply(promptPlan("old base"), NovelAiImageModel.V5_FULL, "designer-a"),
            initialResearch = research("旧证据")
        )

        assertEquals(turn.id, repository.currentConversation()?.latestRegeneratableTurnId)
        repository.markTurnPending(
            conversation.id,
            turn.id,
            "designer-b",
            NovelAiImageModel.V5_FULL
        )
        val pending = repository.currentConversation()?.turns?.single()
        assertEquals(NovelAiDesignTurnStatus.PENDING, pending?.status)
        assertEquals("old base", pending?.reply?.plan?.baseCaption)

        repository.completeTurn(
            conversation.id,
            turn.id,
            NovelAiDesignReply(promptPlan("regenerated base"), NovelAiImageModel.V5_FULL, "designer-b"),
            initialResearch = research("新证据"),
            replaceInitialResearch = true
        )

        val regenerated = repository.currentConversation()
        assertEquals("regenerated base", regenerated?.turns?.single()?.reply?.plan?.baseCaption)
        assertEquals("新证据", regenerated?.initialResearch?.tagEvidence?.single()?.name)
        assertEquals(turn.id, regenerated?.latestRegeneratableTurnId)
    }

    private fun repository(): NovelAiDesignConversationRepository =
        NovelAiDesignConversationRepository(
            JsonFileStorage(TestContext(temp.newFolder("files-${System.nanoTime()}")))
        )

    private fun promptPlan(baseCaption: String = "new base") = NovelAiPromptPlan(
        baseCaption = baseCaption,
        characterCaptions = listOf(
            NovelAiCharacterCaption("new character", DesignedCharacterCenter(0.5f, 0.5f))
        )
    )

    private fun research(name: String) = NovelAiDesignResearchSnapshot(
        tagEvidence = listOf(NovelAiDesignTagEvidenceSnapshot(query = "雨夜", name = name))
    )

    private class TestContext(private val dir: File) : ContextWrapper(null) {
        override fun getFilesDir(): File = dir
    }
}
