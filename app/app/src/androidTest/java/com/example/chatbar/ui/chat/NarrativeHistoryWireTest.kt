package com.example.chatbar.ui.chat

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import com.example.chatbar.BuildConfig
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.JsonFileStorage
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatNarrativeMode
import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.repository.ChatRepository
import com.example.chatbar.domain.prompt.PromptTemplates
import com.example.chatbar.ui.kit.CbText
import com.example.chatbar.ui.kit.ChatBarTheme
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.concurrent.thread
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test

class NarrativeHistoryWireTest {
    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    private enum class Transport {
        ORDINARY, EXPERIMENTAL_TEXT, EXPERIMENTAL_TOOL;

        val experimental: Boolean get() = this != ORDINARY
    }

    private enum class LiteralCase { OPENING, INLINE_CLOSE, LAZY_LIST_CLOSE }

    @Before fun prepareSyntheticApplication() = runBlocking {
        assumeTrue("Use the repository-supported isolated Android test build", BuildConfig.ISOLATED_ANDROID_TEST)
        val app = ChatBarApp.instance
        check(app.packageName == BuildConfig.APPLICATION_ID && app.packageName.startsWith("com.example.chatbar."))
        withTimeout(30_000) {
            app.dependenciesReady.first { it }
            app.settingsRepository.isInitialized.first { it }
        }
        rule.setContent { ChatBarTheme { CbText("Synthetic narrative wire verification") } }
    }

    @Test fun ordinaryPreviewStopAndCompletionKeepOnlyIntendedTags() = runBlocking {
        verifyTransport(Transport.ORDINARY)
    }

    @Test fun experimentalTextPreviewStopAndCompletionKeepOnlyIntendedTags() = runBlocking {
        verifyTransport(Transport.EXPERIMENTAL_TEXT)
    }

    @Test fun experimentalToolPreviewStopAndCompletionKeepOnlyIntendedTags() = runBlocking {
        verifyTransport(Transport.EXPERIMENTAL_TOOL)
    }

    @Test fun experimentalToolCleansAnEmbeddedClosingCallBeforeOrdinaryContinuation() = runBlocking {
        verifyReply(
            Transport.EXPERIMENTAL_TOOL, stop = false, continuation = "She notices the rain has stopped.",
        )
    }

    @Test fun currentUserLiteralOpenerSurvivesStreamingStopAndCompletion() = runBlocking {
        verifyReply(Transport.ORDINARY, stop = true, literal = LiteralCase.OPENING)
        verifyReply(Transport.EXPERIMENTAL_TEXT, stop = false, literal = LiteralCase.OPENING)
    }

    @Test fun exactMultilineCopyWithInlineClosingSurvivesStopAndCompletion() = runBlocking {
        verifyReply(Transport.ORDINARY, stop = true, literal = LiteralCase.INLINE_CLOSE)
        verifyReply(Transport.EXPERIMENTAL_TEXT, stop = false, literal = LiteralCase.INLINE_CLOSE)
    }

    @Test fun lazyListClosingExampleSurvivesStopAndGenuineOuterClosing() = runBlocking {
        verifyReply(Transport.ORDINARY, stop = true, literal = LiteralCase.LAZY_LIST_CLOSE)
        verifyReply(Transport.EXPERIMENTAL_TOOL, stop = false, literal = LiteralCase.LAZY_LIST_CLOSE)
    }

    private suspend fun verifyTransport(transport: Transport) {
        verifyReply(transport, stop = true)
        verifyReply(transport, stop = false)
    }

