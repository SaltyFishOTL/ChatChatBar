package com.example.chatbar.ui.chat

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import androidx.test.core.app.ApplicationProvider
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.data.repository.VoiceMessageRepository
import com.example.chatbar.domain.chat.MessageAlternativeVersionPolicy
import com.example.chatbar.domain.chat.MessageFormatRepairPolicy
import com.example.chatbar.domain.prompt.PromptTemplates
import com.example.chatbar.domain.voice.VoiceGenerationPolicy
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test

/** Actual VM/service HTTP retry and cold JSON read; all traffic is synthetic loopback. */
class FormatRepairRetryRuntimeTest {
    @Test fun unchangedMarkerSuccessClearsPreviousFailure() = verifyRetry(true)
    @Test fun originalTextSuccessClearsPreviousFailure() = verifyRetry(false)

    @Test fun undoThenFailedAiRetryStillAllowsLocalRedo() = verifyRetry(true, keepRedo = true)
    @Test fun lateRepairSuccessCannotResurrectMessageAfterRestore() =
        verifyRetry(lateResponse = repairResponse("synthetic late repair"))
    @Test fun lateUnchangedResultCannotResurrectMessageAfterRestore() =
        verifyRetry(lateResponse = repairResponse(PromptTemplates.MESSAGE_FORMAT_REPAIR_UNCHANGED_MARKER))
    @Test fun lateRepairFailureCannotResurrectMessageAfterRestore() =
        verifyRetry(lateResponse = failureResponse())

