package com.example.chatbar.ui.chat

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.domain.chat.MessageAlternativeVersionPolicy
import com.example.chatbar.domain.chat.MessageFormatRepairPolicy
import com.example.chatbar.domain.prompt.PromptTemplates
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test

/** Actual VM/service HTTP retry and cold JSON read; all traffic is synthetic loopback. */
class FormatRepairRetryRuntimeTest {
    @Test fun unchangedMarkerSuccessClearsPreviousFailure() = verifyRetry(true)
    @Test fun originalTextSuccessClearsPreviousFailure() = verifyRetry(false)

    private fun verifyRetry(marker: Boolean) = runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<ChatBarApp>()
        check(app.packageName == "com.example.chatbar.pr7secondreview.r1")
        withTimeout(30_000) { app.dependenciesReady.first { it } }
        withTimeout(30_000) { app.settingsRepository.isInitialized.first { it } }
        val settings = app.settingsRepository.getAppSettings()
        val store = ViewModelStore()
        val server = MockWebServer()
        server.start()
        val model = ModelConfig.create("Synthetic retry model", server.url("/v1").toString(),
            "synthetic-retry-key", "synthetic-model")
        val card = CharacterCard.create("Synthetic retry card")
        val session = ChatSession.create(card.id, "Synthetic retry").copy(modelId = model.id,
            longTermMemoryEnabled = false, automaticImageGenerationEnabled = false)
        val reply = MessageAlternativeVersionPolicy.append(ChatMessage.create(session.id,
            MessageRole.ASSISTANT, "another candidate"), "synthetic current text", "current-version")
        val other = ChatMessage.create(session.id, MessageRole.USER, "synthetic independent record")
        try {
            app.modelRepository.saveModel(model)
            app.characterRepository.save(card)
            app.chatRepository.createSession(session)
            app.chatRepository.addMessage(reply)
            app.chatRepository.addMessage(other)
            app.settingsRepository.saveAppSettings(settings.copy(allowCleartextModelApi = true,
                assistantSegmentedBubblesEnabled = true, formatRepairModelId = model.id))
            val vm = withContext(Dispatchers.Main) {
                ViewModelProvider(store, ChatViewModelFactory(session.id))[ChatViewModel::class.java]
            }
            withTimeout(30_000) { vm.session.first { it?.id == session.id } }
            withTimeout(30_000) { vm.messages.first { messages -> messages.any { it.id == reply.id } } }
            server.enqueue(MockResponse().setResponseCode(400)
                .setBody("{\"error\":{\"message\":\"synthetic first failure\"}}"))
            suspend fun repair() {
                withContext(Dispatchers.Main) { vm.repairMessageFormat(reply.id) }
                val request = requireNotNull(server.takeRequest(15, TimeUnit.SECONDS)) { "loopback request missing" }
                assertEquals("/v1/chat/completions", request.path)
                assertEquals("Bearer synthetic-retry-key", request.getHeader("Authorization"))
                withTimeout(30_000) { vm.isResponding.first { !it } }
            }
            repair()
            val cold = ChatRepository(JsonFileStorage(app))
            val failed = requireNotNull(cold.getMessage(reply.id, session.id))
            assertEquals(MessageFormatRepairNoticeKind.ERROR, failed.formatRepairNotice?.kind)
            assertNotNull(MessageFormatRepairPolicy.applicableNotice(failed))
            val content = if (marker) PromptTemplates.MESSAGE_FORMAT_REPAIR_UNCHANGED_MARKER else reply.displayContent
            val event = buildJsonObject { put("choices", buildJsonArray { add(buildJsonObject {
                put("delta", buildJsonObject { put("content", content) }); put("finish_reason", "stop")
            }) }) }.toString()
            server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream")
                .setBody("data: $event\n\ndata: [DONE]\n\n"))
            repair()
            val successful = requireNotNull(cold.getMessage(reply.id, session.id))
            assertNull("Successful unchanged retry must clear the previous failure", successful.formatRepairNotice)
            assertNull(MessageFormatRepairPolicy.applicableNotice(successful))
            assertEquals(reply.displayContent, successful.displayContent)
            assertEquals(reply.alternatives, successful.alternatives)
            assertEquals(reply.alternativeVersionIds, successful.alternativeVersionIds)
            assertEquals(other.content, cold.getMessage(other.id, session.id)?.content)
            assertNull(vm.messages.value.single { it.id == reply.id }.formatRepairNotice)
            assertEquals(2, server.requestCount)
        } finally {
            withContext(Dispatchers.Main) { store.clear() }
            app.settingsRepository.saveAppSettings(settings)
            app.voiceMessageRepository.deleteForSession(session.id)
            app.chatRepository.deleteMessagesForSession(session.id)
            app.chatRepository.deleteSessionRecord(session.id)
            app.characterRepository.delete(card.id)
            app.modelRepository.deleteModel(model.id)
            server.shutdown()
        }
    }
}
