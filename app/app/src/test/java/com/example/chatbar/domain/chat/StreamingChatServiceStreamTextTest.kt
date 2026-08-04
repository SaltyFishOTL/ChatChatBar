package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ModelConfig
import java.net.InetAddress
import java.net.ServerSocket
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class StreamingChatServiceStreamTextTest {
    @Test
    fun `malformed sse chunk surfaces explicit error with raw data`() = runBlocking {
        val payloads = listOf("这不是合法JSON{{{")

        StreamTextTestServer(payloads).use { server ->
            val events = withTimeout(5_000) {
                service().streamText(
                    messages = listOf(ChatApiMessage.text("user", "hello")),
                    modelConfig = model(server.baseUrl)
                ).toList()
            }

            val error = events.filterIsInstance<StreamEvent.Error>().single().message
            assertTrue(error.contains("解析 SSE 数据失败"))
            assertTrue(error.contains("这不是合法JSON"))
            assertFalse(events.any { it is StreamEvent.Done })
        }
    }

    @Test
    fun `server error object is surfaced as explicit error instead of silent empty`() = runBlocking {
        val payloads = listOf(
            """{"error":{"code":"20015","message":"余额不足"}}""",
            "[DONE]"
        )

        StreamTextTestServer(payloads).use { server ->
            val events = withTimeout(5_000) {
                service().streamText(
                    messages = listOf(ChatApiMessage.text("user", "hello")),
                    modelConfig = model(server.baseUrl)
                ).toList()
            }

            val error = events.filterIsInstance<StreamEvent.Error>().single().message
            assertTrue(error.contains("服务端返回错误"))
            assertTrue(error.contains("余额不足"))
            assertFalse(events.any { it is StreamEvent.Done })
        }
    }

    @Test
    fun `array form content parts are concatenated`() = runBlocking {
        val payloads = listOf(
            """{"choices":[{"delta":{"content":[{"type":"text","text":"你好"},{"type":"text","content":"世界"}]},"finish_reason":null}]}""",
            "[DONE]"
        )

        StreamTextTestServer(payloads).use { server ->
            val events = withTimeout(5_000) {
                service().streamText(
                    messages = listOf(ChatApiMessage.text("user", "hello")),
                    modelConfig = model(server.baseUrl)
                ).toList()
            }

            assertEquals("你好世界", events.filterIsInstance<StreamEvent.Delta>().joinToString("") { it.text })
            assertEquals(1, events.count { it is StreamEvent.Done })
            assertFalse(events.any { it is StreamEvent.Error })
        }
    }

    @Test
    fun `empty stream yields explicit empty content error instead of done`() = runBlocking {
        val payloads = listOf("[DONE]")

        StreamTextTestServer(payloads).use { server ->
            val events = withTimeout(5_000) {
                service().streamText(
                    messages = listOf(ChatApiMessage.text("user", "hello")),
                    modelConfig = model(server.baseUrl)
                ).toList()
            }

            val error = events.filterIsInstance<StreamEvent.Error>().single().message
            assertTrue(error.contains("未收到任何文本内容"))
            assertFalse(events.any { it is StreamEvent.Done })
            assertFalse(events.any { it is StreamEvent.Delta })
        }
    }

    @Test
    fun `blank finish reason does not complete stream prematurely`() = runBlocking {
        val payloads = listOf(
            """{"choices":[{"delta":{"content":"你好"},"finish_reason":""}]}""",
            """{"choices":[{"delta":{"content":"世界"},"finish_reason":null}]}""",
            "[DONE]"
        )

        StreamTextTestServer(payloads).use { server ->
            val events = withTimeout(5_000) {
                service().streamText(
                    messages = listOf(ChatApiMessage.text("user", "hello")),
                    modelConfig = model(server.baseUrl)
                ).toList()
            }

            assertEquals("你好世界", events.filterIsInstance<StreamEvent.Delta>().joinToString("") { it.text })
            assertEquals(1, events.count { it is StreamEvent.Done })
            assertFalse(events.any { it is StreamEvent.Error })
        }
    }

    @Test
    fun `reasoning only stream yields explicit empty content error`() = runBlocking {
        val payloads = listOf(
            """{"choices":[{"delta":{"reasoning_content":"思考中"},"finish_reason":null}]}""",
            """{"choices":[{"delta":{},"finish_reason":"stop"}]}"""
        )

        StreamTextTestServer(payloads).use { server ->
            val events = withTimeout(5_000) {
                service().streamText(
                    messages = listOf(ChatApiMessage.text("user", "hello")),
                    modelConfig = model(server.baseUrl)
                ).toList()
            }

            assertEquals("思考中", events.filterIsInstance<StreamEvent.ReasoningDelta>().joinToString("") { it.text })
            assertTrue(
                events.filterIsInstance<StreamEvent.Error>().single().message
                    .contains("未收到任何文本内容")
            )
            assertFalse(events.any { it is StreamEvent.Done })
            assertFalse(events.any { it is StreamEvent.Delta })
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

private class StreamTextTestServer(
    payloads: List<String>
) : AutoCloseable {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    val baseUrl: String = "http://127.0.0.1:${server.localPort}/v1"
    private val worker = thread(name = "stream-text-test-server", isDaemon = true) {
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
