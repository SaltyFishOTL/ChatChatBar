package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.prompt.AiTaskContext
import com.example.chatbar.domain.prompt.AiTaskFailureKind
import com.example.chatbar.domain.prompt.AiTaskKind
import com.example.chatbar.domain.prompt.AiTaskRefusalException
import com.example.chatbar.utils.DebugLogManager
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.*
import org.junit.Test

class AuxiliaryTaskTransportTest {
    private val messages = listOf(
        ChatApiMessage.text("system", "fixture-system"),
        ChatApiMessage.text("user", """{"source":"fixture"}""")
    )

    @Test
    fun streamSendsOneConfirmedRequestAndKeepsUsageAfterFinish() = runBlocking {
        val body = listOf(
            """{"choices":[{"delta":{"content":"{}"},"finish_reason":"stop"}]}""",
            """{"choices":[],"usage":{"prompt_tokens":17,"completion_tokens":2,"prompt_tokens_details":{"cached_tokens":3}}}""",
            "[DONE]"
        ).joinToString("") { "data: $it\n\n" }
        TaskResponseServer(body, "text/event-stream").use { server ->
            val context = AiTaskContext(AiTaskKind.WORLD_BOOK_CREATE)
            val events = withTimeout(5_000) {
                service().streamText(messages, model(server), taskContext = context).toList()
            }
            assertEquals(1, events.count { it == StreamEvent.Done })
            assertFalse(events.any { it is StreamEvent.Error })
            assertEquals(2, events.filterIsInstance<StreamEvent.Usage>().single().usage.completionTokens)
            val request = Json.parseToJsonElement(server.requestBody).jsonObject["messages"]!!.jsonArray
            assertEquals(listOf("system", "assistant", "user", "assistant", "user", "assistant", "assistant"), request.map { it.jsonObject["role"]!!.jsonPrimitive.content })
            assertEquals(messages.last().content, request[4].jsonObject["content"])
            val log = DebugLogManager.logs.value.single { it.taskId == context.taskId }
            assertEquals(17, log.apiPromptTokens)
            assertEquals(2, log.apiCompletionTokens)
            assertEquals("stop", log.finishReason)
        }
    }

    @Test
    fun allAuxiliaryInterfacesPreserveRefusalTypeAndStopBeforeRepair() = runBlocking {
        val payload = """{"choices":[{"delta":{"refusal":"blocked"},"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"completion_tokens":1}}"""
        TaskResponseServer("data: $payload\n\ndata: [DONE]\n\n", "text/event-stream").use { server ->
            val events = withTimeout(5_000) {
                service().streamText(messages, model(server), taskContext = AiTaskContext(AiTaskKind.FORMAT_CARD)).toList()
            }
            assertFalse(events.contains(StreamEvent.Done))
            val failure = events.filterIsInstance<StreamEvent.Error>().single()
            assertEquals(AiTaskFailureKind.REFUSAL, failure.failureKind)
            assertTrue(failure.asException() is AiTaskRefusalException)
        }
        TaskResponseServer("data: $payload\n\ndata: [DONE]\n\n", "text/event-stream").use { server ->
            val error = runCatching {
                withTimeout(5_000) {
                    service().completeTextStreaming(messages, model(server), taskContext = AiTaskContext(AiTaskKind.CHARACTER_BRIEF))
                }
            }.exceptionOrNull()
            assertTrue(error is AiTaskRefusalException)
        }
        val nonStream = """{"choices":[{"message":{"content":null,"refusal":"blocked"},"finish_reason":"content_filter"}]}"""
        TaskResponseServer(nonStream, "application/json").use { server ->
            val error = runCatching {
                withTimeout(5_000) {
                    service().completeText(messages, model(server), taskContext = AiTaskContext(AiTaskKind.MEMORY_HEAD))
                }
            }.exceptionOrNull()
            assertEquals(AiTaskFailureKind.CONTENT_FILTER, (error as AiTaskRefusalException).kind)
        }
    }

    @Test
    fun nonStreamingArrayContentPreservesProtocolText() = runBlocking {
        val response = """{"choices":[{"message":{"content":[{"type":"text","text":"[[CHATBAR_FORMAT_OK]]"}]},"finish_reason":"stop"}]}"""
        TaskResponseServer(response, "application/json").use { server ->
            val text = withTimeout(5_000) {
                service().completeText(messages, model(server), taskContext = AiTaskContext(AiTaskKind.FORMAT_REPAIR))
            }
            assertEquals("[[CHATBAR_FORMAT_OK]]", text)
        }
    }

    private fun service() = StreamingChatService { true }
    private fun model(server: TaskResponseServer) = ModelConfig(
        id = "fixture", displayName = "fixture", modelName = "fixture",
        baseUrl = server.baseUrl, apiKey = "", createdAt = 0L
    )
}

internal class TaskResponseServer(body: String, contentType: String) : AutoCloseable {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val baseUrl = "http://127.0.0.1:${server.localPort}/v1"
    @Volatile var requestBody = ""
    private val worker = thread(isDaemon = true, name = "auxiliary-task-fixture") {
        server.accept().use { socket ->
            val input = socket.getInputStream().buffered()
            val headers = StringBuilder()
            while (!headers.endsWith("\r\n\r\n")) {
                val byte = input.read()
                if (byte < 0) error("Incomplete fixture request")
                headers.append(byte.toChar())
            }
            val length = headers.lines().first { it.startsWith("Content-Length:", true) }
                .substringAfter(':').trim().toInt()
            val requestBytes = ByteArray(length)
            var read = 0
            while (read < length) {
                val count = input.read(requestBytes, read, length - read)
                if (count < 0) error("Incomplete fixture body")
                read += count
            }
            requestBody = requestBytes.toString(Charsets.UTF_8)
            val bytes = body.toByteArray(Charsets.UTF_8)
            socket.getOutputStream().apply {
                write("HTTP/1.1 200 OK\r\nContent-Type: $contentType\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                write(bytes)
                flush()
            }
        }
    }
    override fun close() {
        server.close()
        worker.join(1_000)
    }
}
