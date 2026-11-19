package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ModelConfig
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingChatServiceTerminalTest {
    @Test
    fun `provider errors do not expose the current Authorization key`() = runBlocking {
        val key = "synthetic-reflected-key"
        val responseBody = """{"error":{"message":"Invalid Authorization: Bearer $key; check access"}}"""
        val messages = listOf(ChatApiMessage.text("user", "hello"))
        for (transport in listOf("chat", "text", "nonstream")) {
            SseTestServer(emptyList(), "401 Unauthorized", responseBody).use { server ->
                val model = model(server.baseUrl).copy(apiKey = key, llmAccountId = "test-account")
                val error = withTimeout(5_000) {
                    when (transport) {
                        "chat" -> service().streamChat("error", messages, model).toList()
                            .filterIsInstance<StreamEvent.Error>().single().message
                        "text" -> service().streamText(messages, model).toList()
                            .filterIsInstance<StreamEvent.Error>().single().message
                        else -> runCatching { service().completeText(messages, model) }.exceptionOrNull()?.message.orEmpty()
                    }
                }
                assertTrue(error.contains("401"))
                assertTrue(error.contains("<redacted>"))
                assertTrue(error.contains("check access"))
                assertFalse(error.contains(key))
                assertEquals("Bearer $key", server.authorization)
            }
        }
    }

    @Test
    fun `an explicit account refuses a same origin redirect to another route`() = runBlocking {
        SseTestServer(emptyList(), "307 Temporary Redirect", "", "/other/v1/chat/completions").use { server ->
            val model = model(server.baseUrl).copy(apiKey = "synthetic-route-key", llmAccountId = "test-account")
            val error = withTimeout(5_000) {
                runCatching {
                    service().completeText(listOf(ChatApiMessage.text("user", "hello")), model)
                }.exceptionOrNull() as ModelRequestException
            }
            assertEquals(307, error.httpStatus)
            assertEquals("Bearer synthetic-route-key", server.authorization)
        }
    }

    @Test
    fun `completion evidence preserves truncation refusal and missing reason`() = runBlocking {
        listOf(
            """{"choices":[{"delta":{"content":"partial"},"finish_reason":"length"}]}""" to ChatReplyCompletion("length"),
            """{"choices":[{"delta":{"content":"refusal","refusal":"blocked"},"finish_reason":"stop"}]}""" to ChatReplyCompletion("stop", refused = true),
            """{"choices":[{"delta":{"content":"text"},"finish_reason":"content_filter"}]}""" to ChatReplyCompletion("content_filter", refused = true),
            """{"choices":[{"delta":{"content":"text"}}]}""" to ChatReplyCompletion()
        ).forEach { (payload, expected) ->
            SseTestServer(listOf(payload, "[DONE]")).use { server ->
                var completion: ChatReplyCompletion? = null
                withTimeout(5_000) {
                    service().streamChat(
                        sessionId = "auto-image-completion",
                        messages = listOf(ChatApiMessage.text("user", "hello")),
                        modelConfig = model(server.baseUrl),
                        maxTokens = 1_000,
                        onReplyCompletion = { completion = it }
                    ).collect { event ->
                        if (event is StreamEvent.Done) assertEquals(expected, completion)
                    }
                }
                assertEquals(expected, completion)
            }
        }
    }

    @Test
    fun `finish reason completes chat stream without done sentinel`() = runBlocking {
        val payloads = listOf(
            """{"choices":[{"delta":{"content":"完成"},"finish_reason":"stop"}]}"""
        )

        SseTestServer(payloads).use { server ->
            val events = withTimeout(5_000) {
                service().streamChat(
                    sessionId = "finish-reason-test",
                    messages = listOf(ChatApiMessage.text("user", "hello")),
                    modelConfig = model(server.baseUrl),
                    maxTokens = 1_000
                ).toList()
            }

            assertEquals("完成", events.filterIsInstance<StreamEvent.Delta>().joinToString("") { it.text })
            assertEquals(1, events.count { it is StreamEvent.Done })
            assertFalse(events.any { it is StreamEvent.Error })
        }
    }

    @Test
    fun `usage after finish reason is delivered before done sentinel`() = runBlocking {
        val payloads = listOf(
            """{"choices":[{"delta":{"content":"完成"},"finish_reason":"stop"}]}""",
            """{"choices":[],"usage":{"prompt_tokens":10,"prompt_tokens_details":{"cached_tokens":4}}}""",
            "[DONE]"
        )

        SseTestServer(payloads).use { server ->
            val events = withTimeout(5_000) {
                service().streamChat(
                    sessionId = "usage-after-finish-test",
                    messages = listOf(ChatApiMessage.text("user", "hello")),
                    modelConfig = model(server.baseUrl),
                    maxTokens = 1_000
                ).toList()
            }

            val usage = events.filterIsInstance<StreamEvent.Usage>().single().usage
            assertEquals(10, usage.promptTokens)
            assertEquals(4, usage.cachedTokens)
            assertTrue(events.indexOfFirst { it is StreamEvent.Usage } < events.indexOfFirst { it is StreamEvent.Done })
            assertEquals(1, events.count { it is StreamEvent.Done })
        }
    }

    @Test
    fun `nullable PPIO stream objects do not abort partial output`() = runBlocking {
        val payloads = listOf(
            """{"choices":[{"delta":{"content":"完整回复"},"finish_reason":null}],"usage":null}""",
            """{"choices":[null],"usage":null}""",
            """{"choices":[{"delta":null,"finish_reason":"stop"}],"usage":{"prompt_tokens":10,"prompt_tokens_details":null}}""",
            "[DONE]"
        )

        SseTestServer(payloads).use { server ->
            val events = withTimeout(5_000) {
                service().streamChat(
                    sessionId = "ppio-nullable-chunk-test",
                    messages = listOf(ChatApiMessage.text("user", "hello")),
                    modelConfig = model(server.baseUrl),
                    maxTokens = 1_000
                ).toList()
            }

            assertEquals("完整回复", events.filterIsInstance<StreamEvent.Delta>().joinToString("") { it.text })
            assertEquals(10, events.filterIsInstance<StreamEvent.Usage>().single().usage.promptTokens)
            assertEquals(1, events.count { it is StreamEvent.Done })
            assertFalse(events.any { it is StreamEvent.Error })
        }
    }

    @Test
    fun `terminal event and every queued delta survive a fast stream`() = runBlocking {
        val expected = (0 until 160).joinToString(separator = "") { "$it," }
        val payloads = (0 until 160).map { index ->
            """{"choices":[{"delta":{"content":"$index,"},"finish_reason":null}]}"""
        } + "[DONE]"
        val events = mutableListOf<StreamEvent>()

        SseTestServer(payloads).use { server ->
            withTimeout(10_000) {
                service().streamChat(
                    sessionId = "buffer-test",
                    messages = listOf(ChatApiMessage.text("user", "hello")),
                    modelConfig = model(server.baseUrl),
                    maxTokens = 1_000
                ).collect { event ->
                    events += event
                    delay(2)
                }
            }
        }

        assertEquals(expected, events.filterIsInstance<StreamEvent.Delta>().joinToString("") { it.text })
        assertEquals(1, events.count { it is StreamEvent.Done })
        assertFalse(events.any { it is StreamEvent.Error })
    }

    @Test
    fun `connection close without terminal signal is explicit error`() = runBlocking {
        val payloads = listOf(
            """{"choices":[{"delta":{"content":"部分"},"finish_reason":null}]}"""
        )

        SseTestServer(payloads).use { server ->
            val events = withTimeout(5_000) {
                service().streamChat(
                    sessionId = "abnormal-close-test",
                    messages = listOf(ChatApiMessage.text("user", "hello")),
                    modelConfig = model(server.baseUrl),
                    maxTokens = 1_000
                ).toList()
            }

            assertEquals("部分", events.filterIsInstance<StreamEvent.Delta>().joinToString("") { it.text })
            assertFalse(events.any { it is StreamEvent.Done })
            assertTrue(
                events.filterIsInstance<StreamEvent.Error>().single().message
                    .contains("未收到 finish_reason 或 [DONE]")
            )
        }
    }

    private fun service() = StreamingChatService(allowCleartextHttp = { true })

    private fun model(baseUrl: String) = ModelConfig(
        id = "test-model",
        displayName = "Test Model",
        baseUrl = baseUrl,
        apiKey = "",
        modelName = "test-model",
        createdAt = 0L
    )
}

