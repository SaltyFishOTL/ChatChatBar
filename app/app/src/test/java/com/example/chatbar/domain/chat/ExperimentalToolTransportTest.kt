package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatNarrativeMode
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.prompt.AiTaskContext
import com.example.chatbar.domain.prompt.AiTaskKind
import java.net.InetAddress
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperimentalToolTransportTest {
    private val messages = listOf(ChatApiMessage.text("system", "系统原文"), ChatApiMessage.text("user", "输入原文"))

    @Test
    fun `main ordinary text is visible before server sends completion and is committed once`() = runBlocking {
        GatedExperimentalResponse(
            sse(listOf("""{"choices":[{"delta":{"content":"首段正文"}}]}""")),
            sse(listOf("""{"choices":[{"delta":{"content":"后段"},"finish_reason":"stop"}]}""", "[DONE]"))
        ).use { server ->
            val events = withTimeout(5_000) {
                service().streamChat("ordinary-preview", messages, model(server.baseUrl)).onEach { event ->
                    if (event is StreamEvent.ContentPreview && event.text == "首段正文") {
                        assertFalse(server.tailSent)
                        server.releaseTail()
                    }
                }.toList()
            }
            assertEquals(listOf("首段正文", "首段正文后段"), events.filterIsInstance<StreamEvent.ContentPreview>().map { it.text })
            assertEquals("首段正文后段", events.filterIsInstance<StreamEvent.Delta>().joinToString("") { it.text })
            assertEquals(1, events.count { it is StreamEvent.Done })
            assertFalse(events.any { it is StreamEvent.Error })
        }
    }

    @Test
    fun `auxiliary ordinary preview advances while server waits and final progress is not duplicated`() = runBlocking {
        GatedExperimentalResponse(
            sse(listOf("""{"choices":[{"delta":{"reasoning_content":"思考","content":"首段正文"}}]}""")),
            sse(listOf("""{"choices":[{"delta":{"content":"后段"},"finish_reason":"stop"}]}""", "[DONE]"))
        ).use { server ->
            val progress = AiStreamProgress()
            val events = withTimeout(5_000) {
                val observer = launch {
                    progress.snapshots.first { it.lastOrNull()?.content == "首段正文" }
                    assertFalse(server.tailSent)
                    server.releaseTail()
                }
                try {
                    withContext(progress) { service().streamText(messages, model(server.baseUrl)).toList() }
                } finally {
                    observer.cancel()
                }
            }
            assertEquals("首段正文后段", events.filterIsInstance<StreamEvent.Delta>().joinToString("") { it.text })
            assertEquals("思考", events.filterIsInstance<StreamEvent.ReasoningDelta>().single().text)
            assertEquals("首段正文后段", progress.snapshots.value.single().content)
            assertFalse(progress.snapshots.value.single().active)
            assertEquals(1, events.count { it is StreamEvent.Done })
            assertFalse(events.any { it is StreamEvent.Error })
        }
    }

    @Test
    fun `ordinary preamble preview clears when tool output starts and never enters canonical text`() = runBlocking {
        val body = sse(listOf(
            """{"choices":[{"delta":{"content":"前导说明"}}]}""",
            chunk("""{"metadata":{},"content":"正文""", "game_content"),
            chunk("后段\"}"), chunk(reason = "tool_calls"), "[DONE]"
        ))
        listOf(false, true).forEach { auxiliary ->
            TaskResponseServer(body, "text/event-stream").use { server ->
                val progress = AiStreamProgress()
                val events = withTimeout(5_000) {
                    withContext(progress) {
                        if (auxiliary) service().streamText(messages, model(server)).toList()
                        else service().streamChat("mixed-preview", messages, model(server)).toList()
                    }
                }
                assertEquals("正文后段", events.filterIsInstance<StreamEvent.Delta>().joinToString("") { it.text })
                if (auxiliary) {
                    assertEquals("正文后段", progress.snapshots.value.single().content)
                } else {
                    assertEquals(listOf("前导说明", ""), events.filterIsInstance<StreamEvent.ContentPreview>().map { it.text })
                }
                assertFalse(events.any { it is StreamEvent.Error })
            }
        }
    }

    @Test
    fun `main and auxiliary streams decode tool arguments while preserving completion evidence`() = runBlocking {
        val eventsBody = sse(listOf(
            chunk("""{"content":"第一行\n""", "game_content"),
            chunk("""第二行"}"""),
            chunk(reason = "tool_calls"),
            """{"choices":[],"usage":{"prompt_tokens":23,"completion_tokens":9}}""",
            "[DONE]"
        ))
        listOf(false, true).forEach { auxiliary ->
            TaskResponseServer(eventsBody, "text/event-stream").use { server ->
                var completion: ChatReplyCompletion? = null
                val events = withTimeout(5_000) {
                    if (auxiliary) service().streamText(
                        messages, model(server), taskContext = AiTaskContext(AiTaskKind.MEMORY_HEAD)
                    ).toList() else service().streamChat(
                        "experimental-main", messages, model(server), onReplyCompletion = { completion = it }
                    ).toList()
                }
                assertEquals("第一行\n第二行", events.filterIsInstance<StreamEvent.Delta>().joinToString("") { it.text })
                assertEquals(1, events.count { it is StreamEvent.Done })
                assertFalse(events.any { it is StreamEvent.Error })
                assertEquals(23, events.filterIsInstance<StreamEvent.Usage>().single().usage.promptTokens)
                if (!auxiliary) {
                    assertEquals("tool_calls", completion?.finishReason)
                    assertTrue(completion?.toolReplyCompleted == true)
                }
                val request = Json.parseToJsonElement(server.requestBody).jsonObject
                assertEquals(listOf("system", "user"), request.getValue("messages").jsonArray
                    .map { it.jsonObject.getValue("role").jsonPrimitive.content })
                val description = request.getValue("tools").jsonArray.map { it.jsonObject.getValue("function").jsonObject }
                    .single { it.getValue("name").jsonPrimitive.content == "context" }.getValue("description").jsonPrimitive.content
                assertTrue(description.contains("输入原文"))
            }
        }
    }

    @Test
    fun `final tool delta carries part lengths before done`() = runBlocking {
        val firstBody = "场景一。\n<!-- </历史回复> -->\n继续。"
        val secondBody = "场景二。<!-- <历史回复 模式=\"聚焦\"> -->"
        val first = "<历史回复 模式=\"聚焦\">$firstBody</历史回复>"
        val second = "<历史回复 模式=\"推进\">$secondBody</历史回复>"
        val eventsBody = sse(listOf(
            chunk(buildJsonObject { put("content", first) }.toString(), "game_content"),
            chunk(buildJsonObject { put("content", second) }.toString(), "game_content", index = 1),
            chunk(reason = "tool_calls"),
            "[DONE]",
        ))
        TaskResponseServer(eventsBody, "text/event-stream").use { server ->
            val events = withTimeout(5_000) {
                service().streamChat("multipart-echo", messages, model(server)).toList()
            }
            val deltas = events.filterIsInstance<StreamEvent.Delta>()
            val finalDelta = deltas.last()
            assertEquals(listOf(first.length, second.length), finalDelta.toolReplyPartLengths)
            assertTrue(events.indexOf(finalDelta) < events.indexOf(StreamEvent.Done))
            val raw = deltas.joinToString("") { it.text }
            assertEquals("$firstBody\n$secondBody", NarrativeHistoryEcho.completedBody(
                raw, enabled = true, partLengths = finalDelta.toolReplyPartLengths, streaming = true,
            ))
        }
    }

    @Test
    fun `main stream removes one narrative wrapper spanning validated tool calls`() = runBlocking {
        val first = "<!-- 起始旁注 -->\n<历史回复 模式=\"聚焦\">\n甲"
        val second = "乙\n</历史回复>\n<!-- 末尾旁注 -->"
        val expected = "<!-- 起始旁注 -->\n甲\n乙\n<!-- 末尾旁注 -->"
        val sseBody = sse(listOf(
            chunk(buildJsonObject { put("content", first) }.toString(), "game_content"),
            chunk(buildJsonObject { put("content", second) }.toString(), "game_content", index = 1),
            chunk(reason = "tool_calls"),
            "[DONE]",
        ))
        TaskResponseServer(sseBody, "text/event-stream").use { server ->
            var completion: ChatReplyCompletion? = null
            val events = withTimeout(5_000) {
                service().streamChat(
                    "cross-call-echo", messages, model(server),
                    onReplyCompletion = { completion = it },
                ).toList()
            }
            val deltas = events.filterIsInstance<StreamEvent.Delta>()
            val raw = deltas.joinToString("") { it.text }
            val lengths = deltas.last().toolReplyPartLengths
            assertEquals("$first\n$second", raw)
            assertEquals(listOf(first.length, second.length), lengths)
            assertEquals(lengths, completion?.toolReplyPartLengths)
            assertEquals(StreamEvent.Done, events.last())
            assertEquals(expected, NarrativeHistoryEcho.completedBody(raw, true, partLengths = lengths, streaming = true))
            assertEquals(expected, NarrativeHistoryEcho.completedBody(raw, true, interrupted = true, partLengths = lengths))
            assertEquals(expected, NarrativeHistoryEcho.completedBody(raw, true, partLengths = lengths))
        }
    }

    @Test
    fun `multipart main stream keeps wrapper syntax inside cross call code comments and quotes`() = runBlocking {
        val literal = "<历史回复 模式=\"聚焦\">\n字面示例\n</历史回复>"
        for ((before, after) in listOf(
            "~~~xml" to "~~~",
            "<!--\n内部说明" to "-->\n可见正文",
            "她说：“这段是示例：" to "这段也是引文的一部分。”",
            "> 示例原文如下：" to "这一行也属于同一个引文。",
        )) {
            val parts = listOf(before, literal, after)
            val expected = parts.joinToString("\n")
            val sseBody = sse(parts.mapIndexed { index, part ->
                chunk(buildJsonObject { put("content", part) }.toString(), "game_content", index = index)
            } + listOf(chunk(reason = "tool_calls"), "[DONE]"))
            TaskResponseServer(sseBody, "text/event-stream").use { server ->
                var completion: ChatReplyCompletion? = null
                val events = withTimeout(5_000) {
                    service().streamChat("cross-call-literals", messages, model(server),
                        onReplyCompletion = { completion = it }).toList()
                }
                val deltas = events.filterIsInstance<StreamEvent.Delta>()
                val raw = deltas.joinToString("") { it.text }
                val lengths = deltas.last().toolReplyPartLengths
                assertEquals(expected, raw)
                assertEquals(parts.map(String::length), lengths)
                assertEquals(lengths, completion?.toolReplyPartLengths)
                assertEquals(expected, NarrativeHistoryEcho.completedBody(raw, true, partLengths = lengths, streaming = true))
                assertEquals(expected, NarrativeHistoryEcho.completedBody(raw, true, interrupted = true, partLengths = lengths))
                assertEquals(expected, NarrativeHistoryEcho.completedBody(raw, true, partLengths = lengths))
            }
        }
    }

    @Test
    fun `main stream respects a same line format card template in preview stop and done`() = runBlocking {
        val raw = "<历史回复 模式=\"推进\">她推门。</历史回复>"
        assertNarrativeReplyStates(
            raw,
            formatCard = "本轮输出模板：<历史回复 模式=\"推进\">{正文}</历史回复>",
            expected = raw,
        )
    }

    @Test
    fun `main stream closes a quoted title before a later contraction`() = runBlocking {
        val body = "She titled it ‘Visitors’ because they’re leaving."
        assertNarrativeReplyStates("<历史回复 模式=\"聚焦\">\n$body\n</历史回复>", expected = body)
    }

    @Test
    fun `main stream respects a balanced nested format card template`() = runBlocking {
        val formatCard = "本轮输出模板：<历史回复 模式=\"推进\"><历史回复 模式=\"聚焦\">{正文}</历史回复></历史回复>"
        val raw = "<历史回复 模式=\"推进\"><历史回复 模式=\"聚焦\">她推门。</历史回复></历史回复>"
        assertNarrativeReplyStates(raw, expected = raw, formatCard = formatCard)
    }

    @Test
    fun `main stream ignores a trailing comment apostrophe when matching the outer close`() = runBlocking {
        val body = "她说‘Yes’ and left."
        val note = "<!-- customers’ notes -->"
        assertNarrativeReplyStates(
            "<历史回复 模式=\"推进\">\n$body\n</历史回复>\n$note",
            expected = "$body\n$note",
        )
    }

    @Test
    fun `main stream ignores a bare format example followed by same line prose`() = runBlocking {
        val raw = "<历史回复 模式=\"推进\">她推门。</历史回复>"
        val card = "$raw 只是历史标记示例，请勿使用。"
        assertNarrativeReplyStates(raw, expected = "她推门。", formatCard = card)
    }

    @Test
    fun `main stream reserves only the declared format opener`() = runBlocking {
        val card = "本轮输出模板：<历史回复 模式=\"推进\">{正文}</历史回复>"
        val declared = "<历史回复 模式=\"推进\">推进正文。</历史回复>"
        assertNarrativeReplyStates(declared, expected = declared, formatCard = card)
        assertNarrativeReplyStates("<历史回复 模式=\"聚焦\">聚焦正文。</历史回复>", expected = "聚焦正文。", formatCard = card)
        assertNarrativeReplyStates("<聚焦模式=\"沉浸\">旧版正文。</聚焦模式>", expected = "旧版正文。", formatCard = card)
    }

    @Test
    fun `main stream keeps a literal closing tag in an ordered list item`() = runBlocking {
        val body = "1. Explain this closing tag:\n   </历史回复>\n   It is a literal example inside this list item."
        val raw = "<历史回复 模式=\"聚焦\">\n$body\n</历史回复>"
        assertNarrativeReplyStates(raw, expected = body)
    }

    @Test
    fun `main stream keeps a standalone lazy quote tag before the terminal outer close`() = runBlocking {
        val close = "</历史回复>"
        val body = "> 下面这行是要展示的闭合标记。\n$close\n\n讲完代码后，他们走向码头。"
        assertNarrativeReplyStates("<历史回复 模式=\"聚焦\">\n$body\n$close", expected = body)
    }

    @Test
    fun `main stream reserves an explicit format after an inline editor comment`() = runBlocking {
        val raw = "<历史回复 模式=\"聚焦\">正文</历史回复>"
        val card = "输出格式：<!-- 编辑注记 -->$raw"
        assertNarrativeReplyStates(raw, expected = raw, formatCard = card)
    }

    @Test
    fun `main stream reserves only an explicitly affirmed format with same line prose`() = runBlocking {
        val raw = "<历史回复 模式=\"聚焦\">正文</历史回复>"
        val affirmation = "（标签是固定输出的一部分）"
        assertNarrativeReplyStates(raw, expected = raw, formatCard = "输出格式：$raw$affirmation")
        assertNarrativeReplyStates(raw, expected = "正文", formatCard = "$raw$affirmation")
        assertNarrativeReplyStates(raw, expected = "正文", formatCard = "输出格式：$raw（仅为示例，请勿输出）")
    }

    private suspend fun assertNarrativeReplyStates(raw: String, expected: String, formatCard: String? = null) {
        val cleanup = NarrativeHistoryEcho.forRequest(ChatNarrativeMode.ADVANCE, formatCard)
        val delta = buildJsonObject {
            put("choices", buildJsonArray {
                add(buildJsonObject { put("delta", buildJsonObject { put("content", raw) }) })
            })
        }
        GatedExperimentalResponse(
            sse(listOf(delta.toString())),
            sse(listOf("""{"choices":[{"delta":{},"finish_reason":"stop"}]}""", "[DONE]")),
        ).use { server ->
            val events = withTimeout(5_000) {
                service().streamChat("narrative-reply-states", messages, model(server.baseUrl)).onEach { event ->
                    if (event is StreamEvent.ContentPreview) {
                        assertFalse(server.tailSent)
                        server.releaseTail()
                    }
                }.toList()
            }
            val preview = events.filterIsInstance<StreamEvent.ContentPreview>().single().text
            assertEquals(raw, preview)
            assertEquals(StreamEvent.Done, events.last())
            val completed = events.filterIsInstance<StreamEvent.Delta>().joinToString("") { it.text }
            assertEquals(raw, completed)
            assertEquals("Preview", expected, cleanup.completedBody(preview, streaming = true))
            assertEquals("Stop", expected, cleanup.completedBody(preview, interrupted = true))
            assertEquals("Done", expected, cleanup.completedBody(completed))
        }
    }

    @Test
    fun `non streaming auxiliary response extracts JSON task payload from tool arguments`() = runBlocking {
        val expected = """{"result":"正确值"}"""
        val response = buildJsonObject {
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("finish_reason", "tool_calls")
                    put("message", buildJsonObject {
                        put("tool_calls", buildJsonArray {
                            add(buildJsonObject {
                                put("type", "function")
                                put("function", buildJsonObject {
                                    put("name", "game_content")
                                    put("arguments", buildJsonObject { put("content", expected) }.toString())
                                })
                            })
                        })
                    })
                })
            })
        }.toString()
        TaskResponseServer(response, "application/json").use { server ->
            assertEquals(expected, withTimeout(5_000) {
                service().completeText(messages, model(server), taskContext = AiTaskContext(AiTaskKind.WORLD_BOOK_CREATE))
            })
        }
    }

    @Test
    fun `invalid tool arguments do not turn into a successful stream`() = runBlocking {
        val body = sse(listOf(chunk("""{"content":"部分正文""", "game_content"), chunk(reason = "tool_calls"), "[DONE]"))
        TaskResponseServer(body, "text/event-stream").use { server ->
            val events = withTimeout(5_000) { service().streamText(messages, model(server)).toList() }
            assertEquals("部分正文", events.filterIsInstance<StreamEvent.Delta>().joinToString("") { it.text })
            assertFalse(events.any { it is StreamEvent.Done })
            assertNotNull(events.filterIsInstance<StreamEvent.Error>().single())
        }
    }

    @Test
    fun `truncation remains truncation instead of JSON parse failure`() = runBlocking {
        val body = sse(listOf(chunk("""{"content":"部分正文""", "game_content"), chunk(reason = "length"), "[DONE]"))
        TaskResponseServer(body, "text/event-stream").use { server ->
            val events = withTimeout(5_000) { service().streamText(messages, model(server)).toList() }
            assertTrue(events.filterIsInstance<StreamEvent.Error>().single().asException() is ModelResponseTruncatedException)
            assertFalse(events.any { it is StreamEvent.Done })
        }
    }

    @Test
    fun `request mode stays fixed when global setting changes during a reply`() = runBlocking {
        val modeReads = java.util.concurrent.atomic.AtomicInteger()
        val service = StreamingChatService(
            experimentalApiModeEnabled = { modeReads.getAndIncrement() == 0 }, allowCleartextHttp = { true }
        )
        val body = sse(listOf(chunk("""{"content":"完整正文"}""", "game_content"), chunk(reason = "tool_calls"), "[DONE]"))
        TaskResponseServer(body, "text/event-stream").use { server ->
            val events = withTimeout(5_000) {
                service.streamChat("mode-snapshot", messages, model(server)).toList()
            }
            assertEquals("完整正文", events.filterIsInstance<StreamEvent.Delta>().joinToString("") { it.text })
            assertFalse(events.any { it is StreamEvent.Error })
            assertEquals(1, modeReads.get())
        }
    }

    private fun service() = StreamingChatService(experimentalApiModeEnabled = { true }, allowCleartextHttp = { true })

    private fun model(server: TaskResponseServer) = model(server.baseUrl)

    private fun model(baseUrl: String) = ModelConfig(
        id = "fixture", displayName = "fixture", modelName = "fixture", baseUrl = baseUrl, apiKey = "", createdAt = 0L
    )

    private fun sse(chunks: List<String>) = chunks.joinToString("") { "data: $it\n\n" }

    private fun chunk(
        arguments: String? = null,
        name: String? = null,
        reason: String? = null,
        index: Int = 0,
    ): String = buildJsonObject {
        put("choices", buildJsonArray {
            add(buildJsonObject {
                reason?.let { put("finish_reason", it) }
                put("delta", buildJsonObject {
                    if (arguments != null || name != null) put("tool_calls", buildJsonArray {
                        add(buildJsonObject {
                            put("index", index)
                            put("function", buildJsonObject {
                                name?.let { put("name", it) }
                                arguments?.let { put("arguments", it) }
                            })
                        })
                    })
                })
            })
        })
    }.toString()
}

