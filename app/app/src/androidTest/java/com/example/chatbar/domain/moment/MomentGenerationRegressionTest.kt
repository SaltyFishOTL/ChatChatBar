package com.example.chatbar.domain.moment

import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.domain.chat.StreamingChatService
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class MomentGenerationRegressionTest {
    @Test fun repetitionDecisionNeverBlocksAValidDraftAndBlankDraftStillFails() = runBlocking {
        val app = ApplicationProvider.getApplicationContext<ChatBarApp>()
        val service = MomentGenerationService(StreamingChatService(), app.novelAiPromptDesigner,
            app.novelAiImageService, app.novelAiImageStorage, app.novelAiCredentialStore)
        val card = CharacterCard.create("synthetic")
        val session = ChatSession(id = "synthetic-session", characterCardId = card.id, title = "test", createdAt = 1, updatedAt = 1)
        val messages = listOf(ChatMessage("message", session.id, MessageRole.USER, "test input", createdAt = 1, updatedAt = 1))
        val model = ModelConfig(id = "offline", displayName = "offline", baseUrl = "https://invalid.test/v1", apiKey = "", modelName = "test", createdAt = 1)
        val checkpoint = MomentGenerationCheckpoint(decision = MomentPostDecision(false, "repeat"),
            draft = MomentDraft(shouldPost = false, text = "synthetic repeated text"))
        var previous: MomentPost? = null
        repeat(2) { index ->
            val result = service.generate(card, session, messages, previous, model, null,
                scheduledAt = index + 1L, autoGenerateImages = false, resumeFrom = checkpoint)
            assertTrue(result is MomentGenerationResult.Posted)
            val post = (result as MomentGenerationResult.Posted).post
            assertEquals(checkpoint.draft!!.text, post.text)
            assertNotEquals(previous?.id, post.id)
            previous = post
        }
        assertTrue(runCatching {
            service.generate(card, session, messages, previous, model, null, scheduledAt = 3,
                autoGenerateImages = false, resumeFrom = checkpoint.copy(draft = MomentDraft(text = "")))
        }.isFailure)
    }
}