private class SseTestServer(
    payloads: List<String>,
    private val status: String = "200 OK",
    errorBody: String? = null,
    location: String? = null
) : AutoCloseable {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val baseUrl: String = "http://127.0.0.1:${server.localPort}/v1"
    @Volatile var authorization: String? = null
        private set
    private val worker = thread(name = "sse-test-server", isDaemon = true) {
        server.accept().use { socket ->
            val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
            while (true) {
                val line = reader.readLine()?.takeIf { it.isNotEmpty() } ?: break
                if (line.startsWith("Authorization:", ignoreCase = true)) authorization = line.substringAfter(':').trim()
            }

            val body = (errorBody ?: payloads.joinToString(separator = "") { "data: $it\n\n" })
                .toByteArray(Charsets.UTF_8)
            val headers = buildString {
                append("HTTP/1.1 $status\r\n")
                append("Content-Type: ${if (errorBody == null) "text/event-stream" else "application/json"}\r\n")
                if (location != null) append("Location: $location\r\n")
                append("Content-Length: ${body.size}\r\n")
                append("Connection: close\r\n")
                append("\r\n")
            }.toByteArray(Charsets.US_ASCII)

            socket.getOutputStream().apply {
                write(headers)
                write(body)
                flush()
            }
        }
    }

    override fun close() {
        server.close()
        worker.join(1_000)
    }
}
