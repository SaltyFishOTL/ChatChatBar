package com.example.chatbar.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.example.chatbar.BuildConfig
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.repository.ModelRepository
import com.example.chatbar.domain.model.ModelDiscoveryService
import com.example.chatbar.domain.model.ModelImageInput
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.ChatBarTheme
import com.example.chatbar.ui.model.ModelEditViewModel
import com.example.chatbar.ui.model.ModelEditViewModelFactory
import java.io.File
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class VisionModelSetupWireTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    @Before
    fun isolate() {
        assumeTrue("Use a clean isolated test app", BuildConfig.ISOLATED_ANDROID_TEST)
        val app = ChatBarApp.instance
        check(app.packageName == BuildConfig.APPLICATION_ID && app.packageName.startsWith("com.example.chatbar."))
        runBlocking {
            withTimeout(30_000) { app.dependenciesReady.first { it } }
            withTimeout(30_000) { app.settingsRepository.isInitialized.first { it } }
        }
        rule.setContent { ChatBarTheme { CbText("Synthetic vision wire test") } }
    }

    @Test
    fun discoveryAndVisionUseTheCurrentExplicitConnectionWithoutReplacingPro() {
        verifyVisionRouting(separateEndpoint = false)
    }

    @Test
    fun separateVisionEndpointUsesItsOwnKeyAndProReceivesTheDescription() {
        verifyVisionRouting(separateEndpoint = true)
    }

    private fun verifyVisionRouting(separateEndpoint: Boolean) {
        val app = ChatBarApp.instance
        val settings = runBlocking { app.settingsRepository.getAppSettings() }
        val textServer = MockWebServer().apply { start() }
        val visionServer = if (separateEndpoint) MockWebServer().apply { start() } else textServer
        val text = ModelConfig.create(
            "Synthetic Pro", textServer.url("/v1").toString(), "synthetic-pro-key", "deepseek-v4-pro"
        )
        val visionKey = if (separateEndpoint) "synthetic-other-key" else text.apiKey
        val vision = ModelConfig.create(
            "Synthetic Flash", visionServer.url("/v1").toString(), visionKey, "deepseek-flash", isMultimodal = true
        ).copy(selectableForChat = false)
        val abandoned = vision.copy(id = UUID.randomUUID().toString(), modelName = "unconfirmed")
        val character = CharacterCard.create("Synthetic vision test")
        val session = ChatSession.create(character.id, "Synthetic vision request").copy(
            modelId = text.id, longTermMemoryEnabled = false, automaticImageGenerationEnabled = false
        )
        val file = File(app.filesDir, "vision-test-${UUID.randomUUID()}.png")
        file.writeBytes(Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAIAAAABCAYAAAD0In+KAAAAD0lEQVR4nGP4zwAE/xkaAAt+An+rqPSSAAAAAElFTkSuQmCC"
        ))
        val viewModels = ViewModelStore()
        try {
            runBlocking {
                app.settingsRepository.saveAppSettings(settings.copy(
                    allowCleartextModelApi = true, siliconFlowApiKey = "synthetic-global-must-not-be-used",
                    defaultModelId = text.id, defaultEmbeddingId = null, defaultFormatCardId = null,
                    automaticFormatCheckEnabled = false, momentsEnabled = false, experimentalApiModeEnabled = false
                ))
                app.modelRepository.saveModel(text)
                app.characterRepository.save(character)
                app.chatRepository.createSession(session)
            }

            visionServer.enqueue(MockResponse().setBody("""{"data":[
                {"id":"deepseek-flash","input_modalities":["text","image"]},
                {"id":"deepseek-v4-pro","input_modalities":["text"]}
            ]}"""))
            val models = runBlocking { ModelDiscoveryService().fetchModels(vision.baseUrl, vision.apiKey, true) }
            assertEquals(ModelImageInput.SUPPORTED, models.first { it.id == vision.modelName }.imageInput)
            assertEquals(ModelImageInput.NOT_SUPPORTED, models.first { it.id == text.modelName }.imageInput)
            val discovery = request(visionServer)
            assertEquals("GET", discovery.method)
            assertEquals("/v1/models", discovery.path)
            assertEquals("Bearer $visionKey", discovery.getHeader("Authorization"))

            lateinit var editor: ModelEditViewModel
            rule.runOnIdle {
                editor = ViewModelProvider(viewModels, ModelEditViewModelFactory(text.id))[ModelEditViewModel::class.java]
            }
            rule.waitUntil(20_000) { editor.modelConfig.value?.id == text.id && editor.apiKeyForCurrentVision.isNotEmpty() }
            val saved = AtomicBoolean(false)
            rule.runOnIdle {
                if (separateEndpoint) {
                    editor.baseUrl = vision.baseUrl
                    assertEquals("", editor.apiKeyForCurrentVision)
                    editor.saveModelConfig { error("Old endpoint credentials cannot be re-saved implicitly") }
                    assertNotNull(editor.saveError)
                    editor.baseUrl = text.baseUrl
                }
                assertEquals(text.apiKey, editor.apiKeyForCurrentVision)
                editor.stageVisionModel(abandoned)
                editor.selectVisionModel(null)
                editor.displayName = "Unsaved Pro alias"
                editor.stageVisionModel(vision)
                assertEquals("Unsaved Pro alias", editor.displayName)
                assertEquals(text.modelName, editor.modelName)
                editor.saveModelConfig { saved.set(true) }
            }
            rule.waitUntil(20_000) { saved.get() || editor.saveError != null }
            assertTrue(editor.saveError, saved.get())

            val cold = ModelRepository(JsonFileStorage(app))
            runBlocking {
                assertNull(cold.getModel(abandoned.id))
                assertEquals(text.copy(displayName = "Unsaved Pro alias", visionModelId = vision.id), cold.getModel(text.id))
                assertEquals(vision, cold.getModel(vision.id))
                assertEquals(text.id, app.effectiveModelResolver.defaultChatModel()?.id)
                assertFalse(app.effectiveModelResolver.availableChatModels().any { it.id == vision.id })
            }

            visionServer.enqueue(stream("A synthetic red bicycle"))
            textServer.enqueue(stream("Synthetic Pro reply"))
            lateinit var chat: ChatViewModel
            rule.runOnIdle {
                chat = ViewModelProvider(viewModels, ChatViewModelFactory(session.id))[ChatViewModel::class.java]
            }
            rule.waitUntil(20_000) { chat.session.value?.id == session.id && chat.isModelUsable.value }
            rule.runOnIdle { assertTrue(chat.sendMessage("请描述这张图。", listOf(file.absolutePath))) }

            val flash = request(visionServer)
            assertEquals("/v1/chat/completions", flash.path)
            assertEquals("Bearer $visionKey", flash.getHeader("Authorization"))
            val flashBody = flash.body.readUtf8()
            assertEquals("deepseek-flash", model(flashBody))
            assertTrue(flashBody.contains("image_url"))

            val pro = request(textServer)
            assertEquals("/v1/chat/completions", pro.path)
            assertEquals("Bearer ${text.apiKey}", pro.getHeader("Authorization"))
            val proBody = pro.body.readUtf8()
            assertEquals("deepseek-v4-pro", model(proBody))
            assertTrue(proBody.contains("A synthetic red bicycle"))
            assertFalse(proBody.contains("data:image"))
            rule.waitUntil(30_000) { !chat.isResponding.value }
            assertEquals(if (separateEndpoint) 1 else 3, textServer.requestCount)
            if (separateEndpoint) assertEquals(2, visionServer.requestCount)
        } finally {
            rule.runOnIdle { viewModels.clear() }
            runBlocking {
                app.settingsRepository.saveAppSettings(settings)
                app.voiceMessageRepository.deleteForSession(session.id)
                app.chatRepository.deleteMessagesForSession(session.id)
                app.chatRepository.deleteSessionRecord(session.id)
                app.modelRepository.deleteModel(text.id)
                app.modelRepository.deleteModel(vision.id)
            }
            file.delete()
            if (visionServer !== textServer) visionServer.shutdown()
            textServer.shutdown()
        }
    }

    private fun request(server: MockWebServer): RecordedRequest =
        checkNotNull(server.takeRequest(25, TimeUnit.SECONDS)) { "Expected a synthetic HTTP request" }

    private fun model(body: String): String = Json.parseToJsonElement(body).jsonObject.getValue("model").jsonPrimitive.content

    private fun stream(text: String): MockResponse {
        val delta = buildJsonObject { put("content", text) }.toString()
        return MockResponse().setHeader("Content-Type", "text/event-stream").setBody(
            "data: {\"choices\":[{\"delta\":$delta,\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
        )
    }
}