    private suspend fun verifyReply(
        transport: Transport,
        stop: Boolean,
        continuation: String? = null,
        literal: LiteralCase? = null,
    ) {
        val app = ChatBarApp.instance
        val settings = app.settingsRepository.getAppSettings()
        val card = CharacterCard.create("Synthetic narrative wire")
        val store = ViewModelStore()
        val mode = if (stop) ChatNarrativeMode.FOCUS else ChatNarrativeMode.ADVANCE
        val prior = "Previous synthetic scene."
        val prose = "She opens the door and walks into the courtyard."
        val template = PromptTemplates.narrativeModeHistoryReply("[story]", mode).prependIndent("> ")
        val framedReply = PromptTemplates.narrativeModeHistoryReply(prose, mode)
        val opener = framedReply.substringBefore('\n')
        val closing = framedReply.substringAfterLast('\n')
        val exactInlineCopy = "$opener\n例子$closing"
        val listExample = "- A literal closing-tag example:\n$closing"
        val listBody = if (stop) listExample else "$listExample\n\nThe explanation continues."
        val authorsTemplate = !stop && continuation == null && literal == null
        val input = when {
            literal == LiteralCase.OPENING -> "Please show the literal opening tag only on its own line, then explain it:\n$opener"
            literal == LiteralCase.INLINE_CLOSE -> "请逐字照抄以下文本并保留首尾标签：\n$exactInlineCopy"
            authorsTemplate -> "Use exactly this XML shape, replace [story] with new prose and keep both tags:\n$template"
            else -> "Continue the scene."
        }
        val reply = when (literal) {
            LiteralCase.OPENING -> "$opener\nThis is the opening tag."
            LiteralCase.INLINE_CLOSE -> exactInlineCopy
            LiteralCase.LAZY_LIST_CLOSE -> if (stop) "$opener\n$listBody" else PromptTemplates.narrativeModeHistoryReply(listBody, mode)
            null -> framedReply
        }
        val secondScene = "A bird flies past the entrance."
        val parts = if (continuation == null) listOf(reply) else listOf(
            reply.substringBeforeLast('\n'),
            "$secondScene\n${reply.substringAfterLast('\n')}",
            continuation,
        )
        val expected = when {
            continuation != null -> listOf(prose, secondScene, continuation).joinToString("\n")
            literal == LiteralCase.LAZY_LIST_CLOSE -> listBody
            authorsTemplate || literal != null -> reply
            else -> prose
        }
        val modelName = "Synthetic narrative wire ${transport.name}"
        HeldNarrativeReplyServer(parts, tool = transport == Transport.EXPERIMENTAL_TOOL).use { server ->
            val model = ModelConfig.create(modelName, server.baseUrl, "fixture-key", "fixture-model")
            val session = ChatSession.create(card.id, modelName).copy(
                modelId = model.id, narrativeMode = mode, longTermMemoryEnabled = false,
                automaticImageGenerationEnabled = false, audiobookModeEnabled = false,
            )
            var editor: ChatViewModel? = null
            try {
                app.modelRepository.saveModel(model)
                app.characterRepository.save(card)
                app.chatRepository.createSession(session)
                app.chatRepository.addMessage(ChatMessage.create(session.id, MessageRole.ASSISTANT, prior).copy(narrativeMode = mode))
                app.settingsRepository.saveAppSettings(settings.copy(
                    allowCleartextModelApi = true, defaultEmbeddingId = null, defaultFormatCardId = null,
                    automaticFormatCheckEnabled = false, momentsEnabled = false, disableNarrativeModes = false,
                    experimentalApiModeEnabled = transport.experimental,
                ))
                val vm = rule.runOnIdle {
                    ViewModelProvider(store, ChatViewModelFactory(session.id))[ChatViewModel::class.java]
                }.also { editor = it }
                rule.waitUntil(20_000) { vm.isModelUsable.value && vm.messages.value.any { it.displayContent == prior } }
                rule.runOnIdle { assertTrue(vm.sendMessage(input)) }
                val request = withTimeout(20_000) { server.requestBody.await() }
                val messages = sentMessages(request, transport)
                assertTrue(messages.any { it.hasMessage("assistant", PromptTemplates.narrativeModeHistoryReply(prior, mode)) })
                assertTrue(messages.any { it.hasMessage("user", input) })
                // Experimental live preview initially carries the first tool call only.
                val preview = if (continuation == null) expected else prose
                rule.waitUntil(20_000) { vm.streamingMessage.value?.displayContent == preview }
                assertFalse("The provider's terminal event must remain held during preview", server.terminalSent.get())
                assertTrue(vm.isResponding.value)

                if (stop) rule.runOnIdle { vm.cancelResponseGeneration() } else server.release()
                rule.waitUntil(20_000) { !vm.isResponding.value }
                assertEquals(!stop, server.terminalSent.get())
                val saved = ChatRepository(JsonFileStorage(app)).getMessages(session.id)
                assertEquals(listOf(prior, input, expected), saved.map(ChatMessage::displayContent))
                assertEquals(listOf(MessageRole.ASSISTANT, MessageRole.USER, MessageRole.ASSISTANT), saved.map(ChatMessage::role))
            } finally {
                try {
                    // The generating job, like memory maintenance, can outlive its ViewModel.
                    editor?.let { vm ->
                        rule.runOnIdle { vm.cancelResponseGeneration() }
                        rule.waitUntil(20_000) { !vm.isResponding.value }
                    }
                } finally {
                    server.release()
                    rule.runOnIdle { store.clear() }
                    try {
                        app.deletionCoordinator.deleteSession(session.id)
                    } finally {
                        app.settingsRepository.saveAppSettings(settings)
                        app.characterRepository.delete(card.id)
                        app.modelRepository.deleteModel(model.id)
                    }
                }
            }
        }
    }