    private fun verifyRetry(marker: Boolean = false, keepRedo: Boolean = false, lateResponse: MockResponse? = null) = runBlocking<Unit> {
        val app = ApplicationProvider.getApplicationContext<ChatBarApp>()
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
        val baseReply = MessageAlternativeVersionPolicy.append(ChatMessage.create(session.id,
            MessageRole.ASSISTANT, "another candidate"), "synthetic current text", "current-version")
        val reply = if (keepRedo) MessageFormatRepairPolicy.replaceCurrentDisplayContent(baseReply, "cached repaired result",
            MessageFormatRepairPolicy.completedRepairNotice(baseReply.displayContent, "cached repaired result")) else baseReply
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
            if (lateResponse != null) {
                verifyRestoreDuringRepair(app, vm, server, reply, other.copy(role = MessageRole.ASSISTANT), lateResponse)
                return@runBlocking
            }
            if (keepRedo) {
                withContext(Dispatchers.Main) { vm.restoreMessageFormatOriginal(reply.id) }
                withTimeout(15_000) { vm.messages.first { messages -> messages.any {
                    it.id == reply.id && it.formatRepairNotice?.restored == true } } }
            }
            server.enqueue(failureResponse())
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
            if (keepRedo) {
                val notice = requireNotNull(failed.formatRepairNotice)
                assertNotNull("Failed AI retry must retain the cached redo pair", notice.originalContent)
                assertTrue(notice.restored)
                assertEquals(baseReply.displayContent, failed.displayContent)
                assertEquals("cached repaired result", notice.targetContent)
                assertNotNull(notice.errorMessage)
                withContext(Dispatchers.Main) { vm.redoMessageFormatRepair(reply.id) }
                withTimeout(15_000) { vm.messages.first { messages -> messages.any {
                    it.id == reply.id && it.displayContent == "cached repaired result" && it.formatRepairNotice?.restored == false } } }
                val redone = requireNotNull(cold.getMessage(reply.id, session.id))
                assertEquals("cached repaired result", redone.displayContent)
                assertNull(redone.formatRepairNotice?.errorMessage)
                assertEquals(reply.alternativeVersionIds, redone.alternativeVersionIds)
                assertEquals(other.content, cold.getMessage(other.id, session.id)?.content)
                assertEquals("Local redo must not send a model request", 1, server.requestCount)
                return@runBlocking
            }
            assertEquals(MessageFormatRepairNoticeKind.ERROR, failed.formatRepairNotice?.kind)
            assertNotNull(MessageFormatRepairPolicy.applicableNotice(failed))
            val content = if (marker) PromptTemplates.MESSAGE_FORMAT_REPAIR_UNCHANGED_MARKER else reply.displayContent
            server.enqueue(repairResponse(content))
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
            app.longTermMemoryAutoMaintenanceCoordinator.cancelAndJoinForSession(session.id)
            app.settingsRepository.saveAppSettings(settings)
            app.voiceMessageRepository.deleteForSession(session.id)
            app.chatRepository.deleteMessagesForSession(session.id)
            app.chatRepository.deleteSessionRecord(session.id)
            app.characterRepository.delete(card.id)
            app.modelRepository.deleteModel(model.id)
            server.shutdown()
        }
    }

    private suspend fun verifyRestoreDuringRepair(
        app: ChatBarApp,
        vm: ChatViewModel,
        server: MockWebServer,
        reply: ChatMessage,
        retained: ChatMessage,
        lateResponse: MockResponse
    ) {
        val audio = File(app.filesDir, "audio/generated/${retained.sessionId}/restore-fixture.mp3")
        val audioBytes = "synthetic archived voice".toByteArray()
        check(audio.parentFile!!.mkdirs())
        audio.writeBytes(audioBytes)
        val voice = GeneratedVoiceMessage.create(
            sessionId = retained.sessionId, messageId = retained.id, messageVersionId = retained.id,
            anchorId = null, sourceOrder = 0, sourceSegmentKind = VoiceGenerationPolicy.WHOLE_MESSAGE_SOURCE_KIND,
            sourceSpeakerName = "", sourceText = retained.content, taggedText = retained.content,
            characterId = "synthetic-character", characterName = "Synthetic",
            voice = FishAudioVoiceBinding("synthetic-voice", "Synthetic"), fishModelId = "synthetic-model",
            audioPath = audio.path, durationMs = 1, byteLength = audio.length()
        )
        val slot = SaveSlot.create(retained.sessionId, "Synthetic restore target", messages = listOf(retained)).copy(
            voiceMessages = listOf(voice), longTermMemoryEnabled = false
        )
        val releaseResponse = CountDownLatch(1)
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse =
                if (releaseResponse.await(30, TimeUnit.SECONDS)) lateResponse else MockResponse().setResponseCode(504)
        }
        try {
            app.saveSlotRepository.save(slot)
            withContext(Dispatchers.Main) { vm.repairMessageFormat(reply.id) }
            assertNotNull("Loopback request must be in flight before restore", server.takeRequest(15, TimeUnit.SECONDS))
            withContext(Dispatchers.Main) { vm.loadSaveSlot(slot.toSummary()) }
            withTimeout(20_000) { vm.saveSlotOperation.first { it.status == "读档完成。" } }
            withTimeout(15_000) { vm.messages.first { rows -> rows.map { it.id } == listOf(retained.id) } }
            releaseResponse.countDown()
            withTimeout(20_000) { vm.isResponding.first { !it } }

            val storage = JsonFileStorage(app)
            val cold = ChatRepository(storage)
            assertNull("The removed message record must stay absent", cold.getMessage(reply.id, retained.sessionId))
            assertEquals(listOf(retained.id), cold.getMessages(retained.sessionId).map(ChatMessage::id))
            assertEquals(retained.content, cold.getRecentMessages(retained.sessionId, 1).single().content)
            assertEquals(retained.content, cold.getSession(retained.sessionId)?.lastMessagePreview)
            val restoredVoice = VoiceMessageRepository(storage).listForSession(retained.sessionId).single()
            assertEquals(voice.id, restoredVoice.id)
            assertEquals(retained.id, restoredVoice.messageId)
            assertArrayEquals(audioBytes, File(restoredVoice.audioPath).readBytes())
        } finally {
            releaseResponse.countDown()
            app.saveSlotRepository.delete(slot.id)
            audio.delete()
            audio.parentFile?.delete()
        }
    }

    private fun failureResponse(): MockResponse = MockResponse().setResponseCode(400)
        .setBody("{\"error\":{\"message\":\"synthetic first failure\"}}")

    private fun repairResponse(content: String): MockResponse {
        val event = buildJsonObject {
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("delta", buildJsonObject { put("content", content) })
                    put("finish_reason", "stop")
                })
            })
        }.toString()
        return MockResponse().setHeader("Content-Type", "text/event-stream")
            .setBody("data: $event\n\ndata: [DONE]\n\n")
    }
}
