package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatNarrativeMode
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.prompt.AiTaskContext
import com.example.chatbar.domain.prompt.AiTaskKind
import com.example.chatbar.domain.prompt.PromptTemplates
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
import kotlinx.serialization.json.JsonPrimitive
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
    fun `main stream cleans a split story followed by an independent tool wrapper`(): Unit = runBlocking {
        val mode = ChatNarrativeMode.FOCUS
        val parts = listOf(
            "<历史回复 模式=\"聚焦\">\n甲段。",
            "乙段。\n</历史回复>",
            PromptTemplates.narrativeModeHistoryReply("丙段。", ChatNarrativeMode.ADVANCE),
        )
        val expected = "甲段。\n乙段。\n丙段。"
        val completed = assertMultipartNarrativeReply(parts, expected)
        val previous = ChatMessage.create("multipart-next", MessageRole.ASSISTANT, completed)
        val projected = ChatHistoryPromptPolicy.renderedHistoryText(previous, completed)
        val nextMessages = listOf(messages.first(), ChatApiMessage.text("assistant", projected), messages.last())
        val response = sse(listOf("""{"choices":[{"delta":{"content":"后续正文"},"finish_reason":"stop"}]}""", "[DONE]"))
        TaskResponseServer(response, "text/event-stream").use { server ->
            val events = withTimeout(5_000) { service().streamChat("multipart-next", nextMessages, model(server)).toList() }
            assertEquals(StreamEvent.Done, events.last())
            val request = Json.parseToJsonElement(server.requestBody).jsonObject
            val contextDescription = request.getValue("tools").jsonArray.map { it.jsonObject.getValue("function").jsonObject }
                .single { it.getValue("name").jsonPrimitive.content == "context" }.getValue("description").jsonPrimitive.content
            val history = Json.parseToJsonElement(contextDescription).jsonArray.first().jsonObject
            assertEquals("assistant", history.getValue("role").jsonPrimitive.content)
            assertEquals(PromptTemplates.narrativeModeHistoryReply(expected, mode), history.getValue("content").jsonPrimitive.content)
        }
        assertMultipartNarrativeReply(
            listOf(parts.last()) + parts.dropLast(1), "丙段。\n甲段。\n乙段。",
        )
    }

    @Test
    fun `grouped tool stories preserve literal and nested tags across their own calls`(): Unit = runBlocking {
        val opening = "<历史回复 模式=\"聚焦\">"
        val close = "</历史回复>"
        val nested = PromptTemplates.narrativeModeHistoryReply("嵌入的例子。", ChatNarrativeMode.ADVANCE)
        val independent = PromptTemplates.narrativeModeHistoryReply("另一段故事。", ChatNarrativeMode.ADVANCE)
        val parts = listOf(
            "$opening\n她说：“这行是引语：",
            "$close\n引语结束。”\n~~~xml\n$close",
            "~~~\n$nested\n旁白继续。\n$close",
            independent,
        )
        val expected = "她说：“这行是引语：\n$close\n引语结束。”\n~~~xml\n$close\n~~~\n$nested\n旁白继续。\n另一段故事。"
        assertMultipartNarrativeReply(parts, expected)
    }

    @Test
    fun `tool grouping does not make visible examples or malformed closes eligible`(): Unit = runBlocking {
        val opening = "<历史回复 模式=\"聚焦\">"
        val close = "</历史回复>"
        val independent = PromptTemplates.narrativeModeHistoryReply("另一段故事。", ChatNarrativeMode.ADVANCE)
        val example = "$opening\n展示内容。\n$close\n这是一个标签示例。"
        assertMultipartNarrativeReply(listOf(example, "示例解释。", independent), "$example\n示例解释。\n另一段故事。")
        assertMultipartNarrativeReply(
            listOf("$opening\n展示内容。", "示例下一行。\n$close", "这是一个标签示例。"),
            "展示内容。\n示例下一行。\n$close\n这是一个标签示例。",
        )
        val malformed = "</历史回复 模式=\"聚焦\">"
        assertMultipartNarrativeReply(
            listOf("$opening\n第一段。", "中间正文。\n$malformed", independent),
            "第一段。\n中间正文。\n$malformed\n另一段故事。",
        )
        assertMultipartNarrativeReply(
            listOf("$opening\n格式正文第一段。", "格式正文第二段。\n$close", independent),
            "$opening\n格式正文第一段。\n格式正文第二段。\n$close\n另一段故事。",
            formatCard = "本轮输出模板：\n$opening\n正文范例\n$close",
        )
    }

    private suspend fun assertMultipartNarrativeReply(
        parts: List<String>, expected: String, formatCard: String? = null,
    ): String {
        val response = sse(parts.mapIndexed { index, part ->
            chunk(buildJsonObject { put("content", part) }.toString(), "game_content", index = index)
        } + listOf(chunk(reason = "tool_calls"), "[DONE]"))
        return TaskResponseServer(response, "text/event-stream").use { server ->
            var completion: ChatReplyCompletion? = null
            val events = withTimeout(5_000) {
                service().streamChat("grouped-tool-echo", messages, model(server),
                    onReplyCompletion = { completion = it }).toList()
            }
            val deltas = events.filterIsInstance<StreamEvent.Delta>()
            val raw = deltas.joinToString("") { it.text }
            val lengths = deltas.last().toolReplyPartLengths
            assertEquals(parts.joinToString("\n"), raw)
            assertEquals(parts.map(String::length), lengths)
            assertEquals(lengths, completion?.toolReplyPartLengths)
            assertEquals(StreamEvent.Done, events.last())
            val cleanup = NarrativeHistoryEcho.forRequest(ChatNarrativeMode.FOCUS, formatCard)
            assertEquals("Preview", expected, cleanup.completedBody(raw, partLengths = lengths, streaming = true))
            assertEquals("Stop", expected, cleanup.completedBody(raw, partLengths = lengths, interrupted = true))
            cleanup.completedBody(raw, partLengths = completion?.toolReplyPartLengths.orEmpty()).also {
                assertEquals("Done", expected, it)
            }
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
    fun `main stream respects explicit English and Chinese inline tag ownership`() = runBlocking {
        val opening = "<历史回复 模式=\"推进\">"
        val closing = "</历史回复>"
        val story = "她推开门。"
        val reply = "$opening$story$closing"
        val template = "$opening{story}$closing"
        for (label in listOf(
            "Required output template (keep both tags verbatim): ",
            "请完整保留首尾标签：",
        )) {
            assertNarrativeReplyStates(reply, expected = reply, formatCard = label + template)
        }
        for (card in listOf(
            "The following is a counterexample.\nRequired output template: $template",
            "Output template: $template\nDo not output these tags.",
            "Output template: $template is merely an example.",
            "She referred to the output template: $template",
            "请完整保留首尾标签：`$template`",
        )) {
            assertNarrativeReplyStates(reply, expected = story, formatCard = card)
        }
    }

    @Test
    fun `main stream cleans a fully identified partial close on the opening line`() = runBlocking {
        val opening = "<历史回复 模式=\"推进\">"
        val closingPrefix = "</历史回复"
        val story = "她推开门。"
        val raw = "$opening$story$closingPrefix"
        assertNarrativeReplyDeltas(listOf(raw, ">"), listOf(story, story), story)
        assertNarrativeReplyStates(raw, expected = story)

        val literal = "$opening 是本次要解释的标签。"
        assertNarrativeReplyStates(literal, expected = literal)
        val escaped = "$opening$story\\$closingPrefix"
        assertNarrativeReplyStates(escaped, expected = escaped)
    }

    @Test
    fun `main stream keeps a closing tag in a guillemet quote inside a genuine wrapper`() = runBlocking {
        val opening = "<历史回复 模式=\"推进\">"
        val closing = "</历史回复>"
        val body = "Elle lit : « Exemple\n$closing\nfin de l’exemple. »\nElle ferme le livre."
        assertNarrativeReplyStates("$opening\n$body\n$closing", expected = body)
        assertNarrativeReplyStates(body, expected = body)
        assertNarrativeReplyStates("$opening\n$body", expected = body)
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

    @Test
    fun `main stream removes the history close after the built in legacy status footer`() = runBlocking {
        val opening = "<历史回复 模式=\"聚焦\">"
        val closing = "</历史回复>"
        val label = "（三个点为状态包裹格式，必须完整输出）"
        val status = "```text$label\n生命：10\n$closing\n$label```"
        val body = "她把门推开。\n$status"
        assertNarrativeReplyStates("$opening\n$body\n$closing", expected = body)

        val code = "```kotlin\n$label```\n$closing\n```"
        assertNarrativeReplyStates("$opening\n$code\n$closing", expected = code)
    }

    @Test
    fun `main stream treats explicitly forbidden standalone format pairs as examples`() = runBlocking {
        val raw = "<历史回复 模式=\"聚焦\">\n正文\n</历史回复>"
        val lead = "以下是禁止输出的反例。"
        val tail = "不得输出上面的标签。"
        for (card in listOf("$lead\n$raw\n$tail", "$lead\n\n$raw", "$raw\n\n$tail")) {
            assertNarrativeReplyStates(raw, expected = "正文", formatCard = card)
        }
    }

    @Test
    fun `main stream preserves a fixed current output template`() = runBlocking {
        val raw = "<历史回复 模式=\"聚焦\">正文</历史回复>"
        assertNarrativeReplyStates(raw, expected = raw, formatCard = "本轮的固定输出模板：$raw")
    }

    @Test
    fun `main stream preserves an affirmed bold format label`() = runBlocking {
        val raw = "<历史回复 模式=\"聚焦\">正文</历史回复>"
        val label = "**输出格式：**"
        assertNarrativeReplyStates(raw, expected = raw, formatCard = "$label$raw（标签是固定输出的一部分）")
        assertNarrativeReplyStates(raw, expected = "正文", formatCard = "$label$raw（仅为示例，请勿输出）")
    }

    @Test
    fun `main stream keeps annotated status around the wrapped narrative`() = runBlocking {
        val label = "（三个点为状态包裹格式，必须完整输出）"
        val before = "```text$label\n⭐️当前地点:门厅，天气晴朗。\n$label```"
        val after = "```text$label\n姓名、外貌：她 | 穿搭、动作：大衣\n$label```"
        val opening = "<历史回复 模式=\"推进\">"
        val closing = "</历史回复>"
        val story = "她推开门。"
        val wrapped = "$opening\n$story\n$closing"
        val both = "$before\n\n$story\n\n$after"
        assertNarrativeReplyStates("$before\n\n$wrapped\n\n$after", expected = both)
        assertNarrativeReplyStates("$before\n\n$wrapped", expected = "$before\n\n$story")
        assertNarrativeReplyStates("$wrapped\n\n$after", expected = "$story\n\n$after")
        assertNarrativeReplyStates("$opening\n$both\n$closing", expected = both)

        val code = "```kotlin\nval literal = 1\n```"
        val ordinaryStatus = "```status\n状态栏\n```"
        assertNarrativeReplyStates("$before\n\n$opening\n$code\n$closing\n\n$after", expected = "$before\n\n$code\n\n$after")
        for (literal in listOf(
            "$code\n\n$before\n\n$wrapped",
            "$wrapped\n\n$code\n\n$after",
            "$wrapped\n\n$after\n\n后续示例说明。",
            "$wrapped\n\n$ordinaryStatus",
        )) {
            assertNarrativeReplyStates(literal, expected = literal)
        }
    }

    @Test
    fun `main stream reserves only a complete explicitly mandatory XML template`() = runBlocking {
        val opening = "<历史回复 模式=\"推进\">"
        val closing = "</历史回复>"
        val raw = "$opening\n她推开门。\n$closing"
        val template = "$opening\n{正文}\n$closing"
        val lead = "请严格按照以下固定模板作答，必须逐字保留首尾标签，不要输出代码围栏："
        val fenced = "```xml\n$template\n```"
        assertNarrativeReplyStates(raw, expected = raw, formatCard = "$lead\n$fenced")
        for (card in listOf(
            fenced,
            "以下内容只是示例，标签不应输出：\n$fenced",
            "她引用了一个例子：“\n$lead\n$fenced\n引文结束。”",
            "$lead\n其他说明。\n$fenced",
            "$lead\n```kotlin\n$template\n```",
            "$lead\n```xml\n附加说明。\n$template\n```",
            "$lead\n```xml\n$template\n附加说明。\n```",
            "$lead\n```xml\n$template\n$template\n```",
            "$lead\n```xml\n$template",
        )) {
            assertNarrativeReplyStates(raw, expected = "她推开门。", formatCard = card)
        }
    }

    @Test
    fun `main stream reserves mandatory templates in unlabeled and HTML fences`() = runBlocking {
        val opening = "<历史回复 模式=\"推进\">"
        val closing = "</历史回复>"
        val raw = "$opening\n她推开门。\n$closing"
        val template = "$opening\n{正文}\n$closing"
        val lead = "请严格按照以下固定模板作答，必须逐字保留首尾标签，不要输出代码围栏："
        for (language in listOf("", "html")) {
            val fenced = "```$language\n$template\n```"
            assertNarrativeReplyStates(raw, expected = raw, formatCard = "$lead\n$fenced")
            for (card in listOf(fenced, "她引用：“\n$lead\n$fenced\n引文结束。”", "$lead\n```$language\n$template")) {
                assertNarrativeReplyStates(raw, expected = "她推开门。", formatCard = card)
            }
        }
    }

    @Test
    fun `main stream reserves a mandatory fixed template on its label line`() = runBlocking {
        val opening = "<历史回复 模式=\"推进\">"
        val closing = "</历史回复>"
        val raw = "$opening 她答应。$closing"
        assertNarrativeReplyStates(raw, expected = raw, formatCard = "每次回复必须使用此固定模板：$opening {正文}$closing")
    }

    @Test
    fun `main stream keeps a literal opener used as a sentence subject`() = runBlocking {
        val opening = "<历史回复 模式=\"推进\">"
        val closing = "</历史回复>"
        val literal = "$opening 是本次要解释的标签，下面说明它的用途。"
        assertNarrativeReplyStates(literal, expected = literal)
        assertNarrativeReplyStates("$opening 她答应。$closing", expected = " 她答应。")
        assertNarrativeReplyStates("$opening\n她答应。", expected = "她答应。")
        assertNarrativeReplyStates(opening, expected = "")
        assertNarrativeReplyStates(opening.dropLast(1), expected = "")
    }

    @Test
    fun `main stream never reserves a template pair below an example-only warning`() = runBlocking {
        val raw = "<历史回复 模式=\"推进\">\n她推开门。\n</历史回复>"
        val card = "以下标签仅供举例，不要在回复中输出：\n<历史回复 模式=\"推进\">\n{正文}\n</历史回复>"
        assertNarrativeReplyStates(raw, expected = "她推开门。", formatCard = card)
    }

    @Test
    fun `main stream never redraws the history wrapper as annotated status grows`() = runBlocking {
        val story = "她推开门。"
        val wrapped = "<历史回复 模式=\"推进\">\n$story\n</历史回复>"
        val label = "（三个点为状态包裹格式，必须完整输出）"
        for (footer in listOf("```", "$label```")) {
            val suffixDeltas = listOf("\n\n`", "``text", label.take(4), "${label.drop(4)}\n地点：门外", "\n$footer")
            val expected = suffixDeltas.runningFold(story, String::plus)
            assertNarrativeReplyDeltas(listOf(wrapped) + suffixDeltas, expected, expected.last())
        }
    }

    @Test
    fun `main stream accepts annotated status that reaches EOF after a history pair`() = runBlocking {
        val story = "她推开门。"
        val wrapped = "<历史回复 模式=\"推进\">\n$story\n</历史回复>"
        val partial = "\n\n```text（三个点"
        val status = "\n\n```text（三个点为状态包裹格式，必须完整输出）\n地点：门外"
        assertNarrativeReplyStates(wrapped + status, expected = story + status)
        assertNarrativeReplyDeltas(listOf(wrapped, partial), listOf(story, story + partial), wrapped + partial)
        assertNarrativeReplyStates(status.trimStart() + "\n$wrapped", expected = status.trimStart() + "\n$wrapped")
    }

    @Test
    fun `main stream keeps a fenced XML template declared by an existing output heading`() = runBlocking {
        val opening = "<历史回复 模式=\"推进\">"
        val closing = "</历史回复>"
        val story = "雨落在窗沿。"
        val raw = "$opening\n$story\n$closing"
        val fenced = "```xml\n$opening\n{story}\n$closing\n```"
        val label = "Required output template:"
        val card = "$label\n$fenced\nReturn this exact XML structure with both tags. Omit the Markdown fence."
        assertNarrativeReplyStates(raw, expected = raw, formatCard = card)

        for (example in listOf(
            "The following is a counterexample.\n$label\n$fenced",
            "$label\n$fenced\nDo not output these tags.",
            "她引用：“\n$label\n$fenced\n引文结束。”",
            "$label\n" + fenced.prependIndent("> "),
            "$label\n" + fenced.replace("```xml", "```kotlin"),
        )) {
            assertNarrativeReplyStates(raw, expected = story, formatCard = example)
        }
    }

    @Test
    fun `main stream keeps a selected status footer while removing its preceding history pair`() = runBlocking {
        val story = "雨落在窗沿。"
        val wrapped = PromptTemplates.narrativeModeHistoryReply(story, ChatNarrativeMode.ADVANCE)
        val status = "\n\n```status\nTime: dawn\n```"
        val lead = "Write the story first. End every reply with exactly one status panel after the story. " +
            "Keep the status fence and replace the time."
        val template = "```status\nTime: [current time]\n```"
        val card = "$lead\n\n$template"
        assertNarrativeReplyDeltas(
            listOf(wrapped, "\n\n```st", "atus\nTime: dawn", "\n```"),
            listOf(story, "$story\n\n```st", "$story\n\n```status\nTime: dawn", story + status),
            expectedCompleted = story + status,
            formatCard = card,
        )
        for (example in listOf(
            template,
            "$lead\n\n$template\nDo not output these tags.",
            "她引用：“\n$lead\n\n$template\n引文结束。”",
        )) {
            assertNarrativeReplyStates(wrapped + status, expected = wrapped + status, formatCard = example)
        }
        for (language in listOf("", "text", "kotlin")) {
            val literal = status.replace("```status", "```$language")
            assertNarrativeReplyStates(wrapped + literal, expected = wrapped + literal, formatCard = card)
        }
    }

    @Test
    fun `main stream removes only the duplicated pair projected into the current request`() = runBlocking {
        val mode = ChatNarrativeMode.ADVANCE
        val opening = "<历史回复 模式=\"推进\">"
        val closing = "</历史回复>"
        val story = "门外传来脚步。"
        val template = "$opening\n（故事正文）\n$closing"
        val label = "输出模板："
        val onePair = "每次回复只使用一对以下首尾标签，正文中不得重复这两个标签。"
        val card = "$onePair\n$label\n$template"
        val previous = ChatMessage.create("fixture", MessageRole.ASSISTANT,
            PromptTemplates.narrativeModeHistoryReply("雨落在窗沿。", mode)).copy(narrativeMode = mode)
        val authored = PromptTemplates.narrativeModeHistoryReply(story, mode)
        val echoed = PromptTemplates.narrativeModeHistoryReply(authored, mode)

        assertNarrativeReplyDeltas(
            listOf("$opening\n$opening\n$story", "\n$closing", "\n$closing"),
            listOf("$opening\n$story", authored, authored),
            expectedCompleted = authored,
            formatCard = card,
            previousAssistant = previous,
        )
        assertNarrativeReplyStates(authored, expected = authored, formatCard = card, previousAssistant = previous)
        assertNarrativeReplyStates(echoed, expected = echoed, formatCard = card)
        assertNarrativeReplyStates(echoed, expected = echoed, formatCard = card,
            previousAssistant = previous.copy(content = "普通的历史正文"))
        assertNarrativeReplyStates(echoed, expected = echoed,
            formatCard = "$label\n$opening\n$template\n$closing", previousAssistant = previous)
    }

    @Test
    fun `main stream keeps a nested reply if visible prose follows its only close`() = runBlocking {
        val mode = ChatNarrativeMode.ADVANCE
        val opening = "<历史回复 模式=\"推进\">"
        val closing = "</历史回复>"
        val card = "每次回复只使用一对以下首尾标签，正文中不得重复这两个标签。\n" +
            "输出模板：\n$opening\n（故事正文）\n$closing"
        val previous = ChatMessage.create(
            "fixture", MessageRole.ASSISTANT,
            PromptTemplates.narrativeModeHistoryReply("雨落在窗沿。", mode),
        ).copy(narrativeMode = mode)
        val authored = PromptTemplates.narrativeModeHistoryReply("门外传来脚步。", mode)
        val provisional = "$opening\n$authored"
        val continuation = "$provisional\n这一段在内层标签外继续。"
        assertNarrativeReplyStates(continuation, expected = continuation, formatCard = card, previousAssistant = previous)
        assertNarrativeReplyDeltas(
            listOf(provisional, "\n</历史回", "复>"),
            listOf(authored, authored, authored),
            expectedCompleted = authored,
            formatCard = card,
            previousAssistant = previous,
        )
    }

    @Test
    fun `main stream accepts ordinary closers only for annotated status outside the wrapper`() = runBlocking {
        val story = "她推开门。"
        val wrapped = "<历史回复 模式=\"推进\">\n$story\n</历史回复>"
        val status = "```text（三个点为状态包裹格式，必须完整输出）\n地点：门外\n```"
        assertNarrativeReplyStates("$status\n\n$wrapped", expected = "$status\n\n$story")
        assertNarrativeReplyStates("$wrapped\n\n$status", expected = "$story\n\n$status")
        assertNarrativeReplyStates("$status\n\n$wrapped\n\n$status", expected = "$status\n\n$story\n\n$status")
    }

    @Test
    fun `main stream keeps nested short Markdown fences inside the cleaned reply`() = runBlocking {
        val opening = "<历史回复 模式=\"推进\">"
        val closing = "</历史回复>"
        val quotedCode = "> > ```text\n> > $closing\n> > ```"
        val listCode = "- ```\n  $closing\n  ```"
        val story = "她读出教程。\n\n$quotedCode\n\n$listCode\n\n她关上电脑。"
        assertNarrativeReplyStates("$opening\n$story\n$closing", expected = story)
    }

    @Test
    fun `main stream still treats text and typed code as ordinary trailing content`() = runBlocking {
        val story = "她推开门。"
        val wrapped = "<历史回复 模式=\"推进\">\n$story\n</历史回复>"
        for (header in listOf("text", "kotlin", "status")) {
            val codeStart = "\n\n```$header\n"
            val codeBody = "示例内容\n```"
            val full = wrapped + codeStart + codeBody
            assertNarrativeReplyDeltas(listOf(wrapped, codeStart, codeBody), listOf(story, wrapped + codeStart, full), full)
        }
    }

    @Test
    fun `main stream closes a history wrapper on an indented paragraph continuation`() = runBlocking {
        val story = "雨停了。"
        val opening = "<历史回复 模式=\"推进\">"
        val closing = "</历史回复>"
        for (indent in listOf("    ", "\t")) {
            val partial = "$opening\n$story\n$indent${closing.dropLast(1)}"
            assertNarrativeReplyStates(partial, expected = story)
            assertNarrativeReplyDeltas(listOf(partial, ">"), listOf(story, story), story)
        }
    }

    @Test
    fun `main stream preserves the status suffix after an indented history close`() = runBlocking {
        val story = "雨停了。"
        val opening = "<历史回复 模式=\"推进\">"
        val closing = "</历史回复>"
        val wrapped = "$opening\n$story\n    $closing"
        val annotated = "```text（三个点为状态包裹格式，必须完整输出）\n地点：门外\n```"
        assertNarrativeReplyDeltas(listOf(wrapped, "\n\n$annotated"), listOf(story, "$story\n\n$annotated"), "$story\n\n$annotated")

        val normalStatus = "$wrapped\n\n```status\n地点：门外\n```"
        val visibleProse = "$wrapped\n\n接下来解释上面的标记。"
        assertNarrativeReplyStates(normalStatus, expected = normalStatus)
        assertNarrativeReplyStates(visibleProse, expected = visibleProse)
    }

    @Test
    fun `main stream preserves literal indented history markers in code quotes and lists`() = runBlocking {
        val story = "雨停了。"
        val opening = "<历史回复 模式=\"推进\">"
        val closing = "</历史回复>"
        for (body in listOf(
            "$story\n\n    $closing",
            "## 示例\n    $closing",
            "> 引用原文。\n    $closing",
            "- 列展示例。\n    $closing",
            "$story\n    她展示的结束标签是 $closing",
        )) {
            assertNarrativeReplyStates("$opening\n$body", expected = body)
            assertNarrativeReplyStates("$opening\n$body\n\n$closing", expected = body)
        }

        val inlineCode = "$story\n    `$closing`"
        assertNarrativeReplyStates("$opening\n$inlineCode\n$closing", expected = inlineCode)

        val card = "请阅读格式说明。\n    $opening\n    {正文}\n    $closing"
        assertNarrativeReplyStates("$opening\n$story\n$closing", expected = story, formatCard = card)
    }

    @Test
    fun `main stream preserves annotated action and status blocks after the narrative`() = runBlocking {
        val dashes = "—".repeat(22)
        val optionLabel = "（横线为选项包裹格式，必须完整输出）"
        val optionOpen = dashes + optionLabel
        val optionClose = optionLabel + dashes
        val heading = "**【行动选项】**"
        val statusLabel = "（三个点为状态包裹格式，必须完整输出）"
        val statusOpen = "```text$statusLabel"
        val statusClose = "$statusLabel```"
        val actions = "$optionOpen\n$heading\n\n1. **[稍候]()**\n\n2. **[离开]()**\n$optionClose"
        val status = "$statusOpen\n地点：门外\n$statusClose"
        val story = "她推开门。"
        val wrapped = "<历史回复 模式=\"推进\">\n$story\n</历史回复>"
        val body = "$story\n\n$actions\n\n$status"

        val raw = "$wrapped\n\n$actions\n\n$status"
        assertNarrativeReplyStates(raw, expected = body)
        assertNarrativeReplyStates(raw.replace("\n", "\r\n"), expected = body.replace("\n", "\r\n"))
        val headingPrefix = heading.take(4)
        val actionDeltas = listOf(
            "\n\n${optionOpen.take(4)}",
            optionOpen.drop(4),
            "\n$headingPrefix",
            actions.removePrefix("$optionOpen\n$headingPrefix") + "\n\n$status",
        )
        assertNarrativeReplyDeltas(
            listOf(wrapped) + actionDeltas,
            actionDeltas.runningFold(story, String::plus),
            body,
            formatCard = "$actions\n\n$status",
        )

        val note = "<!-- 选项旁注 -->"
        assertNarrativeReplyStates(raw.replace("1. **[稍候]()**", "1. **[稍候]()** $note"),
            expected = body.replace("1. **[稍候]()**", "1. **[稍候]()** $note"))
        val segments = parseRoleplayTextSegments(body)
        assertEquals(actions, segments.single { it.rawText == actions }.displayText)
        assertEquals(listOf(RoleplaySegmentKind.NARRATION, RoleplaySegmentKind.NARRATION, RoleplaySegmentKind.STATUS),
            segments.map { it.kind })
        assertEquals(story, stripRoleplayStatusSegments(body))

        for (suffix in listOf(
            actions.replace(heading, "**【示例】**") + "\n\n$status",
            "$optionOpen\n$heading\n正文示例。\n$optionClose\n\n$status",
            actions.removeSuffix(optionClose) + "\n\n$status",
            actions.replace(optionLabel, "").replace("2. ", "3. ") + "\n\n$status",
            "$actions\n\n$status\n\n后续示例说明。",
        )) {
            val literal = "$wrapped\n\n$suffix"
            assertNarrativeReplyStates(literal, expected = literal)
        }
    }

    @Test
    fun `ordinary SSE keeps the history wrapper out when the first headed action arrives`() = runBlocking {
        val story = "她点亮灯。"
        val wrapped = PromptTemplates.narrativeModeHistoryReply(story, ChatNarrativeMode.FOCUS)
        val firstChoice = "\n\n---\n**【行动选项】**\n1. **等到天亮。**"
        val continuation = "\n2. **出门查看。**\n---"
        assertNarrativeReplyDeltas(
            listOf(wrapped, firstChoice, continuation),
            listOf(story, story + firstChoice, story + firstChoice + continuation),
            story + firstChoice + continuation,
            experimental = false,
        )

        val oneChoice = "$firstChoice\n---"
        assertNarrativeReplyStates(
            wrapped + oneChoice,
            expected = story + oneChoice,
            formatCard = "只提供一个行动，按下列格式输出。\n" + oneChoice.trimStart(),
            experimental = false,
        )
        for (literal in listOf(
            "\n\n---\n1. 一条普通列表\n---",
            "\n\n---\n**【行动选项】**\n2. 缺少第一项\n---",
            "\n\n```markdown$oneChoice\n```",
        )) {
            assertNarrativeReplyStates(wrapped + literal, expected = wrapped + literal, experimental = false)
        }
    }

    @Test
    fun `ordinary SSE keeps bulleted choices after a cleaned history wrapper`() = runBlocking {
        val story = "灯亮了。"
        val wrapped = PromptTemplates.narrativeModeHistoryReply(story, ChatNarrativeMode.FOCUS)
        val firstChoice = "\n\n---\n**【行动选项】**\n- 先开门"
        val continuation = "\n- 等同伴\n---"
        assertNarrativeReplyDeltas(
            listOf(wrapped, firstChoice, continuation),
            listOf(story, story + firstChoice, story + firstChoice + continuation),
            story + firstChoice + continuation,
            experimental = false,
        )
        val unheaded = "\n\n---\n- 先开门\n- 等同伴\n---"
        assertNarrativeReplyStates(wrapped + unheaded, story + unheaded, experimental = false)

        for (literal in listOf(
            "\n\n---\n一段普通文字。\n---",
            "\n\n---\n- 唯一的普通列表项\n---",
            "\n\n---\n- 第一项\n* 换到另一种列表\n---",
            "\n\n```markdown$unheaded\n```",
            "$unheaded\n\n她说这只是演示。",
        )) {
            assertNarrativeReplyStates(wrapped + literal, wrapped + literal, experimental = false)
        }
    }

    @Test
    fun `ordinary SSE keeps numbered choices in supported long dash blocks`() = runBlocking {
        val story = "灯亮了。"
        val wrapped = PromptTemplates.narrativeModeHistoryReply(story, ChatNarrativeMode.FOCUS)
        for (delimiter in listOf("————", "------")) {
            val choices = "\n\n$delimiter\n1. 先开门\n2. 等同伴"
            val closing = "\n$delimiter"
            assertNarrativeReplyDeltas(
                listOf(wrapped, choices, closing),
                listOf(story, story + choices, story + choices + closing),
                story + choices + closing,
                experimental = false,
            )
            assertEquals(story, stripRoleplayStatusSegments(story + choices + closing))
            val literal = "\n\n$delimiter\n- 第一项\n- 第二项\n$delimiter"
            assertNarrativeReplyStates(wrapped + literal, wrapped + literal, experimental = false)
        }
    }

    @Test
    fun `ordinary SSE keeps a declared bare status without treating bare code as status evidence`() = runBlocking {
        val story = "灯亮了。"
        val wrapped = PromptTemplates.narrativeModeHistoryReply(story, ChatNarrativeMode.FOCUS)
        val template = "```\n🕰时间：待填\n📍地点：待填\n```"
        val card = "每次回复末尾必须输出以下状态栏：\n$template"
        val suffixDeltas = listOf("\n\n``", "`\n🕰时", "间: 晚上七点\n📍地点：中厅", "\n```")
        val status = suffixDeltas.joinToString("")
        assertNarrativeReplyDeltas(
            listOf(wrapped) + suffixDeltas,
            suffixDeltas.runningFold(story, String::plus),
            story + status,
            formatCard = card,
            experimental = false,
        )
        assertEquals(story, stripRoleplayStatusSegments(story + status))
        for (untrustedCard in listOf(template, "> 每次回复末尾必须输出以下状态栏：\n$template")) {
            assertNarrativeReplyStates(wrapped + status, wrapped + status, formatCard = untrustedCard, experimental = false)
        }
        val code = "\n\n```\nval door = open()\n```"
        assertNarrativeReplyStates(wrapped + code, wrapped + code, formatCard = card, experimental = false)
    }

    @Test
    fun `ordinary SSE keeps an explicitly authored English format`() = runBlocking {
        val story = "门外亮了起来。"
        val wrapped = PromptTemplates.narrativeModeHistoryReply(story, ChatNarrativeMode.FOCUS)
        val example = PromptTemplates.narrativeModeHistoryReply("Example body", ChatNarrativeMode.FOCUS)
        val label = "Output format:"
        val instruction = "Keep both tags verbatim in your answer and omit the surrounding code fence."
        val sample = "```xml\n$example\n```"
        assertNarrativeReplyStates(
            wrapped, expected = wrapped, formatCard = "$label\n$sample\n$instruction", experimental = false,
        )
        for (card in listOf(
            "$label\n$sample\n> $instruction",
            "$label\n$sample\n<!-- $instruction -->",
            "$label\n$sample\n$instruction (example only)",
        )) {
            assertNarrativeReplyStates(wrapped, expected = story, formatCard = card, experimental = false)
        }
    }

    @Test
    fun `ordinary SSE ignores format examples with a visible English prohibition`() = runBlocking {
        val story = "门外亮了起来。"
        val wrapped = PromptTemplates.narrativeModeHistoryReply(story, ChatNarrativeMode.FOCUS)
        val example = PromptTemplates.narrativeModeHistoryReply("Example body", ChatNarrativeMode.FOCUS)
        val prohibition = "Do not include these metadata tags in replies"
        val fenced = "Output format:\n```xml\n$example\n```\nKeep both tags verbatim and omit the code fence."
        for (card in listOf(
            "$prohibition:\n$example\nContinue with story prose only.",
            "$example\n$prohibition.",
            "$prohibition:\n$fenced",
        )) {
            assertNarrativeReplyStates(wrapped, expected = story, formatCard = card, experimental = false)
        }
    }

    @Test
    fun `ordinary SSE keeps a same line template with punctuation after its bold label`() = runBlocking {
        val mode = ChatNarrativeMode.FOCUS
        val story = "门外亮了起来。"
        val wrapped = PromptTemplates.narrativeModeHistoryReply(story, mode)
        val example = PromptTemplates.narrativeModeHistoryReply("Example body", mode)
        for (label in listOf("**本轮输出模板**：", "**Required output template**: ")) {
            assertNarrativeReplyStates(wrapped, expected = wrapped, formatCard = "$label$example", experimental = false)
            assertNarrativeReplyStates(wrapped, expected = story, formatCard = "`$label` $example", experimental = false)
        }
    }

    @Test
    fun `ordinary SSE recognizes only a connected prohibition before a metadata example`() = runBlocking {
        val mode = ChatNarrativeMode.FOCUS
        val story = "门外亮了起来。"
        val wrapped = PromptTemplates.narrativeModeHistoryReply(story, mode)
        val example = PromptTemplates.narrativeModeHistoryReply("Example body", mode)
        val prohibition = "Do not include the following history tags in your reply:"
        val bridge = "For reference, the following illustrates the provider's history metadata."
        val label = "**本轮输出模板**："
        for (sample in listOf(example, "$label$example", "输出模板：\n```xml\n$example\n```")) {
            assertNarrativeReplyStates(
                wrapped, expected = story, formatCard = "$prohibition\n$bridge\n$sample", experimental = false,
            )
        }
        for (card in listOf(
            "$prohibition\n\n$bridge\n$label$example",
            "$prohibition\n$bridge\n\n$label$example",
            "Do not output these history tags in your reply.\n\n" +
                "For reference, the following shows the provider's history metadata:\n" +
                "Output template:\n```xml\n$example\n```",
        )) {
            assertNarrativeReplyStates(wrapped, expected = story, formatCard = card, experimental = false)
        }
        for (card in listOf(
            "$prohibition\n# An independent section\n$bridge\n$label$example",
            "$prohibition\nKeep doing this for the rest of the story.\n$label$example",
            "> $prohibition\n\n$bridge\n$label$example",
            "$prohibition\n> $bridge\n\n$label$example",
        )) {
            assertNarrativeReplyStates(wrapped, expected = wrapped, formatCard = card, experimental = false)
        }
    }

    @Test
    fun `ordinary SSE cleans a balanced double transport pair witnessed in the request`() = runBlocking {
        val mode = ChatNarrativeMode.FOCUS
        val story = "门外亮了起来。"
        val old = PromptTemplates.narrativeModeHistoryReply("雨落在窗沿。", mode)
        val previous = ChatMessage.create("fixture", MessageRole.ASSISTANT, old).copy(narrativeMode = mode)
        val inner = PromptTemplates.narrativeModeHistoryReply(story, mode)
        val echoed = PromptTemplates.narrativeModeHistoryReply(inner, mode)
        val partial = echoed.substringBeforeLast("\n</历史回复>")
        assertNarrativeReplyDeltas(
            listOf(partial, "\n</历史回复>"), listOf(story, story), story,
            previousAssistant = previous, experimental = false,
        )
        assertNarrativeReplyStates(echoed, expected = inner, experimental = false)
        assertNarrativeReplyStates(echoed, expected = inner,
            previousAssistant = previous.copy(narrativeMode = ChatNarrativeMode.ADVANCE), experimental = false)
        assertNarrativeReplyStates(echoed, expected = echoed, formatCard = "输出模板：$inner",
            previousAssistant = previous, experimental = false)
        assertEquals(old, previous.displayContent)
    }

    @Test
    fun `ordinary SSE removes witnessed double transport before a preserved suffix`() = runBlocking {
        val mode = ChatNarrativeMode.FOCUS
        val opening = "<历史回复 模式=\"聚焦\">"
        val close = "</历史回复>"
        val old = PromptTemplates.narrativeModeHistoryReply("雨落在窗沿。", mode)
        val previous = ChatMessage.create("fixture", MessageRole.ASSISTANT, old).copy(narrativeMode = mode)
        val story = "门外亮了起来。"
        val partial = "$opening\n$opening\n$story\n$close\n</历史"
        assertNarrativeReplyDeltas(
            listOf(partial, "回复>\n<!-- keep -->"), listOf(story, "$story\n<!-- keep -->"),
            "$story\n<!-- keep -->", previousAssistant = previous, experimental = false,
        )
        val status = "```status\nTime: 10:00\n```"
        assertNarrativeReplyStates(
            "$opening\n$opening\n$story\n$close\n$close\n$status", expected = "$story\n$status",
            formatCard = "Required trailing status panel template:\n$status",
            previousAssistant = previous, experimental = true,
        )
    }

    @Test
    fun `ordinary SSE ignores fenced tags while recognizing witnessed double transport`() = runBlocking {
        val mode = ChatNarrativeMode.FOCUS
        val close = "</历史回复>"
        val story = "门外亮了起来。\n```xml\n$close\n```\n有人来了。"
        val original = PromptTemplates.narrativeModeHistoryReply(story, mode)
        val previous = ChatMessage.create("fixture", MessageRole.ASSISTANT, original).copy(narrativeMode = mode)
        val echoed = PromptTemplates.narrativeModeHistoryReply(original, mode)
        assertNarrativeReplyStates(echoed, expected = story, previousAssistant = previous, experimental = false)
        assertNarrativeReplyStates(echoed, expected = original, experimental = false)
        assertEquals(original, previous.displayContent)
    }

    @Test
    fun `main SSE preserves list item code while removing the actual closing tag`(): Unit = runBlocking {
        val opening = "<历史回复 模式=\"聚焦\">"
        val close = "</历史回复>"
        for (listMarker in listOf("-", "1.")) {
            val story = "以下是代码片段：\n\n$listMarker     $close"
            assertNarrativeReplyStates("$opening\n$story\n$close", expected = story, experimental = false)
            assertMultipartNarrativeReply(listOf("$opening\n$story", close), expected = story)
        }
    }

    @Test
    fun `main SSE removes only exact sent narrative headings at reply edges`() = runBlocking {
        val story = "门外亮了起来。"
        for (mode in ChatNarrativeMode.entries) {
            val prompt = PromptTemplates.narrativeModeSystemPrompt(mode)
            val heading = prompt.lineSequence().first()
            val paired = PromptTemplates.narrativeModeHistoryReply(story, mode)
            assertNarrativeReplyStates(
                "$heading\n$paired\n$heading", expected = story, experimental = false, narrativePrompt = prompt,
            )
            assertNarrativeReplyStates(
                "$heading\n$story", expected = "$heading\n$story", experimental = false,
            )
            assertNarrativeReplyStates(
                "```text\n$heading\n```", expected = "```text\n$heading\n```",
                experimental = false, narrativePrompt = prompt,
            )
            assertNarrativeReplyStates(
                "$heading\n$story", expected = "$heading\n$story", experimental = false,
                narrativePrompt = prompt, currentUserText = "请保留 $heading",
            )
            assertNarrativeReplyStates(
                "$heading\n$story", expected = "$heading\n$story", experimental = false,
                narrativePrompt = prompt, formatCard = "使用这个固定标题：\n$heading",
            )
            val parts = listOf(heading, paired, heading)
            val cleanup = NarrativeHistoryEcho.forRequest(mode, null)
            cleanup.recordNarrativePrompt(prompt, story)
            assertEquals(story, cleanup.completedBody(parts.joinToString("\n"), partLengths = parts.map(String::length)))
        }
    }

    @Test
    fun `main SSE only removes an unprotected orphan close from sent history`() = runBlocking {
        val mode = ChatNarrativeMode.FOCUS
        val story = "门外亮了起来。"
        val close = "</历史回复>"
        val previous = ChatMessage.create("fixture", MessageRole.ASSISTANT, "雨落在窗沿。").copy(narrativeMode = mode)
        val raw = "$story\n$close"
        assertNarrativeReplyStates(raw, expected = story, previousAssistant = previous, experimental = false)
        assertNarrativeReplyStates(raw, expected = raw, experimental = false)
        val legacyClose = "</聚焦模式>"
        val legacy = previous.copy(content = "<聚焦模式=\"沉浸\">\n雨落在窗沿。\n$legacyClose")
        assertNarrativeReplyStates(
            "$story\n$legacyClose", expected = story, previousAssistant = legacy, experimental = false,
        )
        assertNarrativeReplyStates(
            raw, expected = raw, previousAssistant = previous, experimental = false, currentUserText = "逐字输出 $close",
        )
        for (literal in listOf(
            "$story\n```xml\n$close",
            "$story\n> $close",
            "闭合标签示例：\n$close",
            "$story\n    $close",
        )) {
            assertNarrativeReplyStates(literal, expected = literal, previousAssistant = previous, experimental = false)
        }
    }

    @Test
    fun `format repair SSE keeps prohibited examples out of the repair prefix and replacement`() = runBlocking {
        val mode = ChatNarrativeMode.FOCUS
        val original = "月光落在旧窗台。"
        val changed = "月光照进窗台。"
        val sample = PromptTemplates.narrativeModeHistoryReply("旧片段", mode)
        val card = "Do not output these history tags in your reply.\n\n" +
            "For reference, the following shows the provider's history metadata:\n" +
            "Output template:\n```xml\n$sample\n```"
        val wrapped = PromptTemplates.narrativeModeHistoryReply(changed, mode)
        val storyPrefix = changed.take(3)
        val partial = wrapped.substringBefore(storyPrefix) + storyPrefix

        fun delta(content: String) = buildJsonObject {
            put("choices", buildJsonArray {
                add(buildJsonObject { put("delta", buildJsonObject { put("content", content) }) })
            })
        }.toString()

        GatedExperimentalResponse(
            sse(listOf(delta(partial))),
            sse(listOf(delta(wrapped.substring(partial.length)), "[DONE]")),
        ).use { server ->
            val transport = StreamingChatService(allowCleartextHttp = { true })
            val repair = MessageFormatRepairService(transport)
            val cleanup = NarrativeHistoryEcho.forFormatRepair(card, original)
            var received = ""
            val events = withTimeout(5_000) {
                repair.streamRepair(original, card, null, model(server.baseUrl)).onEach { event ->
                    if (event is StreamEvent.Delta) {
                        received += event.text
                        if (received == partial) {
                            assertFalse(server.tailSent)
                            for (prefix in listOf(
                                cleanup.completedBody(received, streaming = true),
                                cleanup.completedBody(received, interrupted = true),
                            )) {
                                assertEquals(storyPrefix, prefix)
                                assertEquals(
                                    storyPrefix + original.drop(storyPrefix.length),
                                    MessageFormatRepairPolicy.progressiveOverlay(original, prefix),
                                )
                            }
                            server.releaseTail()
                        }
                    }
                }.toList()
            }
            val payload = Json.parseToJsonElement(server.requestBody).jsonObject.getValue("messages").jsonArray
            assertTrue(payload.any { card in it.jsonObject.getValue("content").jsonPrimitive.content })
            assertEquals(StreamEvent.Done, events.last())
            assertEquals(wrapped, received)
            assertEquals(changed, cleanup.completedBody(received))
        }
        for (safe in listOf(
            NarrativeHistoryEcho.forFormatRepair("输出模板：$sample", original),
            NarrativeHistoryEcho.forFormatRepair("$card\n\n输出模板：$sample", original),
            NarrativeHistoryEcho.forFormatRepair(card, sample),
            NarrativeHistoryEcho.forFormatRepair(card, original, "逐字输出 $sample"),
        )) {
            assertEquals(wrapped, safe.completedBody(wrapped))
        }
    }

    @Test
    fun `ordinary and tool replies clean both sent narrative headings`() = runBlocking {
        val story = "门外亮了起来。"
        for (mode in ChatNarrativeMode.entries) {
            val prompt = PromptTemplates.narrativeModeSystemPrompt(mode)
            val title = prompt.lineSequence().first()
            val tail = PromptTemplates.narrativeModeTailSystemPrompt(mode, null)
            val tailTitle = tail.lineSequence().first()
            val partialTitle = title.substringBefore('（')
            for (toolCall in listOf(false, true)) {
                assertNarrativeReplyDeltas(
                    listOf("$tailTitle\n$title\n$story\n$partialTitle", title.removePrefix(partialTitle)),
                    listOf(story, story), story, experimental = toolCall,
                    narrativePrompt = prompt, narrativeTail = tail, toolCall = toolCall,
                )
            }
            val raw = "$story\n$tailTitle"
            assertNarrativeReplyStates(raw, expected = raw, narrativePrompt = prompt, experimental = false)
            for ((literal, card, user) in listOf(
                Triple(raw, null, "请逐字保留 $tailTitle"),
                Triple("$story\n$partialTitle", null, "请逐字保留 $partialTitle"),
                Triple(raw, "固定标题：$tailTitle", null),
                Triple("他写了“\n$tailTitle\n”。", null, null),
            )) {
                assertNarrativeReplyDeltas(
                    listOf(literal), listOf(literal), literal,
                    experimental = true, narrativePrompt = prompt, narrativeTail = tail,
                    toolCall = true, formatCard = card, currentUserText = user,
                )
            }
        }
    }

    @Test
    fun `ordinary and tool replies remove a projected orphan close and preserve the declared footer`() = runBlocking {
        val story = "门外亮了起来。"
        val close = "</历史回复>"
        val partial = "</历史回"
        val fence = "```"
        val footer = "\n\n${fence}status\n时间：夜晚\n$fence"
        val card = "每次回复末尾必须输出以下状态栏：\n${fence}status\n时间：[当前时间]\n$fence"
        val previous = ChatMessage.create("fixture", MessageRole.ASSISTANT, "雨落在窗沿。")
            .copy(narrativeMode = ChatNarrativeMode.ADVANCE)
        for (toolCall in listOf(false, true)) {
            assertNarrativeReplyDeltas(
                listOf("$story\n$partial", close.removePrefix(partial) + footer),
                listOf(story, story + footer), story + footer, formatCard = card, previousAssistant = previous,
                experimental = toolCall, toolCall = toolCall,
            )
        }
        assertNarrativeReplyStates(close, expected = "", previousAssistant = previous, experimental = false)
        assertNarrativeReplyStates("$story\n$close$footer", expected = "$story\n$close$footer", experimental = false)
        for (literal in listOf("$story\n${fence}xml\n$partial", "$story\n</", "$story\n$close\n上一行是示例。")) {
            assertNarrativeReplyStates(literal, expected = literal, previousAssistant = previous)
        }
        val authored = "$story\n$partial"
        assertNarrativeReplyStates(
            authored, expected = authored, previousAssistant = previous, currentUserText = "请逐字保留 $partial",
        )
    }

    @Test
    fun `same line prose stays visible until its matching outer close arrives`() = runBlocking {
        val mode = ChatNarrativeMode.ADVANCE
        val story = "门外亮了起来。"
        val prefix = "<历史回复 模式=\"${mode.shortLabel}\">$story"
        val previous = ChatMessage.create("fixture", MessageRole.ASSISTANT, "雨落在窗沿。").copy(narrativeMode = mode)
        for (toolCall in listOf(false, true)) {
            assertNarrativeReplyDeltas(
                listOf(prefix, "</历史回复>"), listOf(prefix, story), story, previousAssistant = previous,
                experimental = toolCall, toolCall = toolCall,
            )
        }
    }

    @Test
    fun `repair SSE removes an orphan close from a delivered forbidden example`() = runBlocking {
        val original = "月光落在旧窗台。"
        val revised = "月光照进窗台。"
        val partial = "</历史回"
        val close = "</历史回复>"
        val sample = PromptTemplates.narrativeModeHistoryReply("旧片段", ChatNarrativeMode.ADVANCE)
        val card = "以下标签仅供举例，不要在回复中输出：\n$sample"
        val prefix = "$revised\n$partial"
        val cleanup = NarrativeHistoryEcho.forFormatRepair(card, original)
        fun delta(content: String) = buildJsonObject {
            put("choices", buildJsonArray {
                add(buildJsonObject { put("delta", buildJsonObject { put("content", content) }) })
            })
        }.toString()

        GatedExperimentalResponse(
            sse(listOf(delta(prefix))),
            sse(listOf(delta(close.removePrefix(partial)), "[DONE]")),
        ).use { server ->
            val repair = MessageFormatRepairService(StreamingChatService(allowCleartextHttp = { true }))
            var received = ""
            val events = withTimeout(5_000) {
                repair.streamRepair(original, card, null, model(server.baseUrl)).onEach { event ->
                    if (event is StreamEvent.Delta) {
                        received += event.text
                        if (received == prefix) {
                            assertFalse(server.tailSent)
                            for ((streaming, stopped) in listOf(true to false, false to true)) {
                                val cleaned = cleanup.completedBody(received, streaming = streaming, interrupted = stopped)
                                assertEquals(revised, cleaned)
                                assertEquals(revised + original.drop(revised.length),
                                    MessageFormatRepairPolicy.progressiveOverlay(original, cleaned))
                            }
                            server.releaseTail()
                        }
                    }
                }.toList()
            }
            val payload = Json.parseToJsonElement(server.requestBody).jsonObject.getValue("messages").jsonArray
            assertTrue(payload.any { card in it.jsonObject.getValue("content").jsonPrimitive.content })
            assertEquals(StreamEvent.Done, events.last())
            assertEquals("$revised\n$close", received)
            assertEquals(revised, cleanup.completedBody(received))
            assertEquals(received, NarrativeHistoryEcho.forFormatRepair("输出模板：$sample", original).completedBody(received))
            assertEquals(received, NarrativeHistoryEcho.forFormatRepair(card, "$original\n$close").completedBody(received))
            val literal = NarrativeHistoryEcho.forFormatRepair(card, "$original\n$partial")
            assertEquals(prefix, literal.completedBody(prefix, interrupted = true))
        }
    }

    private suspend fun assertNarrativeReplyStates(
        raw: String,
        expected: String,
        formatCard: String? = null,
        previousAssistant: ChatMessage? = null,
        experimental: Boolean = true,
        narrativePrompt: String? = null,
        currentUserText: String? = null,
    ) = assertNarrativeReplyDeltas(
        listOf(raw), listOf(expected), expected, formatCard, previousAssistant, experimental, narrativePrompt, currentUserText,
    )

    private suspend fun assertNarrativeReplyDeltas(
        deltaTexts: List<String>,
        expectedPreviews: List<String>,
        expectedCompleted: String,
        formatCard: String? = null,
        previousAssistant: ChatMessage? = null,
        experimental: Boolean = true,
        narrativePrompt: String? = null,
        currentUserText: String? = null,
        narrativeTail: String? = null,
        toolCall: Boolean = false,
    ) {
        val cleanup = NarrativeHistoryEcho.forRequest(ChatNarrativeMode.ADVANCE, formatCard)
        cleanup.recordNarrativePrompt(narrativePrompt.orEmpty(), currentUserText)
        val requestMessages = messages.toMutableList()
        if (currentUserText != null) {
            val userIndex = requestMessages.indexOfFirst { it.role == "user" }
            requestMessages[userIndex] = ChatApiMessage.text("user", currentUserText)
        }
        if (narrativePrompt != null) requestMessages.add(1, ChatApiMessage.text("system", narrativePrompt))
        val cardRequirements = formatCard?.let { PromptTemplates.currentTurnOutputRequirementsSystemPrompt(it, 300) }
        cardRequirements?.let { requestMessages.add(ChatApiMessage.text("system", it)) }
        if (narrativeTail != null) {
            requestMessages.add(ChatApiMessage.text("system", narrativeTail))
            cleanup.recordNarrativePrompt(narrativeTail, currentUserText)
        }
        previousAssistant?.let { assistant ->
            val body = assistant.displayContent
            val projected = ChatHistoryPromptPolicy.renderedHistoryText(
                assistant, body, legacyHistoryWrapperDeclaredAsOutput = cleanup.declaresLegacyHistoryWrapper,
            )
            cleanup.recordHistoryProjection(body, projected)
            requestMessages.add(1, ChatApiMessage.text("assistant", projected))
        }
        val acknowledgement = PromptTemplates.CCB_POST_USER_ACK_ASSISTANT_PROMPT.trimIndent().trim()
        requestMessages.add(ChatApiMessage.text("assistant", acknowledgement))
        val rawPreviews = deltaTexts.runningFold("", String::plus).drop(1)
        val deltas = deltaTexts.mapIndexed { index, delta ->
            if (toolCall) {
                val prefix = if (index == 0) "{\"content\":\"" else ""
                val suffix = if (index == deltaTexts.lastIndex) "\"}" else ""
                val escaped = JsonPrimitive(delta).toString().removeSurrounding("\"")
                chunk(prefix + escaped + suffix, "game_content".takeIf { index == 0 })
            } else buildJsonObject {
                put("choices", buildJsonArray {
                    add(buildJsonObject { put("delta", buildJsonObject { put("content", delta) }) })
                })
            }.toString()
        }
        GatedExperimentalResponse(
            sse(deltas),
            sse(listOf(chunk(reason = if (toolCall) "tool_calls" else "stop"), "[DONE]")),
        ).use { server ->
            val previews = mutableListOf<String>()
            val chatService = StreamingChatService(experimentalApiModeEnabled = { experimental }, allowCleartextHttp = { true })
            val events = withTimeout(5_000) {
                chatService.streamChat("narrative-reply-states", requestMessages, model(server.baseUrl)).onEach { event ->
                    val preview = when {
                        event is StreamEvent.ContentPreview -> event.text
                        (!experimental || toolCall) && event is StreamEvent.Delta ->
                            previews.lastOrNull().orEmpty() + event.text
                        else -> null
                    }
                    if (preview != null) {
                        previews += preview
                        if (preview == rawPreviews.last()) {
                            assertFalse(server.tailSent)
                            server.releaseTail()
                        }
                    }
                }.toList()
            }
            assertEquals(rawPreviews, previews)
            val payload = Json.parseToJsonElement(server.requestBody).jsonObject
            val context = if (experimental) {
                val description = payload.getValue("tools").jsonArray.map { it.jsonObject.getValue("function").jsonObject }
                    .single { it.getValue("name").jsonPrimitive.content == "context" }
                    .getValue("description").jsonPrimitive.content
                Json.parseToJsonElement(description + "\"}]").jsonArray
            } else payload.getValue("messages").jsonArray
            val historyProjection = previousAssistant?.let { requestMessages[1].content.jsonPrimitive.content }
            for (source in listOfNotNull(narrativePrompt, narrativeTail, currentUserText, historyProjection, cardRequirements)) {
                assertTrue("Expected source in serialized reply request", context.any {
                    it.jsonObject.getValue("content").jsonPrimitive.content == source
                })
            }
            assertEquals(acknowledgement, context.last().jsonObject.getValue("content").jsonPrimitive.content)
            assertEquals(StreamEvent.Done, events.last())
            val completed = events.filterIsInstance<StreamEvent.Delta>().joinToString("") { it.text }
            assertEquals(rawPreviews.last(), completed)
            for ((index, preview) in previews.withIndex()) {
                assertEquals("Preview delta ${index + 1}", expectedPreviews[index], cleanup.completedBody(preview, streaming = true))
                assertEquals("Stop delta ${index + 1}", expectedPreviews[index], cleanup.completedBody(preview, interrupted = true))
            }
            assertEquals("Done", expectedCompleted, cleanup.completedBody(completed))
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
    @Volatile var requestBody = ""
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
            val requestLength = headers.lines().first { it.startsWith("Content-Length:", true) }
                .substringAfter(':').trim().toInt()
            val requestBytes = input.readNBytes(requestLength)
            check(requestBytes.size == requestLength)
            requestBody = requestBytes.toString(Charsets.UTF_8)
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
