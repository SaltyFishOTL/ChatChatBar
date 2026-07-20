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
    fun `finish reason completes chat stream without done sentinel`() = runBlocking {
        val payloads = listOf(
            """{"choices":[{"delta":{"content":"完成"},"finish_reason":"stop"}]}"""
        )

        SseTestServer(payloads).use { server ->
            val events = withTimeout(5_000) {
                service().streamChat(
                    sessionId = "finish-reason-test",
                    messages = listOf(ChatApiMessage.text("user", "hello")),
                    modelConfig = model(server.baseUrl)
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
                    modelConfig = model(server.baseUrl)
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
                    modelConfig = model(server.baseUrl)
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
                    modelConfig = model(server.baseUrl)
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
    payloads: List<String>
) : AutoCloseable {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val baseUrl: String = "http://127.0.0.1:${server.localPort}/v1"
    private val worker = thread(name = "sse-test-server", isDaemon = true) {
        server.accept().use { socket ->
            val reader = socket.getInputStream().bufferedReader(Charsets.UTF_8)
            while (!reader.readLine().isNullOrEmpty()) {
                // Consume request headers before writing the SSE response.
            }

            val body = payloads.joinToString(separator = "") { "data: $it\n\n" }
                .toByteArray(Charsets.UTF_8)
            val headers = buildString {
                append("HTTP/1.1 200 OK\r\n")
                append("Content-Type: text/event-stream\r\n")
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
