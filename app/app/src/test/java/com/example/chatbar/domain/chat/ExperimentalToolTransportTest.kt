package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatNarrativeMode
import com.example.chatbar.data.local.entity.FormatCardUserToolConfig
import com.example.chatbar.data.local.entity.FormatPromptPosition
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.card.FormatCardUserToolPolicy
import com.example.chatbar.domain.prompt.AiTaskContext
import com.example.chatbar.domain.prompt.AiTaskKind
import com.example.chatbar.domain.prompt.PromptTemplates
import com.example.chatbar.ui.chat.appendCurrentUserAndCcbTailMessages
import com.example.chatbar.ui.chat.buildCcbFinalTailSystemPrompt
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
            "$opening\n展示内容。\n示例下一行。\n$close\n这是一个标签示例。",
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
        parts: List<String>, expected: String, formatCard: String? = null, narrativePrompt: String? = null,
        userText: String? = null,
    ): String {
        val cleanup = NarrativeHistoryEcho.forRequest(ChatNarrativeMode.FOCUS, formatCard)
        val request = messages.toMutableList()
        userText?.let {
            cleanup.recordNarrativePrompt("", it)
            cleanup.recordCurrentLiteralTags(it)
            request.add(ChatApiMessage.text("user", it))
        }
        for (assistant in narrativeHistoryFixtures()) {
            request.add(1, projectAssistant(cleanup, assistant))
        }
        narrativePrompt?.let {
            cleanup.recordNarrativePrompt(it, null)
            request.add(1, ChatApiMessage.text("system", it))
        }
        formatCard?.let {
            request.add(1, ChatApiMessage.text("system", PromptTemplates.currentTurnOutputRequirementsSystemPrompt(it, 300)))
        }
        val response = sse(parts.mapIndexed { index, part ->
            chunk(buildJsonObject { put("content", part) }.toString(), "game_content", index = index)
        } + listOf(chunk(reason = "tool_calls"), "[DONE]"))
        return TaskResponseServer(response, "text/event-stream").use { server ->
            var completion: ChatReplyCompletion? = null
            val events = withTimeout(5_000) {
                service().streamChat("grouped-tool-echo", request, model(server),
                    onReplyCompletion = { completion = it }).toList()
            }
            val deltas = events.filterIsInstance<StreamEvent.Delta>()
            val raw = deltas.joinToString("") { it.text }
            val lengths = deltas.last().toolReplyPartLengths
            assertEquals(parts.joinToString("\n"), raw)
            assertEquals(parts.map(String::length), lengths)
            assertEquals(lengths, completion?.toolReplyPartLengths)
            assertEquals(StreamEvent.Done, events.last())
            val sent = Json.parseToJsonElement(server.requestBody).jsonObject.getValue("tools").jsonArray
                .map { it.jsonObject.getValue("function").jsonObject }
                .single { it.getValue("name").jsonPrimitive.content == "context" }
                .getValue("description").jsonPrimitive.content
            val sentHistory = Json.parseToJsonElement(sent).jsonArray
            for (source in request.drop(1).dropLast(1)) {
                assertTrue(sentHistory.any { it.jsonObject.getValue("content") == source.content })
            }
            assertEquals("Preview", expected, cleanup.completedBody(raw, partLengths = lengths, streaming = true))
            assertEquals("Stop", expected, cleanup.completedBody(raw, partLengths = lengths, interrupted = true))
            cleanup.completedBody(raw, partLengths = completion?.toolReplyPartLengths.orEmpty()).also {
                assertEquals("Done", expected, it)
            }
        }
    }

    @Test
    fun `actual streams honor verbatim copies and explicit tags inside a separate history echo`(): Unit = runBlocking {
        val opening = "<历史回复 模式=\"聚焦\">"
        val closing = "</历史回复>"
        val requestedPair = "${opening}原样正文$closing"
        val copyRequest = "Please return this XML verbatim: $requestedPair"
        for (experimental in listOf(false, true)) {
            assertNarrativeReplyStates(requestedPair, requestedPair, currentUserText = copyRequest, experimental = experimental)
            assertNarrativeReplyStates(
                "$opening\n$requestedPair\n$closing", requestedPair, currentUserText = copyRequest, experimental = experimental,
            )
        }
        val following = "$opening\n门开了。\n$closing"
        for ((tag, instruction) in listOf(
            opening to "Please show the literal opening tag only on its own line, then explain it:",
            closing to "Please show the literal closing tag on its own line, then explain it:",
        )) {
            val body = "$tag\nThis is a delimiter in serialized history."
            val userText = "$instruction\n$tag"
            assertNarrativeReplyStates("$opening\n$body\n$closing", body, currentUserText = userText)
            assertMultipartNarrativeReply(listOf(opening, body, closing, following), "$body\n门开了。", userText = userText)
        }
        val example = listOf("$opening\n示例内容", "$closing\n这是一个历史标签示例。")
        assertMultipartNarrativeReply(example + following, "${example.joinToString("\n")}\n门开了。")
    }

    @Test
    fun `actual modes preserve explicitly copied Markdown quotes and balanced bare dialogue`(): Unit = runBlocking {
        val closing = "</历史回复>"
        for (mode in ChatNarrativeMode.entries) {
            val opening = "<历史回复 模式=\"${mode.shortLabel}\">"
            val requested = "$opening\nEXACT_QUOTED_COPY\n$closing"
            val user = "Please copy the following XML verbatim:\n" +
                requested.lineSequence().joinToString("\n") { "> $it" }
            val dialogue = "[Read this tag example:\n$closing\nIt is only an example.]"
            val dialogueWire = "$opening\n$dialogue\n$closing"
            for (experimental in listOf(false, true)) {
                assertNarrativeReplyStates(requested, requested, currentUserText = user, experimental = experimental)
                assertNarrativeReplyStates(dialogueWire, dialogue, experimental = experimental)
            }
            assertNarrativeReplyDeltas(
                listOf(requested), listOf(requested), requested, currentUserText = user, toolCall = true,
            )
            assertMultipartNarrativeReply(listOf(opening, dialogue, closing), dialogue)
        }
    }

    @Test
    fun `one response can echo two adjacent witnessed history pairs`(): Unit = runBlocking {
        for (mode in ChatNarrativeMode.entries) {
            val first = PromptTemplates.narrativeModeHistoryReply("雨停了。", mode)
            val second = PromptTemplates.narrativeModeHistoryReply("She opened the door.", mode)
            val opening = second.substringBefore('\n')
            val earlyOpener = opening.take(2)
            val restOfPartialOpener = opening.drop(2).dropLast(1)
            val chunks = listOf(first, "\n$earlyOpener", restOfPartialOpener, second.drop(opening.length - 1))
            val cleaned = "雨停了。\nShe opened the door."
            val previews = listOf("雨停了。", "雨停了。", "雨停了。", cleaned)
            for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
                assertNarrativeReplyDeltas(chunks, previews, cleaned, experimental = experimental, toolCall = toolCall)
            }

            val raw = "$first\n$second"
            val copy = "Please copy the following XML verbatim:\n$raw"
            assertNarrativeReplyStates(raw, raw, currentUserText = copy)
            val explanatory = "$raw\nThis explains the two examples."
            assertNarrativeReplyStates(explanatory, explanatory, experimental = false)
        }
    }

    @Test
    fun `a comment-only gap preserves its bytes between adjacent history pairs`() = runBlocking {
        val first = PromptTemplates.narrativeModeHistoryReply("雨停了。", ChatNarrativeMode.FOCUS)
        val second = PromptTemplates.narrativeModeHistoryReply("她推开门。", ChatNarrativeMode.FOCUS)
        val note = "<!-- 段落提示 -->"
        val gap = "\n$note"
        val cleaned = "雨停了。$gap\n她推开门。"
        val openerPrefix = second.take(2)
        assertNarrativeReplyDeltas(
            listOf(first, gap, "\n$openerPrefix", second.drop(openerPrefix.length)),
            listOf("雨停了。", "雨停了。$gap", "雨停了。$gap\n", cleaned),
            cleaned,
            experimental = false,
        )
    }

    @Test
    fun `visible or fenced examples between adjacent history pairs stay literal`() = runBlocking {
        val first = PromptTemplates.narrativeModeHistoryReply("第一个示例。", ChatNarrativeMode.FOCUS)
        val second = PromptTemplates.narrativeModeHistoryReply("第二个示例。", ChatNarrativeMode.FOCUS)
        val note = "<!-- 段落提示 -->"
        for (gap in listOf("$note\n下面是另一个示例。", "~~~xml\n$note\n~~~")) {
            val raw = "$first\n$gap\n$second"
            assertNarrativeReplyStates(raw, raw, experimental = false)
        }
    }

    @Test
    fun `one tool call can contain adjacent history pairs before a separate prose call`(): Unit = runBlocking {
        val first = PromptTemplates.narrativeModeHistoryReply("雨停了。", ChatNarrativeMode.FOCUS)
        val second = PromptTemplates.narrativeModeHistoryReply("她推开门。", ChatNarrativeMode.FOCUS)
        assertMultipartNarrativeReply(listOf("$first\n$second", "风吹进来。"), "雨停了。\n她推开门。\n风吹进来。")
    }

    @Test
    fun `adjacent history pairs in a tool call stay literal within a cross-call fence`(): Unit = runBlocking {
        val first = PromptTemplates.narrativeModeHistoryReply("第一个示例。", ChatNarrativeMode.FOCUS)
        val second = PromptTemplates.narrativeModeHistoryReply("第二个示例。", ChatNarrativeMode.FOCUS)
        val parts = listOf("~~~xml", "$first\n$second", "~~~\n这段在解释示例。")
        assertMultipartNarrativeReply(parts, parts.joinToString("\n"))
    }

    @Test
    fun `visible XML documentation prohibition does not reserve a history wrapper`(): Unit = runBlocking {
        for (mode in ChatNarrativeMode.entries) {
            val example = PromptTemplates.narrativeModeHistoryReply("旧句。", mode)
            val story = "She opened the door."
            val reply = PromptTemplates.narrativeModeHistoryReply(story, mode)
            val documentation = "The XML below is documentation. Never put it around your answer."
            val card = "$documentation\n$example\nWrite only the story."
            for (experimental in listOf(false, true)) {
                assertNarrativeReplyStates(reply, story, formatCard = card, experimental = experimental)
            }
            assertNarrativeReplyStates(reply, reply, formatCard = "Output template:\n$example")
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
    fun `live lazy list example keeps its literal close while a later outer close is removed`(): Unit = runBlocking {
        val opening = "<历史回复 模式=\"聚焦\">"
        val closing = "</历史回复>"
        val stopped = "- A literal closing-tag example:\n$closing"
        val body = "$stopped\n\nThe explanation continues."
        for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
            assertNarrativeReplyDeltas(
                listOf("$opening\n$stopped", "\n\nThe explanation continues.\n$closing"),
                listOf(stopped, body), body, experimental = experimental, toolCall = toolCall,
            )
        }
        assertMultipartNarrativeReply(
            listOf("$opening\n- A literal closing-tag example:", closing, "\nThe explanation continues.\n$closing"),
            body,
        )
    }

    @Test
    fun `only an eligible angle trailer can continue a validated closing group`(): Unit = runBlocking {
        val opening = "<历史回复 模式=\"聚焦\">"
        val close = "</历史回复>"
        val comment = listOf("$opening\nFirst", "$close\n<!-- note", "continued -->")
        assertMultipartNarrativeReply(comment, "First\n<!-- note\ncontinued -->")
        val plain = listOf("$opening\nFirst", "$close\n<widget>ordinary</widget>") +
            List(10) { "Part $it" }
        assertMultipartNarrativeReply(plain, (listOf("First") + plain.drop(1)).joinToString("\n"))
    }

    @Test
    fun `a closing marker after a list can still prove it is outside an earlier quote`(): Unit = runBlocking {
        val opening = "<历史回复 模式=\"聚焦\">"
        val close = "</历史回复>"
        val actionList = "Story.\n$close\n---\n1. One\n2. Two"
        assertMultipartNarrativeReply(listOf("$opening\n> quoted", actionList, close), "> quoted\n$actionList")
    }

    @Test
    fun `main stream keeps a standalone lazy quote tag before the terminal outer close`() = runBlocking {
        val close = "</历史回复>"
        val body = "> 下面这行是要展示的闭合标记。\n$close\n\n讲完代码后，他们走向码头。"
        assertNarrativeReplyStates("<历史回复 模式=\"聚焦\">\n$body\n$close", expected = body)
    }

    @Test
    fun `normal SSE preserves a captioned lazy quote at length and at a true close`(): Unit = runBlocking {
        assertCaptionedLazyQuote(experimental = false, toolCall = false)
    }

    @Test
    fun `experimental text preserves a captioned lazy quote at length and at a true close`(): Unit = runBlocking {
        assertCaptionedLazyQuote(experimental = true, toolCall = false)
    }

    @Test
    fun `experimental tool preserves a captioned lazy quote at length and at a true close`(): Unit = runBlocking {
        assertCaptionedLazyQuote(experimental = true, toolCall = true)
    }

    private suspend fun assertCaptionedLazyQuote(experimental: Boolean, toolCall: Boolean) {
        val open = "<历史回复 模式=\"聚焦\">"
        val close = "</历史回复>"
        val caption = "> Closing tag example:"
        val body = "$caption\n$close"
        assertNarrativeReplyDeltas(
            listOf("$open\n$caption\n", close.dropLast(1), close.takeLast(1)),
            listOf("$caption\n", "$caption\n${close.dropLast(1)}", body), body,
            experimental = experimental, toolCall = toolCall, finishReason = "length",
        )
        assertNarrativeReplyDeltas(
            listOf("$open\n$body", "\n$close"), listOf(body, body), body,
            experimental = experimental, toolCall = toolCall,
        )
        val ordinaryQuote = "> Spoken dialogue."
        assertNarrativeReplyDeltas(
            listOf("$open\n$ordinaryQuote\n$close"), listOf(ordinaryQuote), ordinaryQuote,
            experimental = experimental, toolCall = toolCall,
        )
    }

    @Test
    fun `normal SSE preserves the body bytes of an exact frame sent as history`(): Unit = runBlocking {
        val mode = ChatNarrativeMode.FOCUS
        val body = "\nFirst line\n<!-- Editorial note -->  \n"
        val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, body).copy(narrativeMode = mode)
        assertNarrativeReplyStates(
            PromptTemplates.narrativeModeHistoryReply(body, mode), body,
            previousAssistant = prior, experimental = false,
        )
    }

    @Test
    fun `adjacent echoed frames close after quoted text in ordinary and experimental streams`(): Unit = runBlocking {
        val mode = ChatNarrativeMode.FOCUS
        val close = "</历史回复>"
        val next = "The lights go out."
        val second = PromptTemplates.narrativeModeHistoryReply(next, mode)
        for (quote in listOf("> Spoken dialogue.", "> Closing tag example:\n$close")) {
            val first = PromptTemplates.narrativeModeHistoryReply(quote, mode)
            val expected = "$quote\n$next"
            for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
                assertNarrativeReplyDeltas(
                    listOf(first, "\n${second.take(6)}", second.drop(6)), listOf(quote, quote, expected), expected,
                    experimental = experimental, toolCall = toolCall,
                )
            }
            assertMultipartNarrativeReply(listOf(first, second), expected)
            val continuation = "They watch from the hall."
            assertMultipartNarrativeReply(listOf("$first\n$second", continuation), "$expected\n$continuation")
        }
    }

    @Test
    fun `one streamed reply can contain independently witnessed legacy and modern frames in either order`(): Unit = runBlocking {
        val legacy = "<聚焦模式=\"沉浸\">\nLegacy scene.\n</聚焦模式>"
        val modern = PromptTemplates.narrativeModeHistoryReply("Modern scene.", ChatNarrativeMode.ADVANCE)
        for ((first, second, expected) in listOf(
            Triple(legacy, modern, "Legacy scene.\nModern scene."),
            Triple(modern, legacy, "Modern scene.\nLegacy scene."),
        )) {
            for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
                val firstBody = expected.substringBefore('\n')
                assertNarrativeReplyDeltas(
                    listOf(first, "\n${second.take(5)}", second.drop(5)), listOf(firstBody, firstBody, expected), expected,
                    experimental = experimental, toolCall = toolCall,
                )
            }
            assertMultipartNarrativeReply(listOf(first, second), expected)
        }
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
        assertNarrativeReplyStates(echoed, expected = echoed,
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
            val cleanup = requestWithNarrativeHistory(mode, null)
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
        assertNarrativeReplyStates(raw, expected = raw, experimental = false, fixtureHistory = false)
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
    fun `ordinary and tool replies remove sent headings inside an echoed pair`() = runBlocking {
        val story = "她推开了门。"
        for (mode in ChatNarrativeMode.entries) {
            val narrative = PromptTemplates.narrativeModeSystemPrompt(mode)
            val tail = PromptTemplates.narrativeModeTailSystemPrompt(mode, null)
            val previous = ChatMessage.create("fixture", MessageRole.ASSISTANT, "雨落在窗沿。").copy(narrativeMode = mode)
            val inner = "${tail.lineSequence().first()}\n$story\n${narrative.lineSequence().first()}"
            val wrapped = PromptTemplates.narrativeModeHistoryReply(inner, mode)
            for (toolCall in listOf(false, true)) {
                assertNarrativeReplyDeltas(
                    listOf(wrapped.substringBeforeLast('\n'), "\n</历史回复>"),
                    listOf(story, story), story, previousAssistant = previous,
                    experimental = toolCall, narrativePrompt = narrative, narrativeTail = tail, toolCall = toolCall,
                )
                val quoted = "她写下了“\n${tail.lineSequence().first()}\n”。"
                assertNarrativeReplyDeltas(
                    listOf(PromptTemplates.narrativeModeHistoryReply(quoted, mode)), listOf(quoted), quoted,
                    previousAssistant = previous, experimental = toolCall, narrativePrompt = narrative,
                    narrativeTail = tail, toolCall = toolCall,
                )
            }
        }
    }

    @Test
    fun `sent narrative heading is removed immediately before an eligible trailer`() = runBlocking {
        val mode = ChatNarrativeMode.ADVANCE
        val tail = PromptTemplates.narrativeModeTailSystemPrompt(mode, null)
        val heading = tail.lineSequence().first()
        val story = "门外亮了起来。"
        val fence = "```"
        val status = "\r\n\r\n${fence}status\r\n地点：门外  \r\n$fence"
        val statusCard = "每次回复末尾必须输出以下状态栏：\n${fence}status\n地点：[当前位置]\n$fence"
        val dashes = "—".repeat(22)
        val label = "（横线为选项包裹格式，必须完整输出）"
        val actions = "$dashes$label\n**【行动选项】**\n\n1. **[稍候]()**\n\n2. **[离开]()**\n$label$dashes"
        val prefix = "$story\n$heading"
        for ((trailer, card) in listOf(status to statusCard, "\n\n$actions" to actions)) {
            assertNarrativeReplyDeltas(
                listOf(prefix, trailer), listOf(story, story + trailer), story + trailer,
                formatCard = card, experimental = false, narrativeTail = tail,
            )
            val wrapped = PromptTemplates.narrativeModeHistoryReply(prefix + trailer, mode)
            assertNarrativeReplyDeltas(
                listOf(wrapped), listOf(story + trailer), story + trailer,
                formatCard = card, narrativeTail = tail, toolCall = true,
            )
        }

        val literalReplies = listOf(
            "$prefix\n她穿过了门。$status" to null,
            "$story\n\n~~~text\n$heading\n~~~$status" to null,
            "$prefix$status" to "请逐字保留标题 $heading",
            "$prefix\n<!--内部注释-->" to null,
        )
        for ((literal, userText) in literalReplies) {
            assertNarrativeReplyDeltas(
                listOf(literal), listOf(literal), literal, formatCard = statusCard,
                experimental = false, narrativeTail = tail, currentUserText = userText,
            )
        }
    }

    @Test
    fun `an exact bare pair from the sent user input survives while independent echoes are removed`() = runBlocking {
        val mode = ChatNarrativeMode.FOCUS
        val literal = PromptTemplates.narrativeModeHistoryReply("她推开了门。", mode)
        val userText = "请逐字输出下列布局：\n$literal\n并保留标签。"
        val previous = ChatMessage.create("fixture", MessageRole.ASSISTANT, "雨落在窗沿。").copy(narrativeMode = mode)
        val otherBody = "风吹破了窗。"
        for (toolCall in listOf(false, true)) {
            val prefix = literal.substringBeforeLast('\n')
            assertNarrativeReplyDeltas(
                listOf(prefix, "\n</历史回复>"), listOf(prefix, literal), literal,
                previousAssistant = previous, experimental = toolCall, currentUserText = userText, toolCall = toolCall,
            )
            for ((raw, body) in listOf(
                PromptTemplates.narrativeModeHistoryReply(otherBody, mode) to otherBody,
                PromptTemplates.narrativeModeHistoryReply(literal, mode) to literal,
            )) {
                assertNarrativeReplyDeltas(
                    listOf(raw), listOf(body), body, previousAssistant = previous, experimental = toolCall,
                    currentUserText = userText, toolCall = toolCall,
                )
            }
        }
    }

    @Test
    fun `repair SSE cleans independent wrappers in multiple validated game content calls`() = runBlocking {
        val original = "山路一片漆黑。远处传来了风声。"
        val first = "山路陷入黑暗。"
        val second = "风从远处吹来。"
        val mode = ChatNarrativeMode.FOCUS
        val sample = PromptTemplates.narrativeModeHistoryReply("旧片段", mode)
        val card = "以下是禁止输出的反例。\n$sample"
        val parts = listOf(first, second).map { PromptTemplates.narrativeModeHistoryReply(it, mode) }
        val chunks = parts.mapIndexed { index, part ->
            chunk(buildJsonObject { put("content", part) }.toString(), "game_content", index = index)
        }
        val cleanup = NarrativeHistoryEcho.forFormatRepair(card, original)
        GatedExperimentalResponse(
            sse(listOf(chunks.first())),
            sse(listOf(chunks.last(), chunk(reason = "tool_calls"), "[DONE]")),
        ).use { server ->
            val repair = MessageFormatRepairService(service())
            var received = ""
            var partLengths = emptyList<Int>()
            val events = withTimeout(5_000) {
                repair.streamRepair(original, card, null, model(server.baseUrl)).onEach { event ->
                    if (event is StreamEvent.Delta) {
                        received += event.text
                        partLengths = event.toolReplyPartLengths
                        if (received == parts.first()) {
                            assertFalse(server.tailSent)
                            assertEquals(first, cleanup.completedBody(received, streaming = true))
                            assertEquals(
                                first + original.drop(first.length),
                                MessageFormatRepairPolicy.progressiveOverlay(
                                    original, cleanup.completedBody(received, interrupted = true),
                                ),
                            )
                            server.releaseTail()
                        }
                    }
                }.toList()
            }
            val payload = Json.parseToJsonElement(server.requestBody).jsonObject
            val context = payload.getValue("tools").jsonArray.map { it.jsonObject.getValue("function").jsonObject }
                .single { it.getValue("name").jsonPrimitive.content == "context" }
                .getValue("description").jsonPrimitive.content
            assertTrue(Json.parseToJsonElement(context + "\"}]").jsonArray.any {
                card in it.jsonObject.getValue("content").jsonPrimitive.content
            })
            val expected = "$first\n$second"
            assertEquals(StreamEvent.Done, events.last())
            assertEquals(parts.joinToString("\n"), received)
            assertEquals(expected, cleanup.completedBody(received, partLengths = partLengths, streaming = true))
            assertEquals(expected, cleanup.completedBody(received, partLengths = partLengths, interrupted = true))
            assertEquals(expected, cleanup.completedBody(received, partLengths = partLengths))
            assertEquals(parts.map(String::length), partLengths)
            val declared = NarrativeHistoryEcho.forFormatRepair("输出模板：\n$sample", original)
            assertEquals(received, declared.completedBody(received, partLengths = partLengths))
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
        assertNarrativeReplyStates(
            "$story\n$close$footer", expected = "$story\n$close$footer", experimental = false, fixtureHistory = false,
        )
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

    @Test
    fun `serialized narrative sections clean the exact mode subtitle`() = runBlocking {
        val story = "她推开了门。"
        for (mode in ChatNarrativeMode.entries) {
            val built = PromptAssembler().assembleCachePromptLayers(CharacterCard.create("fixture"), narrativeMode = mode)
                .narrativeModeSystemPrompt
            val subtitle = PromptTemplates.narrativeModeSystemPrompt(mode).lineSequence().first()
            val tail = PromptTemplates.narrativeModeTailSystemPrompt(mode, null)
            val inner = "$subtitle\n$story"
            val wrapped = PromptTemplates.narrativeModeHistoryReply(inner, mode)
            val partialTitle = subtitle.substringBefore('（')
            for (toolCall in listOf(false, true)) {
                assertNarrativeReplyDeltas(
                    listOf(partialTitle, "${subtitle.removePrefix(partialTitle)}\n$story"),
                    listOf("", story), story, narrativePrompt = built, narrativeTail = tail,
                    experimental = toolCall, toolCall = toolCall,
                )
                assertNarrativeReplyDeltas(
                    listOf(wrapped), listOf(story), story, narrativePrompt = built, narrativeTail = tail,
                    experimental = toolCall, toolCall = toolCall,
                )
                assertNarrativeReplyDeltas(
                    listOf(inner), listOf(inner), inner, narrativePrompt = built, narrativeTail = tail,
                    currentUserText = "原样保留标题 $subtitle", experimental = toolCall, toolCall = toolCall,
                )
            }
        }
    }

    @Test
    fun `an exact single line pair from the sent user survives alongside a history projection`() = runBlocking {
        val mode = ChatNarrativeMode.FOCUS
        val literal = "<历史回复 模式=\"${mode.shortLabel}\">样张</历史回复>"
        val userText = "原样输出以下整行：\n$literal"
        val previous = ChatMessage.create("fixture", MessageRole.ASSISTANT, "雨落在窗沿。").copy(narrativeMode = mode)
        val prefix = literal.substringBefore("</")
        val differentBody = "另一页样张"
        val different = literal.replace("样张", differentBody)
        for (toolCall in listOf(false, true)) {
            assertNarrativeReplyDeltas(
                listOf(prefix, literal.removePrefix(prefix)), listOf(prefix, literal), literal,
                previousAssistant = previous, currentUserText = userText, experimental = toolCall, toolCall = toolCall,
            )
            assertNarrativeReplyDeltas(
                listOf(different), listOf(differentBody), differentBody, previousAssistant = previous,
                currentUserText = userText, experimental = toolCall, toolCall = toolCall,
            )
        }
    }

    @Test
    fun `a forbidden card example does not reserve the projected orphan close`() = runBlocking {
        val mode = ChatNarrativeMode.FOCUS
        val story = "她推开了门。"
        val previous = ChatMessage.create("fixture", MessageRole.ASSISTANT, "雨落在窗沿。").copy(narrativeMode = mode)
        val sample = PromptTemplates.narrativeModeHistoryReply("示例", mode)
        val close = sample.lineSequence().last()
        val partialClose = close.dropLast(1)
        val forbidden = "以下是禁止输出的反例："
        val bareCard = "$forbidden\n$sample"
        val fencedCard = "$forbidden\n```xml\n$sample\n```"
        val raw = "$story\n$close"
        for (toolCall in listOf(false, true)) {
            for (card in listOf(bareCard, fencedCard)) {
                assertNarrativeReplyDeltas(
                    listOf("$story\n$partialClose", close.removePrefix(partialClose)), listOf(story, story), story,
                    formatCard = card, previousAssistant = previous, experimental = toolCall, toolCall = toolCall,
                )
            }
            for (card in listOf("输出模板：\n$sample", "$bareCard\n\n保留书页文字 `$close`。")) {
                assertNarrativeReplyDeltas(
                    listOf(raw), listOf(raw), raw, formatCard = card, previousAssistant = previous,
                    experimental = toolCall, toolCall = toolCall,
                )
            }
            assertNarrativeReplyDeltas(
                listOf(raw), listOf(raw), raw, formatCard = bareCard, previousAssistant = previous,
                currentUserText = "保留字面标签 $close", experimental = toolCall, toolCall = toolCall,
            )
        }
    }

    @Test
    fun `repair SSE removes an outer pair while retaining the original literal close`() = runBlocking {
        val mode = ChatNarrativeMode.FOCUS
        val original = "纸上写着 `</历史回复>`。"
        val revised = "纸上印着 `</历史回复>`。"
        val sample = PromptTemplates.narrativeModeHistoryReply("示例", mode)
        val card = "以下是禁止输出的反例：\n$sample"
        val reply = PromptTemplates.narrativeModeHistoryReply(revised, mode)
        val prefix = reply.substringBeforeLast('\n')
        val suffix = reply.removePrefix(prefix)
        for (toolCall in listOf(false, true)) {
            val deltas = listOf(prefix, suffix).mapIndexed { index, body ->
                if (toolCall) {
                    val escaped = JsonPrimitive(body).toString().removeSurrounding("\"")
                    val fragment = if (index == 0) "{\"content\":\"$escaped" else "$escaped\"}"
                    chunk(fragment, "game_content".takeIf { index == 0 })
                } else buildJsonObject {
                    put("choices", buildJsonArray {
                        add(buildJsonObject { put("delta", buildJsonObject { put("content", body) }) })
                    })
                }.toString()
            }
            GatedExperimentalResponse(
                sse(listOf(deltas.first())),
                sse(listOf(deltas.last(), chunk(reason = if (toolCall) "tool_calls" else "stop"), "[DONE]")),
            ).use { server ->
                val transport = StreamingChatService(
                    allowCleartextHttp = { true }, experimentalApiModeEnabled = { toolCall },
                )
                val cleanup = NarrativeHistoryEcho.forFormatRepair(card, original)
                var received = ""
                val events = withTimeout(5_000) {
                    MessageFormatRepairService(transport).streamRepair(original, card, null, model(server.baseUrl))
                        .onEach { event ->
                            if (event is StreamEvent.Delta) {
                                received += event.text
                                if (received == prefix) {
                                    assertFalse(server.tailSent)
                                    server.releaseTail()
                                }
                            }
                        }.toList()
                }
                assertEquals(StreamEvent.Done, events.last())
                assertEquals(reply, received)
                for ((streaming, interrupted) in listOf(true to false, false to true)) {
                    val cleaned = cleanup.completedBody(prefix, streaming = streaming, interrupted = interrupted)
                    assertEquals(revised, MessageFormatRepairPolicy.progressiveOverlay(original, cleaned))
                }
                assertEquals(revised, cleanup.completedBody(received))
                val request = Json.parseToJsonElement(server.requestBody).jsonObject.toString()
                assertTrue(request.contains("禁止输出的反例"))
                assertTrue(request.contains(original))
            }
        }
    }

    @Test
    fun `only actually issued history syntax can remove a reply pair`() = runBlocking {
        val focus = ChatMessage.create("fixture", MessageRole.ASSISTANT, "雨落在窗沿。")
            .copy(narrativeMode = ChatNarrativeMode.FOCUS)
        val body = "她推开了门。"
        val focusWrapped = PromptTemplates.narrativeModeHistoryReply(body, ChatNarrativeMode.FOCUS)
        val advanceWrapped = PromptTemplates.narrativeModeHistoryReply(body, ChatNarrativeMode.ADVANCE)
        val prompt = PromptAssembler().assembleCachePromptLayers(
            CharacterCard.create("fixture"), narrativeMode = ChatNarrativeMode.ADVANCE,
        ).narrativeModeSystemPrompt
        for (toolCall in listOf(false, true)) {
            assertNarrativeReplyDeltas(
                listOf(focusWrapped), listOf(focusWrapped), focusWrapped,
                experimental = toolCall, toolCall = toolCall, fixtureHistory = false, narrativePrompt = prompt,
            )
            assertNarrativeReplyDeltas(
                listOf(advanceWrapped), listOf(advanceWrapped), advanceWrapped,
                previousAssistant = focus, experimental = toolCall, toolCall = toolCall, narrativePrompt = prompt,
            )
            assertNarrativeReplyDeltas(
                listOf(focusWrapped), listOf(body), body,
                previousAssistant = focus, experimental = toolCall, toolCall = toolCall, narrativePrompt = prompt,
            )
        }
    }

    @Test
    fun `an exact earlier user pair survives when a later user message follows it`() = runBlocking {
        val mode = ChatNarrativeMode.FOCUS
        val previous = ChatMessage.create("fixture", MessageRole.ASSISTANT, "雨落在窗沿。").copy(narrativeMode = mode)
        val literal = PromptTemplates.narrativeModeHistoryReply("作者样张。", mode)
        val earlierUser = "之后请原样输出下列样张。\n$literal"
        val latestUser = "现在输出。"
        for (toolCall in listOf(false, true)) {
            assertNarrativeReplyDeltas(
                listOf(literal), listOf(literal), literal,
                previousAssistant = previous, experimental = toolCall, toolCall = toolCall,
                previousUserText = earlierUser, currentUserText = latestUser,
            )
        }
    }

    @Test
    fun `issued wrapper and heading both leave a recognized status trailer intact`() = runBlocking {
        for ((mode, experimental, toolCall) in listOf(
            Triple(ChatNarrativeMode.FOCUS, false, false),
            Triple(ChatNarrativeMode.ADVANCE, true, false),
            Triple(ChatNarrativeMode.FOCUS, true, true),
        )) {
            val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "早先的故事。").copy(narrativeMode = mode)
            val body = "门外的灯亮了。"
            val wrapped = PromptTemplates.narrativeModeHistoryReply(body, mode)
            val tail = PromptTemplates.narrativeModeTailSystemPrompt(mode, mode)
            val heading = tail.lineSequence().first()
            val status = "${MarkdownCodeFences.legacyStatusOpening}\n时间：晚上\n${MarkdownCodeFences.legacyStatusLabel}```"
            val raw = "$wrapped\n$heading\n$status"
            val expected = "$body\n$status"
            assertNarrativeReplyDeltas(
                listOf(raw), listOf(expected), expected,
                previousAssistant = prior, currentUserText = "继续故事。", narrativeTail = tail,
                experimental = experimental, toolCall = toolCall,
            )
            val close = wrapped.substringAfterLast('\n')
            for (orphan in listOf("$body\n$close\n$heading\n$status", "$body\n$heading\n$close\n$status")) {
                assertNarrativeReplyDeltas(
                    listOf(orphan), listOf(expected), expected,
                    previousAssistant = prior, currentUserText = "继续故事。", narrativeTail = tail,
                    experimental = experimental, toolCall = toolCall,
                )
            }
            val quote = "$wrapped\n> $heading\n$status"
            assertNarrativeReplyDeltas(
                listOf(quote), listOf(quote), quote,
                previousAssistant = prior, currentUserText = "继续故事。", narrativeTail = tail,
                experimental = experimental, toolCall = toolCall,
            )
            if (toolCall) {
                val continuation = "灯光照亮了石阶。"
                assertMultipartNarrativeReply(
                    listOf(raw, continuation), "$expected\n$continuation", narrativePrompt = tail,
                )
            }
        }
    }

    @Test
    fun `an exact user pair with a lightly indented opening is preserved`() = runBlocking {
        val mode = ChatNarrativeMode.FOCUS
        val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "早先的故事。").copy(narrativeMode = mode)
        val pair = PromptTemplates.narrativeModeHistoryReply("示例原文。", mode)
        val literals = listOf("  $pair", pair.prependIndent("  "))
        for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
            for (literal in literals) {
                val request = "请逐字复述下面三行，保留标签和缩进。\n$literal"
                assertNarrativeReplyDeltas(
                    listOf(literal), listOf(literal), literal, previousAssistant = prior, currentUserText = request,
                    experimental = experimental, toolCall = toolCall,
                )
                val body = "门外的灯亮了。"
                val echo = "  ${PromptTemplates.narrativeModeHistoryReply(body, mode)}"
                assertNarrativeReplyDeltas(
                    listOf(echo), listOf(body), body, previousAssistant = prior, currentUserText = request,
                    experimental = experimental, toolCall = toolCall,
                )
            }
        }
    }

    @Test
    fun `ordinary SSE keeps a requested exact inline history pair`() = runBlocking {
        assertExactInlineHistoryCopy(experimental = false, toolCall = false)
    }

    @Test
    fun `experimental ordinary fallback keeps a requested exact inline history pair`() = runBlocking {
        assertExactInlineHistoryCopy(experimental = true, toolCall = false)
    }

    @Test
    fun `experimental tool SSE keeps a requested exact inline history pair`() = runBlocking {
        assertExactInlineHistoryCopy(experimental = true, toolCall = true)
    }

    @Test
    fun `ordinary SSE keeps an explicitly requested quoted template`() = runBlocking {
        assertQuotedTemplate(experimental = false, toolCall = false)
    }

    @Test
    fun `ordinary fallback keeps an explicitly requested quoted template`() = runBlocking {
        assertQuotedTemplate(experimental = true, toolCall = false)
    }

    @Test
    fun `tool SSE keeps an explicitly requested quoted template`() = runBlocking {
        assertQuotedTemplate(experimental = true, toolCall = true)
    }

    private suspend fun assertQuotedTemplate(experimental: Boolean, toolCall: Boolean) {
        val mode = ChatNarrativeMode.FOCUS
        val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "Earlier story").copy(narrativeMode = mode)
        val instruction = "Use exactly the following XML template; replace [BODY] with new prose and keep both tags:"
        val template = PromptTemplates.narrativeModeHistoryReply("[BODY]", mode)
        val quote = template.prependIndent("> ")
        val story = "甲推开门。"
        val reply = PromptTemplates.narrativeModeHistoryReply(story, mode)
        val prefix = reply.substringBeforeLast('\n')
        for (sample in listOf(quote, template)) {
            assertNarrativeReplyDeltas(
                listOf(prefix, reply.removePrefix(prefix)), listOf(prefix, reply), reply,
                previousAssistant = prior, currentUserText = "$instruction\n$sample",
                experimental = experimental, toolCall = toolCall,
            )
        }
        for (request in listOf(
            "Here is an illustration.\n$quote",
            "Do not output these tags.\n$instruction\n$quote",
            "$instruction\n$quote\nDo not output these tags.",
            "$instruction\n$quote\n\nDo not output these tags.",
            "$instruction\n${quote.replace("[BODY]", "[OTHER]")}",
            "$instruction\n$quote\n> This is part of the quoted block.",
            "$instruction\n${quote.prependIndent("> ")}",
        )) {
            assertNarrativeReplyDeltas(
                listOf(reply), listOf(story), story, previousAssistant = prior, currentUserText = request,
                experimental = experimental, toolCall = toolCall,
            )
        }
    }

    @Test
    fun `ordinary SSE keeps a literal closing tag inside a lazy list item`() = runBlocking {
        assertLazyListClosing(experimental = false, toolCall = false)
    }

    @Test
    fun `ordinary fallback keeps a literal closing tag inside a lazy list item`() = runBlocking {
        assertLazyListClosing(experimental = true, toolCall = false)
    }

    @Test
    fun `tool SSE keeps a literal closing tag inside a lazy list item`() = runBlocking {
        assertLazyListClosing(experimental = true, toolCall = true)
    }

    @Test
    fun `multipart tool SSE keeps a literal closing tag inside a lazy list item`(): Unit = runBlocking {
        val lead = "- The closing tag in this example is shown below"
        val closing = "</历史回复>"
        assertMultipartNarrativeReply(listOf(lead, closing), "$lead\n$closing")
    }

    private suspend fun assertLazyListClosing(experimental: Boolean, toolCall: Boolean) {
        val mode = ChatNarrativeMode.FOCUS
        val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "Earlier story").copy(narrativeMode = mode)
        val lead = "- The closing tag in this example is shown below"
        val closing = "</历史回复>"
        val literal = "$lead\n$closing"
        val partial = closing.dropLast(1)
        assertNarrativeReplyDeltas(
            listOf(lead, "\n$partial", closing.takeLast(1)), listOf(lead, "$lead\n$partial", literal), literal,
            previousAssistant = prior, experimental = experimental, toolCall = toolCall,
        )
        val narration = "The lantern went out."
        for ((raw, expected) in listOf(
            "$narration\n$closing" to narration,
            PromptTemplates.narrativeModeHistoryReply("- $narration", mode) to "- $narration",
        )) {
            assertNarrativeReplyDeltas(
                listOf(raw), listOf(expected), expected,
                previousAssistant = prior, experimental = experimental, toolCall = toolCall,
            )
        }
    }

    private suspend fun assertExactInlineHistoryCopy(experimental: Boolean, toolCall: Boolean) {
        val body = "Example text"
        for (mode in ChatNarrativeMode.entries) {
            val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "Earlier story").copy(narrativeMode = mode)
            val wrapped = PromptTemplates.narrativeModeHistoryReply(body, mode)
            val opening = wrapped.substringBefore('\n')
            val closing = wrapped.substringAfterLast('\n')
            val literal = "$opening$body$closing"
            val userRequest = "Copy this text exactly, with both tags: $literal"
            for (exactRequest in listOf(userRequest, "请原样复制以下文字并保留首尾标签：$literal")) {
                assertNarrativeReplyDeltas(
                    listOf(literal), listOf(literal), literal,
                    previousAssistant = prior, currentUserText = exactRequest,
                    experimental = experimental, toolCall = toolCall,
                )
            }
            val partialBody = opening + body.take(4)
            val prefix = literal.dropLast(1)
            assertNarrativeReplyDeltas(
                listOf(partialBody, prefix.drop(partialBody.length), literal.takeLast(1)),
                listOf(partialBody, prefix, literal), literal,
                previousAssistant = prior, currentUserText = userRequest,
                experimental = experimental, toolCall = toolCall,
            )
        }
    }

    @Test
    fun `inline examples and different replies remain eligible for projected history cleanup`() = runBlocking {
        val body = "Example text"
        val wrapped = PromptTemplates.narrativeModeHistoryReply(body, ChatNarrativeMode.FOCUS)
        val literal = wrapped.replace("\n", "")
        val instruction = "Copy this text exactly, with both tags: $literal"
        for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
            for (request in listOf(
                "This is what the history metadata looks like: $literal",
                "She quoted:\n“\n$instruction\n”",
                "$instruction This is an illustration.",
                "$instruction\nDo not output these tags.",
            )) {
                assertNarrativeReplyDeltas(
                    listOf(literal), listOf(body), body, currentUserText = request,
                    experimental = experimental, toolCall = toolCall,
                )
            }
            for ((echo, expected) in listOf(
                wrapped to body,
                literal.replace(body, "Different text") to "Different text",
                literal.replace(body, "Different text").dropLast(1) to "Different text",
            )) {
                assertNarrativeReplyDeltas(
                    listOf(echo), listOf(expected), expected, currentUserText = instruction,
                    experimental = experimental, toolCall = toolCall,
                )
            }
        }
    }

    @Test
    fun `validated call boundaries clean issued headings`(): Unit = runBlocking {
        val mode = ChatNarrativeMode.FOCUS
        val prompt = PromptAssembler().assembleCachePromptLayers(
            CharacterCard.create("fixture"), narrativeMode = mode,
        ).narrativeModeSystemPrompt
        val heading = PromptTemplates.narrativeModeSystemPrompt(mode).lineSequence().first()
        assertMultipartNarrativeReply(
            listOf("甲段。", "$heading\n乙段。", "丙段。"), "甲段。\n乙段。\n丙段。", narrativePrompt = prompt,
        )
        assertMultipartNarrativeReply(
            listOf("甲段。", heading, "乙段。"), "甲段。\n乙段。", narrativePrompt = prompt,
        )
        val literalParts = listOf("示例：\n~~~text", "$heading\n乙段。", "~~~\n丙段。")
        assertMultipartNarrativeReply(literalParts, literalParts.joinToString("\n"), narrativePrompt = prompt)
    }

    @Test
    fun `a validated standalone closing call ends its wrapper before ordinary prose`(): Unit = runBlocking {
        val wrapped = PromptTemplates.narrativeModeHistoryReply("甲段。", ChatNarrativeMode.FOCUS)
        assertMultipartNarrativeReply(
            listOf(wrapped.substringBeforeLast('\n'), wrapped.substringAfterLast('\n'), "乙段。"),
            "甲段。\n乙段。",
        )
    }

    @Test
    fun `a standalone wrapper boundary in a closing call can precede ordinary prose`(): Unit = runBlocking {
        val opening = "<历史回复 模式=\"聚焦\">"
        val closing = "</历史回复>"
        val parts = listOf("$opening\n她打开门。", "灯光照进来。\n$closing", "他走了过去。")
        assertMultipartNarrativeReply(parts, "她打开门。\n灯光照进来。\n他走了过去。")
        val example = parts.dropLast(1) + "这是一个标签示例。"
        assertMultipartNarrativeReply(example, example.joinToString("\n"))
    }

    @Test
    fun `an explicitly requested exact XML copy keeps both tags`() = runBlocking {
        val previous = ChatMessage.create("fixture", MessageRole.ASSISTANT, "旧故事。")
        val literal = "<历史回复 模式=\"聚焦\">sample</历史回复>"
        for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
            assertNarrativeReplyDeltas(
                listOf(literal), listOf(literal), literal,
                previousAssistant = previous, currentUserText = "Please copy the XML below verbatim: $literal",
                experimental = experimental, toolCall = toolCall,
            )
            val different = literal.replace("sample", "story")
            assertNarrativeReplyDeltas(
                listOf(different), listOf("story"), "story",
                previousAssistant = previous, currentUserText = "Please copy the XML below verbatim: $literal",
                experimental = experimental, toolCall = toolCall,
            )
        }
    }

    @Test
    fun `an adjacent exact copy instruction protects a multiline pair with an inline close`() = runBlocking {
        val literal = "<历史回复 模式=\"推进\">\n例子</历史回复>"
        val instruction = "请逐字照抄以下文本并保留首尾标签："
        for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
            assertNarrativeReplyDeltas(listOf(literal), listOf(literal), literal,
                currentUserText = "$instruction\n$literal", experimental = experimental, toolCall = toolCall)
            val unrelated = literal.replace("例子", "故事")
            assertNarrativeReplyDeltas(listOf(unrelated), listOf("故事"), "故事",
                currentUserText = "$instruction\n$literal", experimental = experimental, toolCall = toolCall)
            assertNarrativeReplyDeltas(listOf(literal), listOf("例子"), "例子",
                currentUserText = "下面是参考示例。\n$literal", experimental = experimental, toolCall = toolCall)
        }
    }

    @Test
    fun `a verbatim multiline reply keeps outer authored tags around an inline code closer over real SSE`(): Unit = runBlocking {
        val closing = "</历史回复>"
        for (mode in listOf(ChatNarrativeMode.FOCUS, ChatNarrativeMode.ADVANCE)) {
            val opening = "<历史回复 模式=\"${mode.shortLabel}\">"
            val prefix = "$opening\nThe closing-tag token is `"
            val requested = "$prefix$closing`.\n$closing"
            val request = "Copy this text verbatim:\n$requested"
            val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "Earlier story.").copy(narrativeMode = mode)
            for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
                assertNarrativeReplyDeltas(
                    listOf(prefix, closing, "`.\n${closing.dropLast(1)}", ">"),
                    listOf(prefix, prefix + closing, requested.dropLast(1), requested), requested,
                    previousAssistant = prior, currentUserText = request, experimental = experimental, toolCall = toolCall,
                )
                assertNarrativeReplyDeltas(
                    listOf("$opening\n$requested\n$closing"), listOf(requested), requested,
                    previousAssistant = prior, currentUserText = request, experimental = experimental, toolCall = toolCall,
                )
            }
            val different = "The literal marker is `$closing`."
            assertNarrativeReplyStates(
                "$opening\n$different\n$closing", different, previousAssistant = prior,
                currentUserText = request, experimental = false,
            )
        }
    }

    @Test
    fun `a same line copy instruction preserves the exact pair across a split inline code closer`(): Unit = runBlocking {
        val close = "</历史回复>"
        for (mode in listOf(ChatNarrativeMode.FOCUS, ChatNarrativeMode.ADVANCE)) {
            val open = "<历史回复 模式=\"${mode.shortLabel}\">"
            val prefix = "${open}Use `"
            val body = "Use `$close` here"
            val literal = "$open$body$close"
            val user = "Copy this verbatim: $literal"
            val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "Earlier scene.").copy(narrativeMode = mode)
            for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
                assertNarrativeReplyDeltas(
                    listOf(prefix, close, "` here${close.dropLast(1)}", ">"),
                    listOf(prefix, prefix + close, literal.dropLast(1), literal), literal,
                    previousAssistant = prior, currentUserText = user, experimental = experimental, toolCall = toolCall,
                )
                assertNarrativeReplyDeltas(listOf("$open\n$literal\n$close"), listOf(literal), literal,
                    previousAssistant = prior, currentUserText = user, experimental = experimental, toolCall = toolCall)
            }
            assertNarrativeReplyStates(literal, body, previousAssistant = prior, experimental = false,
                currentUserText = "Here is a reference: $literal")
            val other = body.replace("here", "elsewhere")
            assertNarrativeReplyStates("$open$other$close", other, previousAssistant = prior, experimental = false, currentUserText = user)
        }
    }

    @Test
    fun `adjacent transport and exact authored frames each keep their own boundary meaning`(): Unit = runBlocking {
        for (mode in listOf(ChatNarrativeMode.FOCUS, ChatNarrativeMode.ADVANCE)) {
            val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "旧文。").copy(narrativeMode = mode)
            val transport = PromptTemplates.narrativeModeHistoryReply(prior.displayContent, mode)
            val authored = PromptTemplates.narrativeModeHistoryReply("须保留。", mode)
            val user = "Copy this verbatim:\n$authored"
            for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
                val expected = "旧文。\n$authored"
                assertNarrativeReplyDeltas(
                    listOf(transport, "\n$authored"), listOf("旧文。", expected), expected,
                    previousAssistant = prior, currentUserText = user, experimental = experimental, toolCall = toolCall,
                )
                val reverse = "$authored\n旧文。"
                assertNarrativeReplyDeltas(
                    listOf(authored, "\n$transport"), listOf(authored, reverse), reverse,
                    previousAssistant = prior, currentUserText = user, experimental = experimental, toolCall = toolCall,
                )
            }
            val uncertain = "$transport\n$authored\nThis continues the same passage."
            assertNarrativeReplyStates(uncertain, uncertain, previousAssistant = prior, experimental = false, currentUserText = user)
        }
    }

    @Test
    fun `a verbatim directive on an opening line can reserve the following multiline pair`(): Unit = runBlocking {
        val close = "</历史回复>"
        for (mode in listOf(ChatNarrativeMode.FOCUS, ChatNarrativeMode.ADVANCE)) {
            val open = "<历史回复 模式=\"${mode.shortLabel}\">"
            val body = "Keep this exactly."
            val literal = "$open\n$body\n$close"
            val request = "Copy the following text verbatim: $literal"
            val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "Earlier scene.").copy(narrativeMode = mode)
            for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
                val prefix = "$open\n${body.take(6)}"
                assertNarrativeReplyDeltas(
                    listOf(prefix, literal.dropLast(1).drop(prefix.length), ">"), listOf(prefix, literal.dropLast(1), literal), literal,
                    previousAssistant = prior, currentUserText = request, experimental = experimental, toolCall = toolCall,
                )
            }
            assertNarrativeReplyStates(literal, body, previousAssistant = prior, experimental = false,
                currentUserText = "Here is a reference: $literal")
            assertNarrativeReplyStates(literal, body, previousAssistant = prior, experimental = false,
                currentUserText = "Do not output the following history tags:\n$request")
            assertNarrativeReplyStates("$open\n$literal\n$close", literal, previousAssistant = prior, experimental = false,
                currentUserText = request)
        }
    }

    @Test
    fun `a sent prohibited format example supplies orphan closing evidence without prior history`(): Unit = runBlocking {
        val body = "It begins now."
        val close = "</历史回复>"
        val reply = "$body\n$close"
        for (mode in listOf(ChatNarrativeMode.FOCUS, ChatNarrativeMode.ADVANCE)) {
            val example = PromptTemplates.narrativeModeHistoryReply("wrong", mode)
            val card = "The following is a counterexample:\n$example\nDo not output these history tags in your reply."
            for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
                assertNarrativeReplyDeltas(
                    listOf("$body\n${close.dropLast(1)}", ">"), listOf(body, body), body,
                    formatCard = card, fixtureHistory = false, experimental = experimental, toolCall = toolCall,
                )
            }
            assertNarrativeReplyStates(reply, reply, experimental = false, fixtureHistory = false)
            assertNarrativeReplyStates(reply, reply, experimental = false, fixtureHistory = false, formatCard = "Reference values only: $example")
            assertNarrativeReplyStates(reply, reply, experimental = false, fixtureHistory = false,
                formatCard = card, currentUserText = "The footer may contain the literal tag `$close`.")
            assertNarrativeReplyStates(reply, reply, experimental = false, fixtureHistory = false,
                formatCard = "$card\nThe example marker is also written here for reference: `$close`.")
        }
    }

    @Test
    fun `current strong suffix reserves standalone literal tags without promoting historic examples`(): Unit = runBlocking {
        val close = "</历史回复>"
        for (mode in listOf(ChatNarrativeMode.FOCUS, ChatNarrativeMode.ADVANCE)) {
            val open = "<历史回复 模式=\"${mode.shortLabel}\">"
            val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "Earlier scene.").copy(narrativeMode = mode)
            val openerInstruction = "Show the literal opening tag only on its own line, then explain it:\n$open"
            val explanation = "This is the opening tag."
            val requested = "$open\n$explanation"
            val closeInstruction = "Show the literal closing tag on its own line:\n$close"
            val caption = "Closing tag example:\n$close"
            for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
                assertNarrativeReplyDeltas(
                    listOf(open, "\n$explanation"), listOf(open, requested), requested,
                    previousAssistant = prior, currentUserText = "Please explain.", strongPrompt = openerInstruction,
                    experimental = experimental, toolCall = toolCall,
                )
                assertNarrativeReplyDeltas(
                    listOf("$open\n$caption\n$close"), listOf(caption), caption,
                    previousAssistant = prior, currentUserText = "Please explain.", strongPrompt = closeInstruction,
                    experimental = experimental, toolCall = toolCall,
                )
                assertNarrativeReplyDeltas(
                    listOf("$open\nA new scene.\n$close"), listOf("A new scene."), "A new scene.",
                    previousAssistant = prior, strongPrompt = openerInstruction, experimental = experimental, toolCall = toolCall,
                )
            }
            for ((oldInstruction, suffix) in listOf(openerInstruction to null, null to "Reference opener only:\n$open")) {
                assertNarrativeReplyDeltas(listOf(requested), listOf(explanation), explanation,
                    previousAssistant = prior, previousUserText = oldInstruction, strongPrompt = suffix, experimental = false)
            }
        }
    }

    @Test
    fun `padding before a real newline keeps the witnessed opener on its own line`(): Unit = runBlocking {
        for (mode in listOf(ChatNarrativeMode.FOCUS, ChatNarrativeMode.ADVANCE)) {
            val open = "<历史回复 模式=\"${mode.shortLabel}\">"
            val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "Earlier scene.").copy(narrativeMode = mode)
            val body = "A new scene begins."
            for (newline in listOf("\n", "\r\n")) {
                val framed = "$open \t$newline$body"
                for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
                    assertNarrativeReplyDeltas(listOf(framed), listOf(body), body, previousAssistant = prior,
                        experimental = experimental, toolCall = toolCall, finishReason = "length")
                }
                assertNarrativeReplyStates(framed, framed, previousAssistant = prior, experimental = false,
                    currentUserText = "Show the literal opening tag only on its own line, then explain it:\n$open")
            }
            val inline = "$open \t$body"
            assertNarrativeReplyStates(inline, inline, previousAssistant = prior, experimental = false)
        }
    }

    @Test
    fun `current explicit counterexample supersedes only the matching historical user template`(): Unit = runBlocking {
        for ((mode, other) in listOf(ChatNarrativeMode.FOCUS to ChatNarrativeMode.ADVANCE, ChatNarrativeMode.ADVANCE to ChatNarrativeMode.FOCUS)) {
            val sample = PromptTemplates.narrativeModeHistoryReply("[story]", mode)
            val earlier = "Use exactly this XML shape, replace [story] with new prose and keep both tags:\n$sample"
            val negative = "Do not output the following history tags:\n$sample"
            val body = "A new scene begins."
            val framed = PromptTemplates.narrativeModeHistoryReply(body, mode)
            val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "Earlier scene.").copy(narrativeMode = mode)
            for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
                for ((reply, expected) in listOf(framed to body, sample to "[story]")) {
                    assertNarrativeReplyDeltas(listOf(reply), listOf(expected), expected,
                        previousAssistant = prior, previousUserText = earlier, currentUserText = negative,
                        experimental = experimental, toolCall = toolCall)
                }
            }
            for (current in listOf(null, "Continue the scene.", "Prefer natural prose.",
                "Do not output the following history tags:\n${PromptTemplates.narrativeModeHistoryReply("Wrong mode", other)}")) {
                assertNarrativeReplyDeltas(listOf(framed), listOf(framed), framed, previousAssistant = prior,
                    previousUserText = earlier, currentUserText = current, experimental = false)
            }
            assertNarrativeReplyDeltas(listOf(framed), listOf(framed), framed, previousAssistant = prior,
                previousUserText = earlier, currentUserText = negative, formatCard = "Output template:\n$sample", experimental = false)
            assertNarrativeReplyStates(framed, framed, experimental = false, fixtureHistory = false, currentUserText = negative)
        }
    }

    @Test
    fun `a main chat literal closing occurrence does not reserve a separate witnessed orphan`() = runBlocking {
        assertSeparateLiteralAndOrphan(repair = false)
    }

    @Test
    fun `a format repair literal closing occurrence does not reserve a separate witnessed orphan`() = runBlocking {
        assertSeparateLiteralAndOrphan(repair = true)
    }

    private suspend fun assertSeparateLiteralAndOrphan(repair: Boolean) {
        val close = "</历史回复>"
        val original = "屏幕显示 `$close`。旧句。"
        val improved = "屏幕显示 `$close`。新句。"
        for (mode in listOf(ChatNarrativeMode.FOCUS, ChatNarrativeMode.ADVANCE)) {
            val sample = PromptTemplates.narrativeModeHistoryReply("示例正文", mode)
            val card = "以下是禁止输出的反例：\n$sample"
            val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "Earlier scene.").copy(narrativeMode = mode)
            val cases = listOf(
                "$improved\n$close" to improved,
                "新句。\n$close" to "新句。\n$close",
                "$improved 又显示 `$close`。\n$close" to "$improved 又显示 `$close`。\n$close",
            )
            if (!repair) {
                val repeatedInput = "Please preserve both examples `$close` and `$close`."
                val raw = "$improved\n$close"
                assertNarrativeReplyDeltas(listOf(raw), listOf(raw), raw, previousAssistant = prior,
                    currentUserText = repeatedInput, experimental = false)
            }
            for ((raw, expected) in cases) {
                for (tool in listOf(false, true)) {
                    if (!repair) {
                        assertNarrativeReplyDeltas(listOf(raw), listOf(expected), expected, previousAssistant = prior,
                            currentUserText = "Please preserve the literal token `$close`.", experimental = tool, toolCall = tool)
                        continue
                    }
                    val sseHead = if (tool) chunk(buildJsonObject { put("content", raw) }.toString(), "game_content") else ordinaryDelta(raw)
                    val tail = sse(listOf(chunk(reason = if (tool) "tool_calls" else "stop"), "[DONE]"))
                    GatedExperimentalResponse(sse(listOf(sseHead)), tail).use { server ->
                        val transport = StreamingChatService(experimentalApiModeEnabled = { tool }, allowCleartextHttp = { true })
                        val events = withTimeout(5_000) {
                            MessageFormatRepairService(transport).streamRepair(original, card, null, model(server.baseUrl)).onEach {
                                if (it == StreamEvent.Delta(raw)) {
                                    assertFalse(server.tailSent)
                                    server.releaseTail()
                                }
                            }.toList()
                        }
                        assertEquals(StreamEvent.Done, events.last())
                        val received = events.filterIsInstance<StreamEvent.Delta>().joinToString("") { it.text }
                        assertEquals(raw, received)
                        val cleanup = NarrativeHistoryEcho.forFormatRepair(card, original)
                        assertEquals(expected, cleanup.completedBody(received, streaming = true))
                        assertEquals(expected, cleanup.completedBody(received, interrupted = true))
                        assertEquals(expected, cleanup.completedBody(received))
                        val repeatedSource = NarrativeHistoryEcho.forFormatRepair(card, "$original 再显示 `$close`。")
                        assertEquals(raw, repeatedSource.completedBody(received))
                    }
                }
            }
        }
    }

    @Test
    fun `projected orphan padding is distinct from Markdown code indentation`(): Unit = runBlocking {
        val previous = ChatMessage.create("fixture", MessageRole.ASSISTANT, "旧故事。")
        val closing = "</历史回复>"
        val story = "故事就此结束。"
        for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
            for (padding in 0..3) {
                val padded = "$story\n${" ".repeat(padding)}$closing"
                assertNarrativeReplyDeltas(
                    listOf(padded), listOf(story), story,
                    previousAssistant = previous, experimental = experimental, toolCall = toolCall,
                )
            }
            val code = "$story\n\n    $closing"
            assertNarrativeReplyDeltas(
                listOf(code), listOf(code), code,
                previousAssistant = previous, experimental = experimental, toolCall = toolCall,
            )
        }
    }

    @Test
    fun `a witnessed nested history projection can contain different modes`() = runBlocking {
        val previous = ChatMessage.create(
            "fixture", MessageRole.ASSISTANT,
            PromptTemplates.narrativeModeHistoryReply("旧故事。", ChatNarrativeMode.FOCUS),
        ).copy(narrativeMode = ChatNarrativeMode.ADVANCE)
        val story = "她推开窗。"
        val inner = PromptTemplates.narrativeModeHistoryReply(story, ChatNarrativeMode.FOCUS)
        val outer = PromptTemplates.narrativeModeHistoryReply(inner, ChatNarrativeMode.ADVANCE)
        for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
            assertNarrativeReplyDeltas(
                listOf(outer), listOf(story), story,
                previousAssistant = previous, experimental = experimental, toolCall = toolCall,
            )
            assertNarrativeReplyDeltas(
                listOf(outer), listOf(inner), inner,
                experimental = experimental, toolCall = toolCall,
            )
            assertNarrativeReplyDeltas(
                listOf(outer), listOf(inner), inner,
                previousAssistant = previous, experimental = experimental, toolCall = toolCall,
                formatCard = "本轮输出模板：\n$inner",
            )
        }
    }

    @Test
    fun `an explicit user output template keeps its tags around replaced story text`() = runBlocking {
        val mode = ChatNarrativeMode.FOCUS
        val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "雨落在窗沿。").copy(narrativeMode = mode)
        val sample = PromptTemplates.narrativeModeHistoryReply("[story]", mode)
        val story = "她推开门。"
        val wrapped = PromptTemplates.narrativeModeHistoryReply(story, mode)
        val opening = wrapped.substringBefore('\n')
        val rest = wrapped.substring(opening.length)
        for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
            val declarations = listOf(
                "Use exactly this XML shape, replace [story] with new prose and keep both tags:",
                "请使用以下 XML 模板，将[story]替换为新正文并保留首尾标签：",
            )
            for (declaration in declarations) {
                assertNarrativeReplyDeltas(
                    listOf(opening, rest), listOf(opening, wrapped), wrapped,
                    previousAssistant = prior, experimental = experimental, toolCall = toolCall,
                    currentUserText = "$declaration\n$sample",
                )
            }
            for (reference in listOf(
                "Here is a historical XML sample for reference:",
                declarations.first().replace("[story]", "[another]"),
            )) {
                assertNarrativeReplyDeltas(
                    listOf(wrapped), listOf(story), story,
                    previousAssistant = prior, experimental = experimental, toolCall = toolCall,
                    currentUserText = "$reference\n$sample",
                )
            }
        }
    }

    @Test
    fun `a format card can explicitly require the tags in an unfenced output`() = runBlocking {
        val mode = ChatNarrativeMode.ADVANCE
        val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "雨落在窗沿。").copy(narrativeMode = mode)
        val sample = PromptTemplates.narrativeModeHistoryReply("[story]", mode)
        val wrapped = PromptTemplates.narrativeModeHistoryReply("她到达码头。", mode)
        val fencedSample = "Output format:\n```xml\n$sample\n```"
        val format = "Preserve the opening and closing tags exactly as written. Do not include the code fence.\n$fencedSample"
        for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
            assertNarrativeReplyDeltas(
                listOf(wrapped), listOf(wrapped), wrapped, formatCard = format,
                previousAssistant = prior, experimental = experimental, toolCall = toolCall,
            )
            val body = wrapped.lineSequence().elementAt(1)
            assertNarrativeReplyDeltas(
                listOf(wrapped), listOf(body), body, formatCard = "The sample is a reference.\n$fencedSample",
                previousAssistant = prior, experimental = experimental, toolCall = toolCall,
            )
        }
    }

    @Test
    fun `a direct format card instruction can declare an exact fenced history pair without a fixed label`() = runBlocking {
        val mode = ChatNarrativeMode.ADVANCE
        val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "雨落在窗沿。").copy(narrativeMode = mode)
        val sample = PromptTemplates.narrativeModeHistoryReply("[story]", mode)
        val story = "她到达码头。"
        val wrapped = PromptTemplates.narrativeModeHistoryReply(story, mode)
        val opening = wrapped.substringBefore('\n')
        val rest = wrapped.substring(opening.length)
        val declaration = "Use this XML on every reply. " +
            "Keep the opening and closing tags verbatim and omit the surrounding fence."
        val declarations = listOf(
            declaration,
            "Preserve both tags exactly as shown in your answer.\nDo not output the code fence.",
            "务必原样保留首尾标签，不得输出代码围栏。",
        )
        for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
            for (instruction in declarations) {
                val card = "### Required reply format\n$instruction\n\n```xml\n$sample\n```"
                assertNarrativeReplyDeltas(
                    listOf(opening, rest), listOf(opening, wrapped), wrapped, formatCard = card,
                    previousAssistant = prior, experimental = experimental, toolCall = toolCall,
                )
            }
            for ((instruction, sampleText) in listOf(
                "The following history markup is only a reference." to sample,
                "The old manual says: \"$declaration\"" to sample,
                "> $declaration" to sample,
                "```text\n$declaration\n```" to sample,
                declaration to sample.replace("[story]", "A previously written scene."),
            )) {
                val card = "### Reference\n$instruction\n\n```xml\n$sampleText\n```"
                assertNarrativeReplyDeltas(
                    listOf(wrapped), listOf(story), story, formatCard = card,
                    previousAssistant = prior, experimental = experimental, toolCall = toolCall,
                )
            }
        }
    }

    @Test
    fun `a user prohibition does not reserve the heading that it names`() = runBlocking {
        val mode = ChatNarrativeMode.ADVANCE
        val prompt = PromptTemplates.narrativeModeSystemPrompt(mode)
        val heading = prompt.lineSequence().first()
        val story = "她到达码头。"
        val reply = "$heading\n$story"
        for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
            for (instruction in listOf(
                "继续写故事。下面是系统标题，禁止在回复中输出：\n$heading",
                "Never copy this internal system heading into the reply: $heading",
                "请不要在本轮回复中复述以下叙事模式标题：\n$heading",
            )) {
                assertNarrativeReplyDeltas(
                    listOf(heading, "\n$story"), listOf("", story), story,
                    narrativePrompt = prompt, currentUserText = instruction,
                    experimental = experimental, toolCall = toolCall,
                )
            }
            for (instruction in listOf(
                "请逐字复述这条标题：\n$heading",
                "> Never copy this internal system heading into the reply:\n\n$heading",
                "```text\nNever copy this internal system heading into the reply:\n```\n$heading",
            )) {
                assertNarrativeReplyDeltas(
                    listOf(reply), listOf(reply), reply, narrativePrompt = prompt, currentUserText = instruction,
                    experimental = experimental, toolCall = toolCall,
                )
            }
        }
    }

    @Test
    fun `horizontal padding does not expose the sent mode title`() = runBlocking {
        val story = "她收起雨伞。"
        for (mode in ChatNarrativeMode.entries) {
            val prompt = PromptTemplates.narrativeModeSystemPrompt(mode)
            val heading = prompt.lineSequence().first()
            for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
                for (line in listOf("$heading  ", "   $heading\t")) {
                    assertNarrativeReplyDeltas(
                        listOf(line, "\n$story"), listOf("", story), story,
                        narrativePrompt = prompt, experimental = experimental, toolCall = toolCall,
                    )
                }
                val code = "    $heading\n\n$story"
                assertNarrativeReplyDeltas(
                    listOf(code), listOf(code), code,
                    narrativePrompt = prompt, experimental = experimental, toolCall = toolCall,
                )
            }
        }
    }

    @Test
    fun `a blank line with horizontal padding does not expose an issued title or its wrapper`() = runBlocking {
        val story = "她收起雨伞。"
        for (mode in ChatNarrativeMode.entries) {
            val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "雨落在窗沿。").copy(narrativeMode = mode)
            val prompt = PromptTemplates.narrativeModeSystemPrompt(mode)
            val heading = prompt.lineSequence().first()
            val reply = PromptTemplates.narrativeModeHistoryReply(story, mode)
            val opening = reply.substringBefore('\n')
            val wrappedBody = reply.substringAfter('\n')
            for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
                for (blankLine in listOf("\n", " \n", "\t \r\n")) {
                    assertNarrativeReplyDeltas(
                        listOf("$blankLine$heading\n$opening", "\n$wrappedBody"), listOf("", story), story,
                        previousAssistant = prior, narrativePrompt = prompt, experimental = experimental, toolCall = toolCall,
                    )
                }
                val code = " \n    $heading\n\n$story"
                assertNarrativeReplyDeltas(
                    listOf(code), listOf(code), code,
                    previousAssistant = prior, narrativePrompt = prompt, experimental = experimental, toolCall = toolCall,
                )
                val paddedProse = " \n  $story"
                assertNarrativeReplyDeltas(
                    listOf(paddedProse), listOf(paddedProse), paddedProse,
                    previousAssistant = prior, narrativePrompt = prompt, experimental = experimental, toolCall = toolCall,
                )
            }
        }
    }

    @Test
    fun `the sent title stays visible when the story explicitly reads it aloud`() = runBlocking {
        for (mode in ChatNarrativeMode.entries) {
            val prompt = PromptTemplates.narrativeModeSystemPrompt(mode)
            val heading = prompt.lineSequence().first()
            val lead = "她指着屏幕念出上面显示的标题："
            val story = "她收起雨伞。"
            for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
                for (literalLead in listOf(lead, "She reads the on-screen title aloud:")) {
                    val reply = "$literalLead\n$heading"
                    assertNarrativeReplyDeltas(
                        listOf(literalLead, "\n$heading"), listOf(literalLead, reply), reply,
                        narrativePrompt = prompt, experimental = experimental, toolCall = toolCall,
                    )
                }
                assertNarrativeReplyDeltas(
                    listOf("$story\n$heading"), listOf(story), story,
                    narrativePrompt = prompt, experimental = experimental, toolCall = toolCall,
                )
            }
            assertMultipartNarrativeReply(
                listOf(lead, heading, story), "$lead\n$heading\n$story", narrativePrompt = prompt,
            )
        }
    }

    @Test
    fun `a user prohibition leaves the issued standalone closing tag eligible for cleanup`() = runBlocking {
        val story = "她收起雨伞。"
        for (mode in ChatNarrativeMode.entries) {
            val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "雨落在窗沿。").copy(narrativeMode = mode)
            val pair = PromptTemplates.narrativeModeHistoryReply(story, mode)
            val close = pair.substringAfterLast('\n')
            val raw = "$story\n$close"
            for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
                for (example in listOf(close, pair)) {
                    assertNarrativeReplyDeltas(
                        listOf(raw), listOf(story), story,
                        previousAssistant = prior, experimental = experimental, toolCall = toolCall,
                        currentUserText = "请勿输出以下历史标签：\n$example\n请续写故事。",
                    )
                }
                assertNarrativeReplyDeltas(
                    listOf(pair), listOf(story), story,
                    previousAssistant = prior, experimental = experimental, toolCall = toolCall,
                    currentUserText = "以下是禁止输出的反例。\n$pair\n请续写故事。",
                )
                assertNarrativeReplyDeltas(
                    listOf(raw), listOf(raw), raw,
                    previousAssistant = prior, experimental = experimental, toolCall = toolCall,
                    currentUserText = "请在结尾逐字写出以下标记：\n$close",
                )
            }
        }
    }

    @Test
    fun `a required output label preserves the tags around new story text`() = runBlocking {
        val story = "她收起雨伞。"
        for (mode in ChatNarrativeMode.entries) {
            val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "雨落在窗沿。").copy(narrativeMode = mode)
            val sample = PromptTemplates.narrativeModeHistoryReply("[story]", mode)
            val reply = PromptTemplates.narrativeModeHistoryReply(story, mode)
            val label = "Required output template (keep both tags verbatim):"
            for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
                assertNarrativeReplyDeltas(
                    listOf(reply), listOf(reply), reply,
                    previousAssistant = prior, experimental = experimental, toolCall = toolCall,
                    currentUserText = "$label\n$sample",
                )
                assertNarrativeReplyDeltas(
                    listOf(reply), listOf(reply), reply,
                    previousAssistant = prior, experimental = experimental, toolCall = toolCall,
                    formatCard = "$label\n$sample",
                )
                for (source in listOf(
                    "Here is an example of the request metadata:\n$sample",
                    "Do not include the following history tags in your reply:\n$label\n$sample",
                    "\"$label\"\n$sample",
                )) {
                    assertNarrativeReplyDeltas(
                        listOf(reply), listOf(story), story,
                        previousAssistant = prior, experimental = experimental, toolCall = toolCall,
                        currentUserText = source,
                    )
                }
            }
        }
    }

    @Test
    fun `a required user template survives horizontal padding after the closing tag`() = runBlocking {
        val story = "她收起雨伞。"
        val instruction = "Use this XML template, replace [story] with new prose and keep both tags verbatim:"
        for (mode in ChatNarrativeMode.entries) {
            val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "雨落在窗沿。").copy(narrativeMode = mode)
            val sample = PromptTemplates.narrativeModeHistoryReply("[story]", mode)
            val reply = PromptTemplates.narrativeModeHistoryReply(story, mode)
            for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
                for (padding in listOf("", " ", "\t ")) {
                    assertNarrativeReplyDeltas(
                        listOf(reply), listOf(reply), reply,
                        previousAssistant = prior, experimental = experimental, toolCall = toolCall,
                        currentUserText = "$instruction\n$sample$padding\nWrite the prose in Chinese.",
                    )
                }
                assertNarrativeReplyDeltas(
                    listOf(reply), listOf(story), story,
                    previousAssistant = prior, experimental = experimental, toolCall = toolCall,
                    currentUserText = "$instruction\n$sample This is the end of the example.\nWrite the prose in Chinese.",
                )
            }
        }
    }

    @Test
    fun `an explicit user template reserves its fenced tags for the unfenced reply`() = runBlocking {
        val instruction = "Use exactly this XML template, replace [story] with new prose and keep both tags verbatim:"
        val story = "门开了。"
        for (mode in ChatNarrativeMode.entries) {
            val sample = PromptTemplates.narrativeModeHistoryReply("[story]", mode)
            val reply = PromptTemplates.narrativeModeHistoryReply(story, mode)
            val fence = "```xml\n$sample\n```"
            for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
                assertNarrativeReplyDeltas(
                    listOf(reply), listOf(reply), reply, experimental = experimental, toolCall = toolCall,
                    currentUserText = "$instruction\n$fence",
                )
                for (example in listOf(
                    "The following shows history metadata:\n$fence",
                    "$instruction\n$fence\nDo not output these tags.",
                    "$instruction\n" + fence.prependIndent("> "),
                    "$instruction\n" + fence.replace("```xml", "```kotlin"),
                    "$instruction\n" + fence.removeSuffix("```"),
                )) {
                    assertNarrativeReplyDeltas(
                        listOf(reply), listOf(story), story, experimental = experimental, toolCall = toolCall,
                        currentUserText = example,
                    )
                }
            }
        }
    }

    @Test
    fun `the sent strong format card suffix reserves an explicit reply template`() = runBlocking {
        for (mode in ChatNarrativeMode.entries) {
            val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "雨落在窗沿。").copy(narrativeMode = mode)
            val story = "她收起雨伞。"
            val sample = PromptTemplates.narrativeModeHistoryReply("[正文]", mode)
            val reply = PromptTemplates.narrativeModeHistoryReply(story, mode)
            for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
                assertNarrativeReplyDeltas(
                    listOf(reply), listOf(reply), reply,
                    formatCard = "用自然的叙述描写场景。",
                    previousAssistant = prior, experimental = experimental, toolCall = toolCall,
                    strongPrompt = "输出模板：\n$sample",
                )
                assertNarrativeReplyDeltas(
                    listOf(reply), listOf(story), story,
                    previousAssistant = prior, experimental = experimental, toolCall = toolCall,
                    strongPrompt = "请勿输出以下历史标签：\n输出模板：\n$sample",
                )
            }
        }
    }

    @Test
    fun `scoped history wrapper keeps the clearly captioned literal closer on Preview and Stop`() = runBlocking {
        for (mode in ChatNarrativeMode.entries) {
            val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "Earlier scene.").copy(narrativeMode = mode)
            val caption = "The screen shows this literal closing tag:"
            val close = "</历史回复>"
            val body = "$caption\n$close"
            val opening = PromptTemplates.narrativeModeHistoryReply("sample", mode).substringBefore('\n')
            for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
                assertNarrativeReplyDeltas(
                    listOf("$opening\n$caption\n</历史回", "复>"),
                    listOf("$caption\n</历史回", body), body,
                    previousAssistant = prior, experimental = experimental, toolCall = toolCall, finishReason = "length",
                )
            }
        }
    }

    @Test
    fun `scoped history wrapper keeps the literal closing tag inside a real closing pair`() = runBlocking {
        val close = "</历史回复>"
        val caption = "The screen shows this literal closing tag:"
        for (mode in ChatNarrativeMode.entries) {
            val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "Earlier scene.").copy(narrativeMode = mode)
            for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
                for (body in listOf(
                    "$caption\n$close\nShe turned the page.",
                    "$caption\r\n$close\r\nShe turned the page.",
                    "$caption\n`$close`\nShe turned the page.",
                    "$caption\n> $close\n\nShe turned the page.",
                    "$caption\n\n$close\nShe turned the page.",
                )) {
                    val framed = PromptTemplates.narrativeModeHistoryReply(body, mode)
                    // The blank line leaves a standalone tag ambiguous.
                    val expected = if ("\n\n$close" in body) framed else body
                    assertNarrativeReplyDeltas(
                        listOf(framed), listOf(expected), expected,
                        previousAssistant = prior, experimental = experimental, toolCall = toolCall,
                    )
                }
                val genericCaption = "She turned the page:"
                val framed = PromptTemplates.narrativeModeHistoryReply(genericCaption, mode)
                assertNarrativeReplyDeltas(
                    listOf(framed), listOf(genericCaption), genericCaption,
                    previousAssistant = prior, experimental = experimental, toolCall = toolCall,
                )
                val noWitness = PromptTemplates.narrativeModeHistoryReply("$caption\n$close\nShe turned the page.", mode)
                assertNarrativeReplyDeltas(
                    listOf(noWitness), listOf(noWitness), noWitness, fixtureHistory = false,
                    experimental = experimental, toolCall = toolCall,
                )
            }
        }
    }

    @Test
    fun `rendered character post-history output declaration preserves the actual reply frame`() = runBlocking {
        val name = "Gatekeeper"
        for (mode in ChatNarrativeMode.entries) {
            val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "Earlier scene.").copy(narrativeMode = mode)
            val exact = PromptTemplates.narrativeModeHistoryReply("$name turned the page.", mode)
            val source = "Copy this text verbatim:\n" +
                PromptTemplates.narrativeModeHistoryReply("{{char}} turned the page.", mode)
            for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
                for (history in listOf(null, "The token `</历史回复>` is printed.")) {
                    assertNarrativeReplyDeltas(
                        listOf(exact), listOf(exact), exact, previousAssistant = prior,
                        currentUserText = "Continue.", previousUserText = history, experimental = experimental,
                        toolCall = toolCall, characterPostHistoryInstructions = source,
                    )
                }
                assertNarrativeReplyDeltas(
                    listOf(exact), listOf(exact), exact, previousAssistant = prior,
                    currentUserText = "Continue.", experimental = experimental, toolCall = toolCall,
                    strongPrompt = source.replace("{{char}}", name),
                )
                val template = "Required output template (keep both tags verbatim):\n" +
                    PromptTemplates.narrativeModeHistoryReply("[story]", mode)
                assertNarrativeReplyDeltas(
                    listOf(exact), listOf(exact), exact, previousAssistant = prior,
                    currentUserText = "Continue.", experimental = experimental, toolCall = toolCall,
                    characterPostHistoryInstructions = template,
                )
                for (control in listOf("", "The history format was:\n" + source.substringAfter('\n'))) {
                    assertNarrativeReplyDeltas(
                        listOf(exact), listOf("$name turned the page."), "$name turned the page.",
                        previousAssistant = prior, currentUserText = "Continue.", experimental = experimental,
                        toolCall = toolCall, characterPostHistoryInstructions = control,
                    )
                }
            }
        }
    }

    @Test
    fun `text array SSE preserves a separate newline after the issued mode title`() = runBlocking {
        val prompt = PromptTemplates.narrativeModeSystemPrompt(ChatNarrativeMode.FOCUS)
        val title = prompt.lineSequence().first()
        val story = "她收起雨伞。"
        val request = messages + ChatApiMessage.text("system", prompt)
        val response = sse(listOf(
            ordinaryArrayDelta(title),
            ordinaryArrayDelta("\n"),
            ordinaryArrayDelta("她收起", "雨伞。"),
            chunk(reason = "stop"),
            "[DONE]",
        ))
        for (experimental in listOf(false, true)) {
            val chat = StreamingChatService(experimentalApiModeEnabled = { experimental }, allowCleartextHttp = { true })
            val cleanup = NarrativeHistoryEcho.forRequest(ChatNarrativeMode.FOCUS, null)
            cleanup.recordNarrativePrompt(prompt, null)
            TaskResponseServer(response, "text/event-stream").use { server ->
                val events = withTimeout(5_000) { chat.streamChat("array-delta", request, model(server)).toList() }
                val previews = if (experimental) events.filterIsInstance<StreamEvent.ContentPreview>().map { it.text }
                else events.filterIsInstance<StreamEvent.Delta>().runningFold("") { prefix, delta ->
                    prefix + delta.text
                }.drop(1)
                assertEquals(listOf(title, "$title\n", "$title\n$story"), previews)
                assertEquals(listOf("", "", story), previews.map { cleanup.completedBody(it, streaming = true) })
                assertEquals(story, cleanup.completedBody(previews.last(), interrupted = true))
                val completed = events.filterIsInstance<StreamEvent.Delta>().joinToString("") { it.text }
                assertEquals(story, cleanup.completedBody(completed))
                assertEquals(StreamEvent.Done, events.last())
            }
            TaskResponseServer(response, "text/event-stream").use { server ->
                val events = withTimeout(5_000) { chat.streamText(request, model(server)).toList() }
                val completed = events.filterIsInstance<StreamEvent.Delta>().joinToString("") { it.text }
                assertEquals("$title\n$story", completed)
                assertEquals(StreamEvent.Done, events.last())
            }
        }
    }

    @Test
    fun `a paired tool part does not prevent cleanup of a second projected orphan close`(): Unit = runBlocking {
        val firstStory = "她抬头。"
        val secondStory = "他推开门。"
        val pair = PromptTemplates.narrativeModeHistoryReply(firstStory, ChatNarrativeMode.FOCUS)
        val close = pair.substringAfterLast('\n')
        val orphan = "$secondStory\n$close"
        assertMultipartNarrativeReply(listOf(pair, orphan), "$firstStory\n$secondStory")
        assertMultipartNarrativeReply(
            listOf(pair, orphan), "$firstStory\n$orphan", userText = "请在结尾逐字写出以下标记：\n$close",
        )
        val fencedExample = listOf("这是 XML 代码：\n~~~xml", orphan, "~~~")
        assertMultipartNarrativeReply(listOf(pair) + fencedExample, "$firstStory\n" + fencedExample.joinToString("\n"))
        val quotedExample = listOf("她说：“我在展示一段代码。", orphan, "例子结束。”")
        assertMultipartNarrativeReply(listOf(pair) + quotedExample, "$firstStory\n" + quotedExample.joinToString("\n"))
    }

    @Test
    fun `a hidden opening comment stays in the reply while the issued title is removed`() = runBlocking {
        for (mode in ChatNarrativeMode.entries) {
            val prompt = PromptTemplates.narrativeModeSystemPrompt(mode)
            val heading = prompt.lineSequence().first()
            val comment = "<!-- 舞台注记 -->"
            val story = "她收起雨伞。"
            val raw = "$comment\n$heading\n$story"
            for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
                assertNarrativeReplyDeltas(
                    listOf(comment, "\n$heading\n$story"), listOf(comment, "$comment\n$story"), "$comment\n$story",
                    narrativePrompt = prompt, experimental = experimental, toolCall = toolCall,
                )
                assertNarrativeReplyDeltas(
                    listOf(raw), listOf(raw), raw, narrativePrompt = prompt, experimental = experimental,
                    toolCall = toolCall, currentUserText = "请逐字保留这行标题：\n$heading",
                )
            }
        }
    }

    @Test
    fun `an explicit metadata example lead keeps the same reply across transport boundaries`(): Unit = runBlocking {
        val lead = "The following shows history metadata:"
        val sample = PromptTemplates.narrativeModeHistoryReply("示例正文。", ChatNarrativeMode.FOCUS)
        val expected = "$lead\n$sample"
        assertNarrativeReplyStates(expected, expected, experimental = false)
        assertMultipartNarrativeReply(listOf(lead, sample), expected)
        assertMultipartNarrativeReply(listOf(lead, sample.substringBeforeLast('\n'), sample.substringAfterLast('\n')), expected)
        assertMultipartNarrativeReply(listOf("她翻开说明书。", sample), "她翻开说明书。\n示例正文。")
    }

    @Test
    fun `an explanation delivered in the next tool call preserves the literal example`(): Unit = runBlocking {
        val sample = PromptTemplates.narrativeModeHistoryReply("示例正文。", ChatNarrativeMode.FOCUS)
        val explanation = "以上标签仅用于演示历史记录格式。"
        val anotherStory = PromptTemplates.narrativeModeHistoryReply("雨停了。", ChatNarrativeMode.ADVANCE)
        for (exampleParts in listOf(
            listOf(sample),
            listOf(sample.substringBeforeLast('\n'), sample.substringAfterLast('\n')),
        )) {
            assertMultipartNarrativeReply(
                exampleParts + listOf(explanation, anotherStory), "$sample\n$explanation\n雨停了。",
            )
        }
        assertMultipartNarrativeReply(
            listOf(sample, "她擦掉了白板。", anotherStory), "示例正文。\n她擦掉了白板。\n雨停了。",
        )
    }

    @Test
    fun `repair SSE removes a forbidden outer pair while retaining an original code sample`() = runBlocking {
        val mode = ChatNarrativeMode.FOCUS
        val sample = PromptTemplates.narrativeModeHistoryReply("字面样张。", mode)
        val card = "以下是禁止输出的反例：\n$sample"
        val original = "她打开一本书。\n\n```xml\n$sample\n```"
        val changed = original.replace("一本书", "一封信")
        val raw = PromptTemplates.narrativeModeHistoryReply(changed, mode)
        val response = sse(listOf(ordinaryDelta(raw), chunk(reason = "stop"), "[DONE]"))
        TaskResponseServer(response, "text/event-stream").use { server ->
            val transport = StreamingChatService(allowCleartextHttp = { true })
            val events = withTimeout(5_000) {
                MessageFormatRepairService(transport).streamRepair(original, card, null, model(server)).toList()
            }
            val sent = Json.parseToJsonElement(server.requestBody).jsonObject.getValue("messages").jsonArray
            assertTrue(sent.any {
                val content = it.jsonObject.getValue("content").jsonPrimitive.content
                card in content && original in content
            })
            assertEquals(StreamEvent.Done, events.last())
            val received = events.filterIsInstance<StreamEvent.Delta>().joinToString("") { it.text }
            val cleanup = NarrativeHistoryEcho.forFormatRepair(card, original)
            assertEquals(changed, cleanup.completedBody(received, streaming = true))
            assertEquals(changed, cleanup.completedBody(received, interrupted = true))
            assertEquals(changed, cleanup.completedBody(received))
            assertEquals(raw, NarrativeHistoryEcho.forFormatRepair(card, sample).completedBody(raw))
        }
    }

    @Test
    fun `experimental repair previews ordinary fallback and retracts it on tool ownership`() = runBlocking {
        val mode = ChatNarrativeMode.ADVANCE
        val sample = PromptTemplates.narrativeModeHistoryReply("字面样张。", mode)
        val card = "以下是禁止输出的反例：\n$sample"
        val original = "她打开一本书。"
        val fallback = "她打开一封信。"
        val authoritative = "她把书合上了。"
        val rawFallback = PromptTemplates.narrativeModeHistoryReply(fallback, mode)
        val rawTool = PromptTemplates.narrativeModeHistoryReply(authoritative, mode)
        val repair = MessageFormatRepairService(service())
        val cleanup = NarrativeHistoryEcho.forFormatRepair(card, original)
        val ordinaryEvents = listOf(ordinaryDelta(rawFallback), chunk(reason = "stop"), "[DONE]")
        TaskResponseServer(sse(ordinaryEvents), "text/event-stream").use { server ->
            val stop = withTimeout(5_000) {
                repair.streamRepair(original, card, null, model(server)).first()
            }
            assertEquals(StreamEvent.ContentPreview(rawFallback), stop)
            val prefix = cleanup.completedBody((stop as StreamEvent.ContentPreview).text, interrupted = true)
            assertEquals(fallback, MessageFormatRepairPolicy.progressiveOverlay(original, prefix))
        }
        TaskResponseServer(sse(ordinaryEvents), "text/event-stream").use { server ->
            val events = withTimeout(5_000) {
                repair.streamRepair(original, card, null, model(server)).toList()
            }
            assertEquals(listOf(StreamEvent.ContentPreview(rawFallback), StreamEvent.Delta(rawFallback), StreamEvent.Done), events)
            assertEquals(fallback, cleanup.completedBody(events.filterIsInstance<StreamEvent.Delta>().single().text))
        }
        val takeover = listOf(
            ordinaryDelta(rawFallback),
            chunk(buildJsonObject { put("content", rawTool) }.toString(), "game_content"),
            chunk(reason = "tool_calls"), "[DONE]",
        )
        TaskResponseServer(sse(takeover), "text/event-stream").use { server ->
            val events = withTimeout(5_000) {
                repair.streamRepair(original, card, null, model(server)).toList()
            }
            assertEquals(listOf(
                StreamEvent.ContentPreview(rawFallback), StreamEvent.ContentPreview(""),
                StreamEvent.Delta(rawTool), StreamEvent.Done,
            ), events)
            val accepted = events.filterIsInstance<StreamEvent.Delta>().single().text
            assertEquals(authoritative, cleanup.completedBody(accepted, streaming = true))
            assertEquals(authoritative, cleanup.completedBody(accepted, interrupted = true))
            assertEquals(authoritative, cleanup.completedBody(accepted))
        }
    }

    private fun ordinaryDelta(body: String): String = buildJsonObject {
        put("choices", buildJsonArray {
            add(buildJsonObject { put("delta", buildJsonObject { put("content", body) }) })
        })
    }.toString()

    private fun ordinaryArrayDelta(vararg parts: String): String = buildJsonObject {
        put("choices", buildJsonArray {
            add(buildJsonObject {
                put("delta", buildJsonObject {
                    put("content", buildJsonArray {
                        for (part in parts) add(buildJsonObject {
                            put("type", "text")
                            put("text", part)
                        })
                    })
                })
            })
        })
    }.toString()

    private fun narrativeHistoryFixtures(): List<ChatMessage> =
        ChatNarrativeMode.entries.map { mode ->
            ChatMessage.create("fixture", MessageRole.ASSISTANT, "早先的故事。").copy(narrativeMode = mode)
        } + ChatMessage.create("fixture", MessageRole.ASSISTANT, "<聚焦模式=\"沉浸\">\n旧故事。\n</聚焦模式>")

    private fun projectAssistant(cleanup: NarrativeHistoryEcho.RequestCleanup, assistant: ChatMessage): ChatApiMessage {
        val body = assistant.displayContent
        val projection = ChatHistoryPromptPolicy.renderedHistory(
            assistant, body, narrativeModesEnabled = true,
            legacyHistoryWrapperDeclaredAsOutput = cleanup.declaresLegacyHistoryWrapper,
        )
        cleanup.recordHistoryProjection(body, projection.text, projection.retainsLegacyFraming)
        return ChatApiMessage.text("assistant", projection.text)
    }

    private suspend fun assertNarrativeReplyStates(
        raw: String,
        expected: String,
        formatCard: String? = null,
        previousAssistant: ChatMessage? = null,
        experimental: Boolean = true,
        narrativePrompt: String? = null,
        currentUserText: String? = null,
        fixtureHistory: Boolean = true,
    ) = assertNarrativeReplyDeltas(
        listOf(raw), listOf(expected), expected, formatCard, previousAssistant, experimental, narrativePrompt, currentUserText,
        fixtureHistory = fixtureHistory,
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
        fixtureHistory: Boolean = true,
        previousUserText: String? = null,
        strongPrompt: String? = null,
        characterPostHistoryInstructions: String? = null,
        finishReason: String = if (toolCall) "tool_calls" else "stop",
    ) {
        val cleanup = NarrativeHistoryEcho.forRequest(ChatNarrativeMode.ADVANCE, formatCard)
        previousUserText?.let { cleanup.recordNarrativePrompt("", it) }
        cleanup.recordNarrativePrompt(narrativePrompt.orEmpty(), currentUserText)
        val requestMessages = messages.toMutableList()
        if (currentUserText != null) {
            val userIndex = requestMessages.indexOfFirst { it.role == "user" }
            requestMessages[userIndex] = ChatApiMessage.text("user", currentUserText)
            cleanup.recordCurrentLiteralTags(currentUserText)
        }
        if (narrativePrompt != null) requestMessages.add(1, ChatApiMessage.text("system", narrativePrompt))
        val cardRequirements = formatCard?.let { PromptTemplates.currentTurnOutputRequirementsSystemPrompt(it, 300) }
        cardRequirements?.let { requestMessages.add(ChatApiMessage.text("system", it)) }
        if (narrativeTail != null) {
            requestMessages.add(ChatApiMessage.text("system", narrativeTail))
            cleanup.recordNarrativePrompt(narrativeTail, currentUserText)
        }
        previousUserText?.let { requestMessages.add(1, ChatApiMessage.text("user", it)) }
        val histories = previousAssistant?.let(::listOf) ?: if (fixtureHistory) narrativeHistoryFixtures() else emptyList()
        val historyMessages = histories.map { projectAssistant(cleanup, it) }
        for (history in historyMessages) {
            requestMessages.add(1, history)
        }
        val acknowledgement = PromptTemplates.CCB_POST_USER_ACK_ASSISTANT_PROMPT.trimIndent().trim()
        val strongSuffix = strongPrompt?.let { text ->
            FormatCardUserToolPolicy.strongPromptSystemSuffix(
                listOf(FormatCardUserToolConfig.strongPromptSuffix().copy(text = text)),
            )
        }
        val postHistory = characterPostHistoryInstructions?.let { source ->
            PromptAssembler().assembleCachePromptLayers(
                CharacterCard.create("Gatekeeper").copy(postHistoryInstructions = source),
            ).tailSystemPrompt
        }
        val postUserPrompt = postHistory?.let {
            buildCcbFinalTailSystemPrompt(it, "", FormatPromptPosition.END)
        }
        if (strongSuffix == null && postUserPrompt == null) {
            requestMessages.add(ChatApiMessage.text("assistant", acknowledgement))
        } else {
            val sentUser = requestMessages.removeAt(requestMessages.indexOfLast { it.role == "user" })
            appendCurrentUserAndCcbTailMessages(requestMessages, sentUser, strongSuffix.orEmpty(), postUserPrompt.orEmpty())
            if (characterPostHistoryInstructions != null && postHistory != null) {
                cleanup.recordDispatchedPostHistory(postHistory, characterPostHistoryInstructions.isNotBlank())
                if ("{{char}}" in characterPostHistoryInstructions) {
                    assertTrue("Expected rendered character name", "Gatekeeper turned the page." in postHistory)
                    assertFalse("Unexpected unrendered placeholder", "{{char}}" in postHistory)
                }
            }
            strongSuffix?.let(cleanup::recordAuthoredOutput)
        }
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
            sse(listOf(chunk(reason = finishReason), "[DONE]")),
        ).use { server ->
            val previews = mutableListOf<String>()
            var completion: ChatReplyCompletion? = null
            val chatService = StreamingChatService(experimentalApiModeEnabled = { experimental }, allowCleartextHttp = { true })
            val events = withTimeout(5_000) {
                chatService.streamChat(
                    "narrative-reply-states", requestMessages, model(server.baseUrl),
                    onReplyCompletion = { completion = it },
                ).onEach { event ->
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
            val sourceTexts = listOfNotNull(narrativePrompt, narrativeTail, currentUserText, previousUserText, cardRequirements, strongSuffix, postUserPrompt) +
                historyMessages.map { it.content.jsonPrimitive.content }
            for (source in sourceTexts) {
                assertTrue("Expected source in serialized reply request", context.any {
                    it.jsonObject.getValue("content").jsonPrimitive.content == source
                })
            }
            assertEquals(acknowledgement, context.last().jsonObject.getValue("content").jsonPrimitive.content)
            assertEquals(StreamEvent.Done, events.last())
            assertEquals(finishReason, completion?.finishReason)
            val completed = events.filterIsInstance<StreamEvent.Delta>().joinToString("") { it.text }
            assertEquals(rawPreviews.last(), completed)
            val live = previews.map { cleanup.completedBody(it, streaming = true) }
            val stopped = previews.map { cleanup.completedBody(it, interrupted = true) }
            val done = cleanup.completedBody(completed, interrupted = finishReason == "length")
            assertEquals(
                "Reply Preview, Stop and Done",
                listOf(expectedPreviews, expectedPreviews, listOf(expectedCompleted)),
                listOf(live, stopped, listOf(done)),
            )
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
    fun `main chat truncation retains complete contiguous tool calls before the truncated call`() = runBlocking {
        val mode = ChatNarrativeMode.FOCUS
        val opening = PromptTemplates.narrativeModeHistoryReply("history", mode).substringBefore('\n')
        val story = "门开了。"
        val cleanup = NarrativeHistoryEcho.forRequest(mode, null)
        val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "Earlier story").copy(narrativeMode = mode)
        val request = messages.toMutableList().apply { add(1, projectAssistant(cleanup, prior)) }
        val response = sse(listOf(
            chunk(buildJsonObject { put("content", opening) }.toString(), "game_content", index = 0),
            chunk(buildJsonObject { put("content", story) }.toString(), "game_content", index = 1),
            chunk("""{"content":"unfinished""", "game_content", index = 2),
            chunk(buildJsonObject { put("content", "after incomplete call") }.toString(), "game_content", index = 3),
            chunk(reason = "length"),
            "[DONE]",
        ))
        TaskResponseServer(response, "text/event-stream").use { server ->
            var completion: ChatReplyCompletion? = null
            val events = withTimeout(5_000) {
                service().streamChat("truncated-multipart", request, model(server),
                    onReplyCompletion = { completion = it }).toList()
            }
            val partLengths = listOf(opening.length, story.length)
            val deltas = events.filterIsInstance<StreamEvent.Delta>()
            val text = deltas.joinToString("") { it.text }
            assertEquals(listOf(StreamEvent.Delta(opening), StreamEvent.Delta("\n$story", partLengths)), deltas)
            assertEquals(StreamEvent.Done, events.last())
            assertEquals("length", completion?.finishReason)
            assertEquals(false, completion?.toolReplyCompleted)
            assertEquals(partLengths, completion?.toolReplyPartLengths)
            assertEquals(story, cleanup.completedBody(text, partLengths = partLengths, streaming = true))
            assertEquals(story, cleanup.completedBody(text, partLengths = partLengths, interrupted = true))
        }
    }

    @Test
    fun `main chat cancellation reads complete contiguously buffered calls without a terminal event`() = runBlocking {
        val story = "First scene."
        val next = "Second scene."
        val first = chunk(buildJsonObject { put("content", story) }.toString(), "game_content", index = 0)
        val second = chunk(buildJsonObject { put("content", next) }.toString(), "game_content", index = 1)
        val incomplete = chunk("""{"content":"unfinished""", "game_content", index = 2)
        val later = chunk("""{"content":"Skipped scene."}""", "game_content", index = 3)
        assertInterruptedToolPrefix(listOf(first, second, incomplete, later), story, listOf(story, next))
    }

    @Test
    fun `main chat cancellation does not skip an absent or malformed call or change the visible first call`() = runBlocking {
        val story = "First scene."
        val first = chunk(buildJsonObject { put("content", story) }.toString(), "game_content", index = 0)
        val second = chunk("""{"content":"Second scene."}""", "game_content", index = 1)
        val third = chunk("""{"content":"Skipped scene."}""", "game_content", index = 2)
        val malformed = chunk("""{"content":42}""", "game_content", index = 1)
        val inconsistent = chunk("""{"content":"$story","content":"Different content."}""", "game_content", index = 0)
        assertInterruptedToolPrefix(listOf(first, third), story, emptyList())
        assertInterruptedToolPrefix(listOf(first, malformed, third), story, emptyList())
        assertInterruptedToolPrefix(listOf(inconsistent, second), story, emptyList())
    }

    private suspend fun assertInterruptedToolPrefix(frames: List<String>, preview: String, expectedParts: List<String>) {
        val witness = "Synthetic provider has flushed tool arguments."
        val marker = """{"choices":[{"delta":{"reasoning_content":"$witness"}}]}"""
        GatedExperimentalResponse(
            sse(frames + marker), sse(listOf(chunk(reason = "tool_calls"), "[DONE]")),
        ).use { server ->
            var readBufferedParts: (() -> List<String>)? = null
            var completion: ChatReplyCompletion? = null
            val shown = StringBuilder()
            val last = withTimeout(5_000) {
                service().streamChat(
                    "cancel-multipart", messages, model(server.baseUrl),
                    onToolReplyPrefixReader = { readBufferedParts = it },
                    onReplyCompletion = { completion = it },
                ).onEach { if (it is StreamEvent.Delta) shown.append(it.text) }
                    .first { it is StreamEvent.ReasoningDelta }
            }
            assertEquals(StreamEvent.ReasoningDelta(witness), last)
            assertEquals(preview, shown.toString())
            assertFalse(server.tailSent)
            // The client can report its own cancellation through onFailure before this caller resumes.
            assertEquals(null, completion?.finishReason)
            assertFalse(completion?.toolReplyCompleted == true)
            assertEquals(expectedParts, checkNotNull(readBufferedParts).invoke())
        }
    }

    @Test
    fun `explicitly stopped format repair retains only complete contiguous calls already received in the same frame`() = runBlocking {
        val original = "旧。"
        val first = "Repaired opening line."
        val second = "Repaired second line."
        val terminating = sse(listOf(chunk(reason = "tool_calls"), "[DONE]"))
        val cases = listOf(
            Triple(1, buildJsonObject { put("content", second) }.toString(), "$first\n$second"),
            Triple(1, """{"content":"unfinished""", first),
            Triple(1, """{"content":42}""", first),
            Triple(2, buildJsonObject { put("content", second) }.toString(), first),
        )
        for ((index, arguments, expected) in cases) {
            GatedExperimentalResponse(sse(listOf(repairToolFrame(first, index, arguments))), terminating).use { server ->
                var reader: (() -> List<String>)? = null
                val repair = MessageFormatRepairService(service())
                val event = withTimeout(5_000) {
                    repair.streamRepair(original, "Keep paragraph breaks.", null, model(server.baseUrl)) {
                        reader = it
                    }.first { it is StreamEvent.Delta && it.text.length > original.length }
                } as StreamEvent.Delta
                assertEquals(first, event.text)
                assertFalse(server.tailSent)
                val parts = checkNotNull(reader).invoke()
                val prefix = if (parts.isEmpty()) event.text else parts.joinToString("\n")
                assertEquals(expected, MessageFormatRepairPolicy.progressiveOverlay(original, prefix))
            }
        }
    }

    @Test
    fun `completed multipart format repair and ordinary stopped format repair retain their existing paths`() = runBlocking {
        val first = "First repaired line."
        val second = "Preserved second line."
        val complete = repairToolFrame(first, 1, buildJsonObject { put("content", second) }.toString())
        val terminal = sse(listOf(chunk(reason = "tool_calls"), "[DONE]"))
        GatedExperimentalResponse(sse(listOf(complete)), terminal).use { server ->
            val events = withTimeout(5_000) {
                MessageFormatRepairService(service()).streamRepair("旧。", "Keep prose.", null, model(server.baseUrl))
                    .onEach { if (it is StreamEvent.Delta) server.releaseTail() }.toList()
            }
            val deltas = events.filterIsInstance<StreamEvent.Delta>()
            assertEquals("$first\n$second", deltas.joinToString("") { it.text })
            assertEquals(listOf(first.length, second.length), deltas.last().toolReplyPartLengths)
            assertEquals(StreamEvent.Done, events.last())
        }
        GatedExperimentalResponse(sse(listOf(ordinaryDelta(first))), sse(listOf(chunk(reason = "stop"), "[DONE]"))).use { server ->
            val ordinary = StreamingChatService(experimentalApiModeEnabled = { false }, allowCleartextHttp = { true })
            var registeredToolReader = false
            val event = withTimeout(5_000) {
                MessageFormatRepairService(ordinary).streamRepair("旧。", "Keep prose.", null, model(server.baseUrl)) {
                    registeredToolReader = true
                }.first { it is StreamEvent.Delta }
            }
            assertEquals(StreamEvent.Delta(first), event)
            assertFalse(registeredToolReader)
            assertFalse(server.tailSent)
        }
    }

    @Test
    fun `repair cleans eligible orphan closes in independently validated tool parts over real SSE`() = runBlocking {
        for (mode in listOf(ChatNarrativeMode.FOCUS, ChatNarrativeMode.ADVANCE)) assertRepairToolOrphans(mode)
    }

    private suspend fun assertRepairToolOrphans(mode: ChatNarrativeMode) {
        val original = "旧。"
        val first = "第一段。"
        val second = "第二段。"
        val close = "</历史回复>"
        val card = "以下是禁止输出的反例：\n${PromptTemplates.narrativeModeHistoryReply("旧示例", mode)}"
        val framed = PromptTemplates.narrativeModeHistoryReply(first, mode)
        val footnote = "$first\n$close\n[^1]: Editor note."
        val quote = "$first\n> $close"
        val cases = listOf(
            Triple("$first\n$close", second, "$first\n$second"),
            Triple(framed, "$second\n$close", "$first\n$second"),
            Triple(quote, second, "$quote\n$second"),
            Triple(footnote, second, "$footnote\n$second"),
            Triple("$first\n```xml", "$close\n```", "$first\n```xml\n$close\n```"),
            Triple(first, second, "$first\n$second"),
        )
        for ((firstPart, secondPart, expected) in cases) {
            val arguments = buildJsonObject { put("content", secondPart) }.toString()
            val frame = repairToolFrame(firstPart, 1, arguments)
            val cleanup = NarrativeHistoryEcho.forFormatRepair(card, original)
            val parts = listOf(firstPart, secondPart)
            val prefix = heldRepairPrefix(frame, card, original)
            assertEquals(parts, prefix)
            val raw = prefix.joinToString("\n")
            val lengths = prefix.map(String::length)
            assertEquals(expected, cleanup.completedBody(raw, interrupted = true, partLengths = lengths))
            val authored = NarrativeHistoryEcho.forFormatRepair(card, "$original\n`$close`")
            if (firstPart == framed) {
                assertEquals("$first\n$second\n$close", authored.completedBody(raw, partLengths = lengths))
                val visibleAuthoredClose = NarrativeHistoryEcho.forFormatRepair(card, "$original\n$close")
                assertEquals(raw, visibleAuthoredClose.completedBody(raw, partLengths = lengths))
            }
            GatedExperimentalResponse(sse(listOf(frame)), sse(listOf(chunk(reason = "tool_calls"), "[DONE]"))).use { server ->
                val events = withTimeout(5_000) {
                    MessageFormatRepairService(service()).streamRepair(original, card, null, model(server.baseUrl))
                        .onEach { if (it is StreamEvent.Delta) server.releaseTail() }.toList()
                }
                val deltas = events.filterIsInstance<StreamEvent.Delta>()
                assertEquals(raw, deltas.joinToString("") { it.text })
                assertEquals(lengths, deltas.last().toolReplyPartLengths)
                assertEquals(StreamEvent.Done, events.last())
                assertEquals(expected, cleanup.completedBody(raw, partLengths = lengths, streaming = true))
                assertEquals(expected, cleanup.completedBody(raw, partLengths = lengths))
            }
        }
        val incomplete = heldRepairPrefix(repairToolFrame("$first\n$close", 1, """{"content":"unfinished"""), card, original)
        assertEquals(listOf("$first\n$close"), incomplete)
        assertEquals(first, NarrativeHistoryEcho.forFormatRepair(card, original).completedBody(incomplete.single(), interrupted = true))
    }

    private suspend fun heldRepairPrefix(frame: String, card: String, original: String): List<String> {
        GatedExperimentalResponse(sse(listOf(frame)), sse(listOf(chunk(reason = "tool_calls"), "[DONE]"))).use { server ->
            var reader: (() -> List<String>)? = null
            val first = withTimeout(5_000) {
                MessageFormatRepairService(service()).streamRepair(original, card, null, model(server.baseUrl)) {
                    reader = it
                }.first { it is StreamEvent.Delta }
            } as StreamEvent.Delta
            assertFalse(server.tailSent)
            return checkNotNull(reader).invoke().ifEmpty { listOf(first.text) }
        }
    }

    @Test
    fun `a caption and a literal closing tag in separate validated tool calls remain intact in repair and main chat`() = runBlocking {
        val original = "End tag: &lt;/历史回复&gt;."
        val caption = "The literal closing tag is:"
        val close = "</历史回复>"
        val follow = "The explanation follows."
        val sample = PromptTemplates.narrativeModeHistoryReply("wrong", ChatNarrativeMode.FOCUS)
        val card = "Decode written entities in literal examples.\n\nThe following is a counterexample:\n$sample"
        val complete = repairToolFrame(caption, 1, buildJsonObject { put("content", close) }.toString())
        val stopped = heldRepairPrefix(complete, card, original)
        assertEquals(listOf(caption, close), stopped)
        val cleanup = NarrativeHistoryEcho.forFormatRepair(card, original)
        val literal = "$caption\n$close"
        assertEquals(literal, cleanup.completedBody(stopped.joinToString("\n"), interrupted = true, partLengths = stopped.map(String::length)))
        for ((parts, expected) in listOf(
            listOf(caption, close) to literal,
            listOf(caption, close, follow) to "$literal\n$follow",
            listOf("It begins now.", close) to "It begins now.",
            listOf(caption, "> $close") to "$caption\n> $close",
            listOf(caption, "```xml\n$close\n```") to "$caption\n```xml\n$close\n```",
        )) {
            assertMultipartNarrativeReply(parts, expected)
            val chunks = parts.mapIndexed { index, part ->
                chunk(buildJsonObject { put("content", part) }.toString(), "game_content", index = index)
            } + listOf(chunk(reason = "tool_calls"), "[DONE]")
            TaskResponseServer(sse(chunks), "text/event-stream").use { server ->
                val events = withTimeout(5_000) {
                    MessageFormatRepairService(service()).streamRepair(original, card, null, model(server)).toList()
                }
                assertEquals(StreamEvent.Done, events.last())
                val deltas = events.filterIsInstance<StreamEvent.Delta>()
                val body = deltas.joinToString("") { it.text }
                val lengths = deltas.last().toolReplyPartLengths
                assertEquals(parts.map(String::length), lengths)
                assertEquals(expected, cleanup.completedBody(body, streaming = true, partLengths = lengths))
                assertEquals(expected, cleanup.completedBody(body, interrupted = true, partLengths = lengths))
                assertEquals(expected, cleanup.completedBody(body, partLengths = lengths))
            }
        }
        for ((index, arguments) in listOf(1 to """{"content":"unfinished""", 2 to buildJsonObject { put("content", close) }.toString())) {
            val retained = heldRepairPrefix(repairToolFrame(caption, index, arguments), card, original)
            assertEquals(listOf(caption), retained)
        }
        val malformed = repairToolFrame(caption, 1, """{"content":42}""")
        TaskResponseServer(sse(listOf(malformed, chunk(reason = "tool_calls"), "[DONE]")), "text/event-stream").use { server ->
            val events = withTimeout(5_000) {
                MessageFormatRepairService(service()).streamRepair(original, card, null, model(server)).toList()
            }
            assertEquals(listOf(StreamEvent.Delta(caption)), events.filterIsInstance<StreamEvent.Delta>())
            assertTrue(events.last() is StreamEvent.Error)
            assertFalse(events.any { it is StreamEvent.Done })
        }
        var reader: (() -> List<String>)? = null
        GatedExperimentalResponse(sse(listOf(complete)), sse(listOf(chunk(reason = "tool_calls"), "[DONE]"))).use { server ->
            val ordinary = NarrativeHistoryEcho.forRequest(ChatNarrativeMode.FOCUS, null)
            val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "Earlier scene.").copy(narrativeMode = ChatNarrativeMode.FOCUS)
            val request = messages.toMutableList().apply { add(1, projectAssistant(ordinary, prior)) }
            val first = withTimeout(5_000) {
                service().streamChat("captioned-tool-stop", request, model(server.baseUrl), onToolReplyPrefixReader = { reader = it })
                    .first { it is StreamEvent.Delta }
            }
            assertEquals(StreamEvent.Delta(caption), first)
            assertFalse(server.tailSent)
            val parts = checkNotNull(reader).invoke()
            assertEquals(listOf(caption, close), parts)
            assertEquals(literal, ordinary.completedBody(parts.joinToString("\n"), interrupted = true, partLengths = parts.map(String::length)))
        }
    }

    @Test
    fun `an explicitly prohibited standalone repair closer is cleaned at completion`() = runBlocking {
        assertStandaloneRepairProhibition(stop = false)
    }

    @Test
    fun `an explicitly prohibited standalone repair closer is cleaned on Stop before terminal`() = runBlocking {
        assertStandaloneRepairProhibition(stop = true)
    }

    private suspend fun assertStandaloneRepairProhibition(stop: Boolean) {
        val close = "</历史回复>"
        val body = "She turned the page."
        val raw = "$body\n$close"
        val original = "She opened the book."
        val standalone = "Do not output the following history tags:\n$close"
        for ((card, expected) in listOf(
            standalone to body,
            "Example of a history tag:\n$close" to raw,
            "Do not output the following history tags:\n</聚焦模式>" to raw,
            "> Do not output the following history tags:\n> $close" to raw,
            "The following is a counterexample:\n" +
                PromptTemplates.narrativeModeHistoryReply("wrong", ChatNarrativeMode.FOCUS) to body,
        )) {
            for (experimental in listOf(false, true)) {
                val delta = if (experimental) {
                    chunk(buildJsonObject { put("content", raw) }.toString(), "game_content")
                } else {
                    ordinaryDelta(raw)
                }
                GatedExperimentalResponse(
                    sse(listOf(delta)), sse(listOf(chunk(reason = if (experimental) "tool_calls" else "stop"), "[DONE]")),
                ).use { server ->
                    var reader: (() -> List<String>)? = null
                    val repair = MessageFormatRepairService(StreamingChatService(
                        experimentalApiModeEnabled = { experimental }, allowCleartextHttp = { true },
                    ))
                    val events = repair.streamRepair(original, card, null, model(server.baseUrl)) { reader = it }
                    val observed = if (stop) {
                        val first = withTimeout(5_000) { events.first { it is StreamEvent.Delta } } as StreamEvent.Delta
                        assertFalse(server.tailSent)
                        assertEquals(raw, reader?.invoke()?.singleOrNull() ?: first.text)
                        first.text
                    } else {
                        val completed = withTimeout(5_000) {
                            events.onEach { if (it is StreamEvent.Delta) server.releaseTail() }.toList()
                        }
                        assertEquals(StreamEvent.Done, completed.last())
                        completed.filterIsInstance<StreamEvent.Delta>().joinToString("") { it.text }
                    }
                    assertEquals(raw, observed)
                    val cleanup = NarrativeHistoryEcho.forFormatRepair(card, original)
                    assertTrue("Expected explicit card context in actual request", card.lineSequence().first() in server.requestBody)
                    val terminalLabel = if (stop) "Stop" else "Done"
                    assertEquals("Repair Preview and $terminalLabel", listOf(expected, expected), listOf(
                        cleanup.completedBody(observed, streaming = true),
                        cleanup.completedBody(observed, interrupted = stop),
                    ))
                }
            }
        }
    }

    @Test
    fun `repair cleans a fresh balanced pair only when visible input markers survive as proven literals`() = runBlocking {
        val close = "</历史回复>"
        for (mode in listOf(ChatNarrativeMode.FOCUS, ChatNarrativeMode.ADVANCE)) {
            val sample = PromptTemplates.narrativeModeHistoryReply("旧示例", mode)
            val card = "以下是禁止输出的反例：\n$sample"
            val original = "结束标签示例：\n$close\n她关上门。"
            val body = "结束标签示例：\n`$close`\n她关上门。"
            val raw = PromptTemplates.narrativeModeHistoryReply(body, mode)
            val unchangedTokens = PromptTemplates.narrativeModeHistoryReply(original, mode)
            val withoutLiteral = PromptTemplates.narrativeModeHistoryReply("她关上门。", mode)
            for ((input, expected) in listOf(raw to body, unchangedTokens to unchangedTokens, withoutLiteral to withoutLiteral)) {
                for (tool in listOf(false, true)) {
                    val first = if (tool) chunk(buildJsonObject { put("content", input) }.toString(), "game_content") else ordinaryDelta(input)
                    val response = sse(listOf(first, chunk(reason = if (tool) "tool_calls" else "stop"), "[DONE]"))
                    TaskResponseServer(response, "text/event-stream").use { server ->
                        val transport = StreamingChatService(experimentalApiModeEnabled = { tool }, allowCleartextHttp = { true })
                        val events = withTimeout(5_000) {
                            MessageFormatRepairService(transport).streamRepair(original, card, null, model(server)).toList()
                        }
                        assertEquals(StreamEvent.Done, events.last())
                        val received = events.filterIsInstance<StreamEvent.Delta>().joinToString("") { it.text }
                        assertEquals(input, received)
                        val cleanup = NarrativeHistoryEcho.forFormatRepair(card, original)
                        assertEquals(expected, cleanup.completedBody(received, streaming = true))
                        assertEquals(expected, cleanup.completedBody(received, interrupted = true))
                        assertEquals(expected, cleanup.completedBody(received))
                        val fromCurrentUser = NarrativeHistoryEcho.forFormatRepair(card, "她关上门。", original)
                        assertEquals(expected, fromCurrentUser.completedBody(received))
                    }
                }
            }
            val cleanup = NarrativeHistoryEcho.forFormatRepair(card, original)
            val partial = raw.substringBeforeLast('\n') + "\n</历史回"
            assertEquals(partial, cleanup.completedBody(partial, streaming = true))
            assertEquals(partial, cleanup.completedBody(partial, interrupted = true))
            assertEquals("她关上门。\n$close", cleanup.completedBody("她关上门。\n$close"))
            assertEquals(raw, NarrativeHistoryEcho.forFormatRepair(card, "$original\n$close").completedBody(raw))
        }
    }

    @Test
    fun `repair uses normal literal visibility to keep a protected source close inside the new pair`() = runBlocking {
        val close = "</历史回复>"
        val original = "The closing-tag example is $close."
        val literalLines = linkedMapOf(
            "blockquote" to "> $close",
            "list" to "- $close",
            "escaped" to "\\$close",
            "ascii quote" to "\"$close\"",
            "prose quote" to "“$close”",
            "fence" to "~~~xml\n$close\n~~~",
            "inline code" to "`$close`",
        )
        val expected = mutableMapOf<String, List<String>>()
        val observed = mutableMapOf<String, List<String>>()
        for (mode in listOf(ChatNarrativeMode.FOCUS, ChatNarrativeMode.ADVANCE)) {
            val sample = PromptTemplates.narrativeModeHistoryReply("示例正文", mode)
            val card = "以下是禁止输出的反例：\n$sample"
            val cleanup = NarrativeHistoryEcho.forFormatRepair(card, original)
            for ((kind, literal) in literalLines) {
                val body = "Closing-tag example:\n\n$literal"
                val raw = PromptTemplates.narrativeModeHistoryReply("$body\n", mode)
                for (tool in listOf(false, true)) {
                    val key = "$mode $kind ${if (tool) "tool" else "ordinary"}"
                    val first = if (tool) chunk(buildJsonObject { put("content", raw) }.toString(), "game_content") else ordinaryDelta(raw)
                    val tail = sse(listOf(chunk(reason = if (tool) "tool_calls" else "stop"), "[DONE]"))
                    GatedExperimentalResponse(sse(listOf(first)), tail).use { server ->
                        val wire = StreamingChatService(experimentalApiModeEnabled = { tool }, allowCleartextHttp = { true })
                        var sawBody = false
                        val events = withTimeout(5_000) {
                            MessageFormatRepairService(wire).streamRepair(original, card, null, model(server.baseUrl)).onEach {
                                if (it == StreamEvent.Delta(raw)) {
                                    assertFalse(server.tailSent)
                                    sawBody = true
                                    server.releaseTail()
                                }
                            }.toList()
                        }
                        assertTrue(sawBody)
                        assertEquals(StreamEvent.Done, events.last())
                        val received = events.filterIsInstance<StreamEvent.Delta>().joinToString("") { it.text }
                        assertEquals(raw, received)
                        expected[key] = listOf(body, body, body)
                        observed[key] = listOf(
                            cleanup.completedBody(received, streaming = true),
                            cleanup.completedBody(received, interrupted = true),
                            cleanup.completedBody(received),
                        )
                    }
                }
            }
            val nestedQuote = PromptTemplates.narrativeModeHistoryReply("Closing-tag example:\n\n> $close\n", mode)
            assertEquals(original, cleanup.completedBody(original))
            val unwitnessed = PromptTemplates.narrativeModeHistoryReply("Now a different example.", mode)
            assertEquals(unwitnessed, cleanup.completedBody(unwitnessed))
            val unresolved = PromptTemplates.narrativeModeHistoryReply("Closing-tag example:\n$close\nThen prose.", mode)
            assertEquals(unresolved, cleanup.completedBody(unresolved))
            assertEquals(nestedQuote, NarrativeHistoryEcho.forFormatRepair(card, sample).completedBody(nestedQuote))
            assertEquals(nestedQuote, NarrativeHistoryEcho.forFormatRepair(card, "$original\n$close").completedBody(nestedQuote))
        }
        assertEquals(expected, observed)
    }

    @Test
    fun `terminal Done rejects disconnected game content indexes in chat and repair`() = runBlocking {
        data class Reply(val raw: String, val done: Boolean, val error: Boolean, val toolReplyCompleted: Boolean?)
        val first = "First scene."
        val later = "Disconnected or subsequent scene."
        fun call(index: Int, content: String) = chunk(buildJsonObject { put("content", content) }.toString(), "game_content", index = index)
        val observed = mutableMapOf<String, Reply>()
        val expected = mutableMapOf<String, Reply>()
        for ((frames, expectedPreview, successful) in listOf(
            Triple(listOf(call(0, first), call(2, later)), first, false),
            Triple(listOf(call(2, later)), "", false),
            Triple(listOf(call(1, later), call(0, first)), "$first\n$later", true),
        )) {
            for (repair in listOf(false, true)) {
                val response = sse(frames + listOf(chunk(reason = "tool_calls"), "[DONE]"))
                TaskResponseServer(response, "text/event-stream").use { server ->
                    var completion: ChatReplyCompletion? = null
                    val transport = service()
                    val stream = if (repair) MessageFormatRepairService(transport).streamRepair("Original.", "Keep prose.", null, model(server))
                        else transport.streamChat("terminal-index-validation", messages, model(server), onReplyCompletion = { completion = it })
                    val events = withTimeout(5_000) { stream.toList() }
                    val key = "${if (repair) "repair" else "chat"} ${if (successful) "contiguous" else if (frames.size == 1) "missing-zero" else "gap"}"
                    observed[key] = Reply(
                        events.filterIsInstance<StreamEvent.Delta>().joinToString("") { it.text },
                        events.any { it is StreamEvent.Done }, events.last() is StreamEvent.Error, completion?.toolReplyCompleted,
                    )
                    expected[key] = Reply(expectedPreview, successful, !successful, successful.takeUnless { repair })
                }
            }
        }
        assertEquals(expected, observed)
    }

    private fun repairToolFrame(first: String, followingIndex: Int, followingArguments: String): String {
        fun call(index: Int, arguments: String) = buildJsonObject {
            put("index", index)
            put("function", buildJsonObject {
                put("name", "game_content")
                put("arguments", arguments)
            })
        }
        return buildJsonObject {
            put("choices", buildJsonArray {
                add(buildJsonObject {
                    put("delta", buildJsonObject {
                        put("tool_calls", buildJsonArray {
                            add(call(0, buildJsonObject { put("content", first) }.toString()))
                            add(call(followingIndex, followingArguments))
                        })
                    })
                })
            })
        }.toString()
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
