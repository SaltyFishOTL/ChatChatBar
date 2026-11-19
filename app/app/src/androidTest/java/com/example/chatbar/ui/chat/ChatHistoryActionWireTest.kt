package com.example.chatbar.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.example.chatbar.BuildConfig
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.*
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.ChatBarTheme
import java.io.File
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.*
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

/** Real ViewModel/query/window/placeholder/status/narrative pipeline -> serialized loopback HTTP. */
class ChatHistoryActionWireTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val independent = "我推开木门，走到窗边观察街上的雨。"
    private fun options(marker: String) = "————————\n【行动选项】\n1. ${marker}_LEFT\n2. ${marker}_RIGHT\n————————"
    private fun assistant(marker: String) = "NARRATION_$marker 衣着青色大衣 {{user}}\n${options(marker)}"
    private fun row(role: MessageRole, text: String) = ChatMessage.create("placeholder", role, text)
    private fun a(marker: String) = row(MessageRole.ASSISTANT, assistant(marker))
    private fun u(text: String = independent) = row(MessageRole.USER, text)

    @Before fun surface() {
        assumeTrue("Run with -PCHATBAR_ISOLATED_ANDROID_TEST=true on a clean emulator", BuildConfig.ISOLATED_ANDROID_TEST)
        val app = ChatBarApp.instance
        check(app.packageName == BuildConfig.APPLICATION_ID && app.packageName.startsWith("com.example.chatbar."))
        runBlocking { withTimeout(30000) { app.dependenciesReady.first { it } } }
        runBlocking { withTimeout(30000) { app.settingsRepository.isInitialized.first { it } } }
        rule.setContent { ChatBarTheme { CbText("Synthetic action projection wire verification") } }
    }

    @Test fun independentTextOmitsOnlySelectedAssistantOptionsAndPreservesVersions() {
        val old = a("OLD").copy(alternatives = listOf(assistant("UNSELECTED"), assistant("SELECTED")), currentAlternativeIndex = 1)
        verify("independent", listOf(u(), old), independent) { wire, _ ->
            assertTrue(wire.contains("NARRATION_SELECTED")); assertTrue(wire.contains("青色大衣"))
            assertFalse(wire.contains("SELECTED_LEFT")); assertFalse(wire.contains("UNSELECTED"))
            assertTrue(wire.contains("<历史回复")) // Android's existing narrative wrapping still runs.
        }
    }
    @Test fun numericAndDeicticCurrentInputPreserveItsActionSources() {
        for (input in listOf("2", "就照刚才的那个行动做。", "请继续生成接下来发生的故事。", "贰，然后看看她接下来的反应。", "I will carry it out tomorrow.", "Please carry it out tomorrow.", "We shall proceed with them tomorrow.", "Please select B and wait quietly.", "I choose B and then wait for her.", "那就选乙，然后再看看她接下来的反应。", "我打算照她所说的计划去做然后再回来。")) {
            verify("current-${UUID.randomUUID()}", listOf(a("OLD")), input) { wire, contents ->
                assertTrue(wire.contains("OLD_LEFT")); assertTrue(contents.contains(input))
            }
        }
        val letterChoices = row(MessageRole.ASSISTANT, "我看向木门。\n<cyoa>\nA) Read the map\nB) Wait by the door\n</cyoa>")
            .copy(actionOptionsFormat = ActionOptionsFormat("<cyoa>", "</cyoa>", "", ActionOptionLabels.LETTERS))
        for ((index, reference) in listOf(
            "Let's go with B and wait quietly.",
            "She suggested A so we will wait quietly."
        ).withIndex()) {
            verify("declared-letter-reference-$index", listOf(letterChoices), reference) { wire, contents ->
                assertTrue(wire.contains("A) Read the map"))
                assertTrue(wire.contains("B) Wait by the door"))
                assertTrue(contents.contains(reference))
            }
        }
        val nineOptions = ('A'..'I').joinToString("\n") { "$it) Action $it" }
        val longMenu = letterChoices.copy(content = "旁白\n<cyoa>\n$nineOptions\n</cyoa>")
        val referenceI = "She suggested I so we will wait quietly."
        verify("declared-letter-i-reference", listOf(longMenu), referenceI) { wire, contents ->
            assertTrue(wire.contains("I) Action I"))
            assertTrue(contents.contains(referenceI))
        }
        val lowerMenu = letterChoices.copy(content = letterChoices.content.replace("A)", "a)").replace("B)", "b)"))
        val lowerReference = "a 然后静静地等待门口下一轮反应。"
        verify("declared-letter-lowercase-reference", listOf(lowerMenu), lowerReference) { wire, contents ->
            assertTrue(wire.contains("a) Read the map"))
            assertTrue(contents.contains(lowerReference))
        }
    }
    @Test fun historicalNumericReferenceProtectsEarlierButNotLaterIndependentOptions() {
        for (exclude in listOf(true, false)) {
            verify("historical-$exclude", listOf(u(), a("OLD"), u("2"), a("NEW")), independent, exclude = exclude) { wire, contents ->
                // A reference protects what remains after the existing earlier-status policy;
                // it must not restore a block that the user's old setting already excludes.
                assertEquals(!exclude, wire.contains("OLD_LEFT")); assertFalse(wire.contains("NEW_LEFT"))
                assertTrue(contents.contains("2"))
            }
        }
    }
    @Test fun uiContinueHasTypedSourceButResumingUnansweredNumericUserKeepsOptions() {
        verify("ui-continue", listOf(a("OLD")), "") { wire, _ -> assertFalse(wire.contains("OLD_LEFT")) }
        verify("resume-user", listOf(a("OLD"), u("2")), "") { wire, contents ->
            assertTrue(wire.contains("OLD_LEFT")); assertEquals(1, contents.count { it == "2" })
        }
    }
    @Test fun regenerationWithoutNewUserStillProtectsHistoricalNumericReferences() {
        verify("regen-numeric", listOf(a("OLD"), u("2"), a("TARGET")), regenerate = true) { wire, contents ->
            assertTrue(wire.contains("OLD_LEFT")); assertFalse(wire.contains("NARRATION_TARGET"))
            assertTrue(contents.contains("2"))
        }
        verify("regen-no-user", listOf(a("OLD"), a("TARGET")), regenerate = true) { wire, _ ->
            assertFalse(wire.contains("OLD_LEFT")); assertFalse(wire.contains("NARRATION_TARGET"))
        }
    }
    @Test fun statusSettingIsIndependentAndPreviousTurnRetainsStatus() {
        val first = a("OLD").copy(content = assistant("OLD") + "\n```status\nSTATUS_EARLIER\n```")
        val previous = a("NEW").copy(content = assistant("NEW") + "\n```status\nSTATUS_PREVIOUS\n```")
        for (exclude in listOf(true, false)) {
            verify("status-$exclude", listOf(u(), first, u(), previous), independent, exclude = exclude) { wire, _ ->
                assertEquals(!exclude, wire.contains("STATUS_EARLIER"))
                assertTrue(wire.contains("STATUS_PREVIOUS"))
                assertFalse(wire.contains("OLD_LEFT")); assertFalse(wire.contains("NEW_LEFT"))
            }
        }
    }
    @Test fun ordinaryListsCodeAndUnknownOptionsArePreserved() {
        val raw = "普通清单\n1. 地图\n2. 水\n```text\n${options("CODE")}\n```\n" +
            "————————\n【行动选项】\n1. WRONG_LEFT\n3. WRONG_RIGHT\n————————"
        verify("unreliable", listOf(row(MessageRole.ASSISTANT, raw), a("RELIABLE")), independent) { wire, _ ->
            assertTrue(wire.contains("CODE_LEFT")); assertTrue(wire.contains("WRONG_LEFT"))
            assertTrue(wire.contains("1. 地图")); assertFalse(wire.contains("RELIABLE_LEFT"))
        }
    }
    @Test fun userLiteralActionBlockIsNeverRewritten() {
        val input = independent + "\n" + options("USER_LITERAL")
        verify("user-literal", listOf(a("OLD")), input) { wire, contents ->
            assertTrue(wire.contains("OLD_LEFT")); assertTrue(contents.contains(input))
        }
    }

    @Test fun imageUserProtectsOnlyItsPrecedingSourcesNotLaterIndependentOptions() {
        verify("image-history", listOf(a("OLD"), u().copy(images = listOf("/synthetic-not-opened.png")), a("NEW")), independent, exclude = false) { wire, _ ->
            assertTrue(wire.contains("OLD_LEFT")); assertFalse(wire.contains("NEW_LEFT"))
        }
    }
    @Test fun currentImageWithLongTextStillProtectsTheOptionsItMayReference() {
        verify("current-image", listOf(a("OLD")), independent, withCurrentImage = true) { wire, contents ->
            assertTrue(wire.contains("OLD_LEFT")); assertTrue(contents.contains(independent))
        }
    }
    @Test fun reliableOptionsOnlyAssistantDoesNotLeakAnEmptyNarrativeWrapper() {
        verify("options-only", listOf(row(MessageRole.ASSISTANT, options("ONLY"))), independent) { wire, _ ->
            assertFalse(wire.contains("ONLY_LEFT"))
            assertFalse(wire.contains("<历史回复"))
        }
    }

    private fun verify(label: String, messages: List<ChatMessage>, input: String = "", regenerate: Boolean = false,
        exclude: Boolean = true, withCurrentImage: Boolean = false, assertions: (String, List<String>) -> Unit) {
        val app = ChatBarApp.instance
        val settings = runBlocking { app.settingsRepository.getAppSettings() }
        val server = MockWebServer()
        val event = buildJsonObject { put("choices", buildJsonArray { add(buildJsonObject {
            put("delta", buildJsonObject { put("content", "Synthetic generated response") }); put("finish_reason", "stop")
        }) }) }.toString()
        server.enqueue(MockResponse().setHeader("Content-Type", "text/event-stream").setBody("data: $event\n\ndata: [DONE]\n\n"))
        server.start()
        val model = ModelConfig.create("Synthetic action model", server.url("/v1").toString(), "synthetic-action-key", "synthetic-model", isMultimodal = withCurrentImage)
        val image = if (withCurrentImage) File(app.filesDir, "action-input-${UUID.randomUUID()}.png").also {
            it.writeBytes(java.util.Base64.getDecoder().decode(
                "iVBORw0KGgoAAAANSUhEUgAAAAIAAAABCAYAAAD0In+KAAAAD0lEQVR4nGP4zwAE/xkaAAt+An+rqPSSAAAAAElFTkSuQmCC"))
        } else null
        val card = CharacterCard.create("Synthetic action card ${UUID.randomUUID()}")
        val session = ChatSession.create(card.id, "Synthetic action history").copy(modelId = model.id,
            longTermMemoryEnabled = false, automaticImageGenerationEnabled = false, contextWindowSize = 20)
        val store = ViewModelStore()
        val originals = runBlocking {
            app.modelRepository.saveModel(model); app.characterRepository.save(card); app.chatRepository.createSession(session)
            val saved = messages.mapIndexed { n, message -> app.chatRepository.addMessage(message.copy(sessionId = session.id,
                orderKey = (n + 1L) * MESSAGE_ORDER_STEP, createdAt = n + 1L, updatedAt = n + 1L)) }
            app.settingsRepository.saveAppSettings(settings.copy(allowCleartextModelApi = true,
                defaultEmbeddingId = null, defaultFormatCardId = null, automaticFormatCheckEnabled = false,
                momentsEnabled = false, excludeAssistantStatusFromHistory = exclude, disableNarrativeModes = false))
            saved
        }
        try {
            lateinit var vm: ChatViewModel
            rule.runOnIdle { vm = ViewModelProvider(store, ChatViewModelFactory(session.id))[ChatViewModel::class.java] }
            rule.waitUntil(20000) { vm.session.value?.id == session.id && vm.messages.value.size >= messages.size && vm.isModelUsable.value }
            rule.runOnIdle { if (regenerate) vm.regenerateResponse(originals.last().id) else assertTrue(vm.sendMessage(input, listOfNotNull(image?.absolutePath))) }
            val request = server.takeRequest(25, TimeUnit.SECONDS)
            assertNotNull("Actual HTTP request must arrive for $label", request)
            assertEquals("/v1/chat/completions", request!!.path)
            assertEquals("Bearer synthetic-action-key", request.getHeader("Authorization"))
            val body = request.body.readUtf8()
            val json = Json.parseToJsonElement(body).jsonObject
            val contents = json.getValue("messages").jsonArray.map {
                when (val content = it.jsonObject.getValue("content")) {
                    is JsonPrimitive -> content.content
                    is JsonArray -> content.mapNotNull { part -> part.jsonObject["text"]?.jsonPrimitive?.content }.joinToString("\n")
                    else -> error("Unexpected serialized chat content")
                }
            }
            val out = File(app.filesDir, "action-history-wire").also { it.mkdirs() }
            val wireFile = File(out, "$label.json")
            // Keep the exact request even when an assertion below exposes a regression.
            wireFile.writeText(buildJsonObject {
                put("stage", "REQUEST_RECEIVED"); put("request", json); put("originals", Json.encodeToJsonElement(originals))
            }.toString())
            assertions(contents.joinToString("\n"), contents)
            rule.waitUntil(20000) { !vm.isResponding.value }
            assertEquals("Only the synthetic completion is requested", 1, server.requestCount)
            val cold = ChatRepository(JsonFileStorage(app))
            val after = runBlocking { originals.map { cold.getMessage(it.id, session.id) } }
            val currentInput = runBlocking { cold.getMessages(session.id).lastOrNull { it.role == MessageRole.USER } }
            if (withCurrentImage) {
                assertEquals(input, currentInput!!.displayContent)
                assertEquals(listOf(image!!.absolutePath), currentInput.images)
                assertTrue(body.contains("image_url"))
            }
            originals.zip(after).forEachIndexed { n, (before, saved) ->
                if (!regenerate || n != originals.lastIndex) assertEquals("Persisted source/version must remain exact", before, saved)
            }
            wireFile.writeText(buildJsonObject {
                put("stage", "COLD_SOURCE_VERIFIED"); put("request", json); put("originals", Json.encodeToJsonElement(originals)); put("coldAfter", Json.encodeToJsonElement(after)); put("currentInput", Json.encodeToJsonElement(currentInput))
            }.toString())
        } finally {
            rule.runOnIdle { store.clear() }
            runBlocking {
                app.settingsRepository.saveAppSettings(settings)
                app.voiceMessageRepository.deleteForSession(session.id)
                app.chatRepository.deleteMessagesForSession(session.id); app.chatRepository.deleteSessionRecord(session.id)
                app.modelRepository.deleteModel(model.id)
            }
            image?.delete()
            server.shutdown()
        }
    }
}