    private fun sentMessages(request: String, transport: Transport): JsonArray {
        val payload = Json.parseToJsonElement(request).jsonObject
        if (!transport.experimental) return payload.getValue("messages").jsonArray
        val tools = payload.getValue("tools").jsonArray.map { it.jsonObject.getValue("function").jsonObject }
        assertTrue(tools.any { it.getValue("name").jsonPrimitive.content == "game_content" })
        val context = tools.single { it.getValue("name").jsonPrimitive.content == "context" }
            .getValue("description").jsonPrimitive.content
        // Experimental transport intentionally leaves its last assistant string open.
        return Json.parseToJsonElement(context + "\"}]").jsonArray
    }

    private fun JsonElement.hasMessage(role: String, content: String): Boolean =
        jsonObject["role"]?.jsonPrimitive?.content == role &&
            (jsonObject["content"] as? JsonPrimitive)?.content == content

    private class HeldNarrativeReplyServer(parts: List<String>, tool: Boolean) : AutoCloseable {
        private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
        private val terminal = CountDownLatch(1)
        val baseUrl = "http://127.0.0.1:${server.localPort}/v1"
        val requestBody = CompletableDeferred<String>()
        val terminalSent = AtomicBoolean(false)
        private val worker = thread(name = "synthetic-narrative-wire", isDaemon = true) {
            runCatching {
                server.accept().use { socket ->
                    // Read a byte at a time through a one-byte charset so UTF-8 JSON length stays exact.
                    val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    val headers = generateSequence(input::readLine).takeWhile(String::isNotEmpty).toList()
                    val length = headers.single { it.substringBefore(':').equals("Content-Length", ignoreCase = true) }
                        .substringAfter(':').trim().toInt()
                    val bytes = ByteArray(length) { input.read().also { check(it >= 0) }.toByte() }
                    requestBody.complete(bytes.toString(Charsets.UTF_8))
                    val first = "data: ${delta(parts, tool)}\n\n".toByteArray(Charsets.UTF_8)
                    val reason = if (tool) "tool_calls" else "stop"
                    val last = ("data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"$reason\"}]}\n\n" +
                        "data: [DONE]\n\n").toByteArray(Charsets.UTF_8)
                    val response = "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n" +
                        "Content-Length: ${first.size + last.size}\r\nConnection: close\r\n\r\n"
                    socket.getOutputStream().apply {
                        write(response.toByteArray(Charsets.US_ASCII))
                        write(first)
                        flush()
                        if (terminal.await(60, TimeUnit.SECONDS)) {
                            terminalSent.set(true)
                            write(last)
                            flush()
                        }
                    }
                }
            }.onFailure { requestBody.completeExceptionally(it) }
        }

        fun release() = terminal.countDown()

        override fun close() {
            release()
            server.close()
            worker.join(1_000)
        }

        private fun delta(parts: List<String>, tool: Boolean): String {
            val frame = if (tool) {
                buildJsonObject {
                    put("tool_calls", buildJsonArray {
                        parts.forEachIndexed { index, part ->
                            add(buildJsonObject {
                                put("index", index)
                                put("id", "fixture-$index")
                                put("type", "function")
                                put("function", buildJsonObject {
                                    put("name", "game_content")
                                    put("arguments", buildJsonObject { put("content", part) }.toString())
                                })
                            })
                        }
                    })
                }.toString()
            } else {
                buildJsonObject { put("content", parts.single()) }.toString()
            }
            return """{"choices":[{"delta":$frame}]}"""
        }
    }
}