/** Completion is withheld until the client proves it has displayed the first body fragment. */
private class GatedExperimentalResponse(prefix: String, tail: String) : AutoCloseable {
    private val server = ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"))
    private val gate = CountDownLatch(1)
    val baseUrl = "http://127.0.0.1:${server.localPort}/v1"
    @Volatile var tailSent = false
        private set
    private val worker = thread(isDaemon = true, name = "experimental-preview-fixture") {
        server.accept().use { socket ->
            socket.soTimeout = 5_000
            val input = socket.getInputStream().buffered()
            val headers = StringBuilder()
            while (!headers.endsWith("\r\n\r\n")) {
                val byte = input.read()
                check(byte >= 0)
                headers.append(byte.toChar())
            }
            var remaining = headers.lines().first { it.startsWith("Content-Length:", true) }
                .substringAfter(':').trim().toInt()
            val buffer = ByteArray(4096)
            while (remaining > 0) {
                val read = input.read(buffer, 0, minOf(buffer.size, remaining))
                check(read > 0)
                remaining -= read
            }
            val prefixBytes = prefix.toByteArray(Charsets.UTF_8)
            val tailBytes = tail.toByteArray(Charsets.UTF_8)
            val output = socket.getOutputStream()
            output.write(("HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\n" +
                "Content-Length: ${prefixBytes.size + tailBytes.size}\r\nConnection: close\r\n\r\n").toByteArray())
            output.write(prefixBytes)
            output.flush()
            check(gate.await(5, TimeUnit.SECONDS)) { "Client did not display the body before completion" }
            tailSent = true
            output.write(tailBytes)
            output.flush()
        }
    }

    fun releaseTail() = gate.countDown()

    override fun close() {
        gate.countDown()
        server.close()
        worker.join(1_000)
    }
}
