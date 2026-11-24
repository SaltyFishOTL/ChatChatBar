package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatNarrativeMode
import com.example.chatbar.data.local.entity.ChunkSourceType
import com.example.chatbar.data.local.entity.FormatCardUserToolConfig
import com.example.chatbar.data.local.entity.FormatPromptPosition
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.WorldBook
import com.example.chatbar.data.local.entity.WorldBookEntry
import com.example.chatbar.domain.card.FormatCardUserToolPolicy
import com.example.chatbar.domain.prompt.AiTaskContext
import com.example.chatbar.domain.prompt.AiTaskKind
import com.example.chatbar.domain.prompt.PromptTemplates
import com.example.chatbar.domain.rag.RetrievedKnowledgeCard
import com.example.chatbar.domain.worldbook.WorldBookEngine
import com.example.chatbar.ui.chat.appendCurrentUserAndCcbTailMessages
import com.example.chatbar.ui.chat.buildCcbFinalTailSystemPrompt
import com.example.chatbar.ui.chat.buildCcbStablePrefixMessages
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
import org.junit.Assert.assertNull
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
        previousAssistant: ChatMessage? = null,
        narrativeModesEnabled: Boolean = true,
        issueRoutineHistoryHeading: Boolean = false,
        checkHeldStop: Boolean = false,
    ): String {
        val cleanup = NarrativeHistoryEcho.forRequest(ChatNarrativeMode.FOCUS.takeIf { narrativeModesEnabled }, formatCard)
        val request = messages.toMutableList()
        userText?.let {
            cleanup.recordNarrativePrompt("", it)
            cleanup.recordCurrentLiteralTags(it)
            request.add(ChatApiMessage.text("user", it))
        }
        for (assistant in previousAssistant?.let(::listOf) ?: narrativeHistoryFixtures().takeIf { narrativeModesEnabled }.orEmpty()) {
            request.add(1, projectAssistant(cleanup, assistant, narrativeModesEnabled))
        }
        val routineHeading = PromptTemplates.sectionHeading(PromptTemplates.SECTION_PREVIOUS_TURN)
        if (issueRoutineHistoryHeading && previousAssistant != null) {
            request.add(1, ChatApiMessage.text("system", routineHeading))
            cleanup.recordDispatchedHistoryHeading(routineHeading)
        }
        narrativePrompt?.let {
            cleanup.recordNarrativePrompt(it, null)
            request.add(1, ChatApiMessage.text("system", it))
        }
        formatCard?.let {
            request.add(1, ChatApiMessage.text("system", PromptTemplates.currentTurnOutputRequirementsSystemPrompt(it, 300)))
        }
        val frames = parts.mapIndexed { index, part ->
            chunk(buildJsonObject { put("content", part) }.toString(), "game_content", index = index)
        }
        val terminal = listOf(chunk(reason = "tool_calls"), "[DONE]")
        if (checkHeldStop) {
            val witness = """{"choices":[{"delta":{"reasoning_content":"All calls sent."}}]}"""
            GatedExperimentalResponse(sse(frames + witness), sse(terminal)).use { server ->
                var reader: (() -> List<String>)? = null
                val visible = StringBuilder()
                val last = withTimeout(5_000) {
                    service().streamChat("grouped-tool-echo-stop", request, model(server.baseUrl),
                        onToolReplyPrefixReader = { reader = it },
                    ).onEach { if (it is StreamEvent.Delta) visible.append(it.text) }.first { it is StreamEvent.ReasoningDelta }
                }
                assertEquals(StreamEvent.ReasoningDelta("All calls sent."), last)
                assertEquals(parts.first(), visible.toString())
                assertFalse(server.tailSent)
                val buffered = requireNotNull(reader).invoke()
                assertEquals(parts, buffered)
                val joined = buffered.joinToString("\n")
                val lengths = buffered.map(String::length)
                assertEquals("Held Stop", expected, cleanup.completedBody(joined, interrupted = true, partLengths = lengths))
                assertEquals("Joined incremental Preview", expected, cleanup.streamingPreview().appendedBody(joined, 0, lengths))
            }
        }
        val response = sse(frames + terminal)
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
            if (userText != null) assertTrue(sentHistory.any {
                val item = it.jsonObject
                item.getValue("role").jsonPrimitive.content == "user" &&
                    item.getValue("content").jsonPrimitive.content == userText
            })
            if (issueRoutineHistoryHeading) {
                assertEquals(previousAssistant != null, sentHistory.any {
                    val item = it.jsonObject
                    item.getValue("role").jsonPrimitive.content == "system" &&
                        item.getValue("content").jsonPrimitive.content == routineHeading
                })
                assertTrue(sentHistory.none { "<历史回复" in it.jsonObject.getValue("content").jsonPrimitive.content })
            }
            for (source in request.drop(1).dropLast(1)) {
                assertTrue(sentHistory.any { it.jsonObject.getValue("content") == source.content })
            }
            val done = cleanup.completedBody(raw, partLengths = completion?.toolReplyPartLengths.orEmpty())
            assertEquals("Done", expected, done)
            assertEquals("Preview", expected, cleanup.completedBody(raw, partLengths = lengths, streaming = true))
            assertEquals("Stop", expected, cleanup.completedBody(raw, partLengths = lengths, interrupted = true))
            done
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
    fun `headed paired choices can continue their first numbered item without keeping the history frame`() = runBlocking {
        val mode = ChatNarrativeMode.FOCUS
        val story = "The lamp lit up."
        val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "The previous scene.").copy(narrativeMode = mode)
        val wrapped = PromptTemplates.narrativeModeHistoryReply(story, mode)
        val suffix = "\n\n---\n**【行动选项】**\n\n1. **[Open the door]()**\n   Wait for the latch to click.\n2. **[Stay here]()**\n---"
        val example = "$wrapped\n\n---\n1. An ordinarily authored item.\n   It continues here.\n---"
        for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
            assertNarrativeReplyDeltas(
                listOf(example), listOf(example), example, previousAssistant = prior,
                experimental = experimental, toolCall = toolCall, requestNarrativeMode = mode,
            )
            val raw = wrapped + suffix
            val body = story + suffix
            assertNarrativeReplyDeltas(
                listOf(raw), listOf(body), body, previousAssistant = prior, checkHeldStop = true,
                experimental = experimental, toolCall = toolCall, requestNarrativeMode = mode,
            )
        }
    }

    @Test
    fun `partial headed choices with continuation stay visible while the real history wrapper is withheld`() = runBlocking {
        val mode = ChatNarrativeMode.FOCUS
        val story = "The lamp lit up."
        val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "The previous scene.").copy(narrativeMode = mode)
        val wrapper = PromptTemplates.narrativeModeHistoryReply(story, mode)
        val basic = "\n\n---\n**【行动选项】**\n\n1. **[Open the door]()**"
        val continuation = "$basic\n   Wait for the latch to click.\n2. **[Stay here]()**"
        val close = "\n---"
        for ((experimental, tool) in listOf(false to false, true to true)) {
            val example = "---\n1. An ordinarily authored item.\n   It continues here."
            for (literal in listOf(example, example.prependIndent("> "))) {
                val raw = "$wrapper\n\n$literal"
                assertNarrativeReplyDeltas(
                    listOf(raw), listOf(raw), raw, previousAssistant = prior,
                    experimental = experimental, toolCall = tool, requestNarrativeMode = mode, checkHeldStop = true,
                )
            }
            assertNarrativeReplyDeltas(
                listOf(wrapper + basic), listOf(story + basic), wrapper + basic, previousAssistant = prior,
                experimental = experimental, toolCall = tool, requestNarrativeMode = mode, checkHeldStop = true,
            )
            assertNarrativeReplyDeltas(
                listOf(wrapper, continuation, close), listOf(story, story + continuation, story + continuation + close),
                story + continuation + close, previousAssistant = prior, experimental = experimental, toolCall = tool,
                requestNarrativeMode = mode, checkHeldStop = true, releaseTailAfter = 1,
            )
            assertNarrativeReplyDeltas(
                listOf(wrapper + continuation), listOf(story + continuation), wrapper + continuation,
                previousAssistant = prior, experimental = experimental, toolCall = tool, requestNarrativeMode = mode, checkHeldStop = true,
            )
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
        assertNarrativeReplyStates(echoed, expected = original, previousAssistant = previous, experimental = false)
        val novel = story.replace("有人来了。", "天开始下雨。")
        val novelInner = PromptTemplates.narrativeModeHistoryReply(novel, mode)
        val novelEcho = PromptTemplates.narrativeModeHistoryReply(novelInner, mode)
        assertNarrativeReplyStates(novelEcho, expected = novel, previousAssistant = previous, experimental = false)
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
    fun `a raw inner wrapper in actual history does not inherit transport authority`() = runBlocking {
        val story = "风吹过长廊。"
        for (outerMode in ChatNarrativeMode.entries) {
            val innerMode = ChatNarrativeMode.entries.first { it != outerMode }
            val oldLiteral = PromptTemplates.narrativeModeHistoryReply("旧的字面样例。", innerMode)
            val literalInHistory = ChatMessage.create("fixture", MessageRole.ASSISTANT, oldLiteral).copy(narrativeMode = outerMode)
            val independentInner = ChatMessage.create("fixture", MessageRole.ASSISTANT, "另一段历史。").copy(narrativeMode = innerMode)
            val inner = PromptTemplates.narrativeModeHistoryReply(story, innerMode)
            val outer = PromptTemplates.narrativeModeHistoryReply(inner, outerMode)
            for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
                assertNarrativeReplyDeltas(
                    listOf(outer), listOf(inner), inner,
                    previousAssistant = literalInHistory, experimental = experimental, toolCall = toolCall,
                )
                assertNarrativeReplyDeltas(
                    listOf(outer), listOf(inner), inner,
                    experimental = experimental, toolCall = toolCall,
                )
                assertNarrativeReplyDeltas(
                    listOf(inner), listOf(story), story,
                    previousAssistant = independentInner, experimental = experimental, toolCall = toolCall,
                )
                assertNarrativeReplyDeltas(
                    listOf(outer), listOf(outer), outer,
                    previousAssistant = literalInHistory, experimental = experimental, toolCall = toolCall,
                    currentUserText = "Copy this text verbatim:\n$outer",
                )
                assertNarrativeReplyDeltas(
                    listOf(outer), listOf(inner), inner,
                    previousAssistant = literalInHistory, experimental = experimental, toolCall = toolCall,
                    formatCard = "本轮输出模板：\n$inner",
                )
            }
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
    fun `rendered character post-history preserves an explicitly requested standalone opener`() = runBlocking {
        for (mode in ChatNarrativeMode.entries) {
            val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "Earlier scene.").copy(narrativeMode = mode)
            val opening = PromptTemplates.narrativeModeHistoryReply("Previous scene.", mode).substringBefore('\n')
            val authored = "Please show the literal opening tag only on its own line, then explain it:\n$opening"
            val explanation = "Here is the explanation."
            val raw = "$opening\n$explanation"
            for (experimental in listOf(false, true)) {
                assertNarrativeReplyDeltas(
                    listOf(raw), listOf(raw), raw, previousAssistant = prior, experimental = experimental,
                    currentUserText = "Continue.", characterPostHistoryInstructions = authored,
                )
                assertNarrativeReplyDeltas(
                    listOf(raw), listOf(explanation), explanation, previousAssistant = prior, experimental = experimental,
                    currentUserText = "Continue.", characterPostHistoryInstructions = "An older log line:\n$opening",
                    finishReason = "length",
                )
            }
        }
    }

    @Test
    fun `rendered character post-history preserves an explicitly requested standalone closer`() = runBlocking {
        for (mode in ChatNarrativeMode.entries) {
            val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "Earlier scene.").copy(narrativeMode = mode)
            val sample = PromptTemplates.narrativeModeHistoryReply("Previous scene.", mode)
            val opening = sample.substringBefore('\n')
            val closing = sample.substringAfterLast('\n')
            val authored = "Please show the literal closing tag only on its own line:\n$closing"
            val caption = "The closing tag example:"
            for (experimental in listOf(false, true)) {
                for ((raw, expected) in listOf(
                    "$opening\n$caption\n$closing" to "$caption\n$closing",
                    "Here is the explanation.\n$closing" to "Here is the explanation.\n$closing",
                )) {
                    assertNarrativeReplyDeltas(
                        listOf(raw), listOf(expected), expected, previousAssistant = prior, experimental = experimental,
                        currentUserText = "Continue.", characterPostHistoryInstructions = authored,
                    )
                }
                val raw = "$opening\n$caption\n$closing"
                assertNarrativeReplyDeltas(
                    listOf(raw), listOf(caption), caption, previousAssistant = prior, experimental = experimental,
                    currentUserText = "Continue.", characterPostHistoryInstructions = "Earlier examples:\n$closing",
                )
            }
        }
    }

    @Test
    fun `a one-line current user template reserves both tags for different prose`() = runBlocking {
        val instruction = "Use exactly this XML template, replace [Story] with new prose and keep both tags verbatim:"
        val story = "She turned the page."
        for (mode in ChatNarrativeMode.entries) {
            val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "Earlier scene.").copy(narrativeMode = mode)
            val spacedSample = PromptTemplates.narrativeModeHistoryReply("[Story]", mode)
            val compactSample = spacedSample.replace("\n", "")
            val framed = PromptTemplates.narrativeModeHistoryReply(story, mode)
            for (experimental in listOf(false, true)) {
                for (sample in listOf(compactSample, spacedSample)) {
                    assertNarrativeReplyDeltas(
                        listOf(framed), listOf(framed), framed, previousAssistant = prior, experimental = experimental,
                        currentUserText = "$instruction\n$sample",
                    )
                }
                for (source in listOf(
                    "Reference sample:\n$compactSample",
                    "Use exactly this XML template, replace [Other] with new prose and keep both tags verbatim:\n$compactSample",
                )) {
                    assertNarrativeReplyDeltas(
                        listOf(framed), listOf(story), story, previousAssistant = prior, experimental = experimental,
                        currentUserText = source,
                    )
                }
            }
        }
    }

    @Test
    fun `the actual first-system character core can declare an output template`() = runBlocking {
        val story = "Gatekeeper turned the page."
        for (mode in ChatNarrativeMode.entries) {
            val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "Earlier scene.").copy(narrativeMode = mode)
            val sample = PromptTemplates.narrativeModeHistoryReply("[Story]", mode)
            val exactSource = "Copy this text verbatim:\n" +
                PromptTemplates.narrativeModeHistoryReply("{{char}} turned the page.", mode)
            val framed = PromptTemplates.narrativeModeHistoryReply(story, mode)
            for (experimental in listOf(false, true)) {
                for (source in listOf("Required output template (keep both tags verbatim):\n$sample", exactSource)) {
                    assertNarrativeReplyDeltas(
                        listOf(framed), listOf(framed), framed, previousAssistant = prior, experimental = experimental,
                        currentUserText = "Continue.", characterCoreInstructions = source,
                    )
                }
                for (source in listOf("", "This was an old history record:\n$sample")) {
                    assertNarrativeReplyDeltas(
                        listOf(framed), listOf(story), story, previousAssistant = prior, experimental = experimental,
                        currentUserText = "Continue.", characterCoreInstructions = source,
                    )
                }
            }
        }
    }

    @Test
    fun `a dispatched strong denial supersedes the earlier matching current user template only`() = runBlocking {
        val story = "She turned the page."
        val instruction = "Required output template (keep both tags verbatim):"
        for (mode in ChatNarrativeMode.entries) {
            val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "Earlier scene.").copy(narrativeMode = mode)
            val sample = PromptTemplates.narrativeModeHistoryReply("[Story]", mode)
            val otherMode = ChatNarrativeMode.entries.first { it != mode }
            val otherSample = PromptTemplates.narrativeModeHistoryReply("[Story]", otherMode)
            val reply = PromptTemplates.narrativeModeHistoryReply(story, mode)
            for (experimental in listOf(false, true)) {
                val deny = "Do not output the following history tags:"
                for ((suffix, expected) in listOf(
                    "$deny\n$sample" to story,
                    "$deny\n$otherSample" to reply,
                    "This is an old log entry:\n$sample" to reply,
                )) {
                    assertNarrativeReplyDeltas(
                        listOf(reply), listOf(expected), expected, previousAssistant = prior, experimental = experimental,
                        currentUserText = "$instruction\n$sample", strongPrompt = suffix,
                    )
                }
                val unprojected = PromptTemplates.narrativeModeHistoryReply(story, otherMode)
                assertNarrativeReplyDeltas(
                    listOf(unprojected), listOf(unprojected), unprojected, previousAssistant = prior, experimental = experimental,
                    currentUserText = "Continue.", strongPrompt = "$deny\n$otherSample",
                )
            }
        }
    }

    @Test
    fun `a historical reference in a format card does not reserve tags for novel prose`() = runBlocking {
        val story = "She turned the page."
        val reference = "Reference only — the following tags record older history; never reproduce this sample in the answer:"
        for (mode in ChatNarrativeMode.entries) {
            val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "Earlier scene.").copy(narrativeMode = mode)
            val sample = PromptTemplates.narrativeModeHistoryReply("An older turn.", mode)
            val reply = PromptTemplates.narrativeModeHistoryReply(story, mode)
            assertEquals(reply, NarrativeHistoryEcho.forFormatRepair("$reference\n$sample", "A plain draft.").completedBody(reply))
            for (experimental in listOf(false, true)) {
                for ((card, expected) in listOf(
                    "$reference\n$sample\nWrite plain narration." to story,
                    "Required output template (keep both tags verbatim):\n$sample" to reply,
                    "> $reference\n" + sample.prependIndent("> ") to story,
                    "$reference\n```xml\n$sample\n```" to story,
                    "The manual uses the caption `$reference`.\n$sample" to reply,
                )) {
                    assertNarrativeReplyDeltas(
                        listOf(reply), listOf(expected), expected, previousAssistant = prior, experimental = experimental,
                        currentUserText = "Continue.", formatCard = card,
                    )
                }
                assertNarrativeReplyDeltas(
                    listOf(reply), listOf(reply), reply, experimental = experimental, fixtureHistory = false,
                    currentUserText = "Continue.", formatCard = "$reference\n$sample",
                )
            }
        }
    }

    @Test
    fun `consecutive validated tool parts preserve an explicitly bounded log quotation`() = runBlocking {
        val lead = "Below is a literal quotation copied from the history log."
        val tail = "That was a verbatim quotation from the log."
        for (mode in ChatNarrativeMode.entries) {
            val sampleBody = "A literal example."
            val sample = PromptTemplates.narrativeModeHistoryReply(sampleBody, mode)
            val parts = listOf(lead, sample, tail)
            val joined = parts.joinToString("\n")
            assertNarrativeReplyStates(joined, joined, experimental = false)
            assertMultipartNarrativeReply(parts, joined)
            assertMultipartNarrativeReply(
                listOf(lead, sample.substringBeforeLast('\n'), sample.substringAfterLast('\n'), tail), joined,
            )
            val later = PromptTemplates.narrativeModeHistoryReply("A new scene.", mode)
            assertMultipartNarrativeReply(parts + later, "$joined\nA new scene.")
            assertMultipartNarrativeReply(listOf(lead, sample, "She moved to the next page."),
                "$lead\n$sampleBody\nShe moved to the next page.")
            assertMultipartNarrativeReply(listOf("She read the page.", sample, tail),
                "She read the page.\n$sampleBody\n$tail")
            assertMultipartNarrativeReply(listOf(lead, "```xml\n$sample\n```", tail),
                "$lead\n```xml\n$sample\n```\n$tail")
        }
    }

    @Test
    fun `a blank line adjacent to the log caption keeps an exact multipart quotation`(): Unit = runBlocking {
        val mode = ChatNarrativeMode.FOCUS
        val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "Earlier scene.").copy(narrativeMode = mode)
        val lead = "Below is a literal quotation copied from the history log."
        val tail = "That was a verbatim quotation from the log."
        val body = "A literal example."
        val sample = PromptTemplates.narrativeModeHistoryReply(body, mode)
        val newStory = "The lamp was still on."
        val frame = PromptTemplates.narrativeModeHistoryReply(newStory, mode)
        val parts = listOf("$lead\n", sample, tail)
        val expected = parts.joinToString("\n")
        assertNarrativeReplyDeltas(
            listOf(expected), listOf(expected), expected, experimental = false, previousAssistant = prior,
            currentUserText = "Continue.", requestNarrativeMode = mode,
        )
        assertMultipartNarrativeReply(
            listOf(lead, sample, tail), "$lead\n$sample\n$tail",
            userText = "Continue.", previousAssistant = prior, checkHeldStop = true,
        )
        assertMultipartNarrativeReply(
            listOf("She read the page.\n", frame, tail), "She read the page.\n\n$newStory\n$tail",
            userText = "Continue.", previousAssistant = prior,
        )
        assertMultipartNarrativeReply(
            listOf("$lead\n", frame, "She moved to the next page."), "$lead\n\n$newStory\nShe moved to the next page.",
            userText = "Continue.", previousAssistant = prior,
        )
        assertMultipartNarrativeReply(parts, expected, userText = "Continue.", previousAssistant = prior, checkHeldStop = true)
        assertMultipartNarrativeReply(
            listOf(lead, sample, "\n$tail"), "$lead\n$sample\n\n$tail",
            userText = "Continue.", previousAssistant = prior, checkHeldStop = true,
        )
        assertMultipartNarrativeReply(
            parts + frame, "$expected\n$newStory", userText = "Continue.", previousAssistant = prior,
        )
    }

    @Test
    fun `ambiguous inline multipart closure keeps the example without original-output evidence`(): Unit = runBlocking {
        val mode = ChatNarrativeMode.FOCUS
        val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "旧事。").copy(narrativeMode = mode)
        val previous = "雨势稍缓。"
        val closing = "</历史回复>"
        val first = "<历史回复 模式=\"聚焦\">\n他走上台阶。"
        val last = "她站在门边。"
        val inline = listOf(previous, first, "他推开门。$closing", last)
        val ownLine = listOf(previous, first, "他推开门。\n$closing", last)
        assertMultipartNarrativeReply(
            ownLine, "$previous\n他走上台阶。\n他推开门。\n$last",
            userText = "Continue.", previousAssistant = prior, checkHeldStop = true,
        )
        for (lead in listOf(previous, "The following shows the history metadata:")) {
            val ambiguous = listOf(lead) + inline.drop(1)
            assertMultipartNarrativeReply(
                ambiguous, ambiguous.joinToString("\n"), userText = "Continue.",
                previousAssistant = prior, checkHeldStop = true,
            )
        }
    }

    @Test
    fun `held multipart Stop retains only the bounded quotation from actual completed calls`() = runBlocking {
        val quoteLead = "Below is a literal quotation copied from the history log."
        val quoteTail = "That was a verbatim quotation from the log."
        for (mode in ChatNarrativeMode.entries) {
            val quote = PromptTemplates.narrativeModeHistoryReply("A literal example.", mode)
            val narrative = PromptTemplates.narrativeModeHistoryReply("A new scene.", mode)
            val parts = listOf(quoteLead, quote, quoteTail, narrative)
            val expected = (parts.take(3) + "A new scene.").joinToString("\n")
            val cleanup = NarrativeHistoryEcho.forRequest(mode, null)
            val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "Previous scene.").copy(narrativeMode = mode)
            val request = messages.toMutableList().apply { add(1, projectAssistant(cleanup, prior)) }
            val frames = parts.mapIndexed { index, part ->
                chunk(buildJsonObject { put("content", part) }.toString(), "game_content", index = index)
            }
            val witness = """{"choices":[{"delta":{"reasoning_content":"Four completed calls"}}]}"""
            GatedExperimentalResponse(
                sse(frames + witness), sse(listOf(chunk(reason = "tool_calls"), "[DONE]")),
            ).use { server ->
                var buffered: (() -> List<String>)? = null
                val seen = withTimeout(5_000) {
                    service().streamChat("bounded-quote-stop", request, model(server.baseUrl),
                        onToolReplyPrefixReader = { buffered = it }).first { it is StreamEvent.ReasoningDelta }
                }
                assertEquals(StreamEvent.ReasoningDelta("Four completed calls"), seen)
                assertFalse(server.tailSent)
                val stopped = checkNotNull(buffered).invoke()
                assertEquals(parts, stopped)
                assertTrue("Expected projected history in the sent request",
                    "Previous scene." in server.requestBody && "历史回复" in server.requestBody)
                assertEquals(
                    expected, cleanup.completedBody(stopped.joinToString("\n"), interrupted = true,
                        partLengths = stopped.map(String::length)),
                )
            }
        }
    }

    @Test
    fun `an explicitly named brace slot declares the same output shape as a square slot`() = runBlocking {
        val story = "The lamp flickered."
        for (mode in ChatNarrativeMode.entries) {
            val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "Earlier scene.").copy(narrativeMode = mode)
            val reply = PromptTemplates.narrativeModeHistoryReply(story, mode)
            for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
                for (slot in listOf("{story}", "[story]")) {
                    val instruction = "Use exactly this XML template, replace $slot with new prose and keep both tags verbatim:"
                    val template = PromptTemplates.narrativeModeHistoryReply(slot, mode)
                    for (sample in listOf(template, template.replace("\n", ""))) {
                        assertNarrativeReplyDeltas(
                            listOf(reply), listOf(reply), reply, previousAssistant = prior, experimental = experimental,
                            toolCall = toolCall, currentUserText = "$instruction\n$sample",
                        )
                    }
                }
                val slot = "{story}"
                val instruction = "Use exactly this XML template, replace $slot with new prose and keep both tags verbatim:"
                val template = PromptTemplates.narrativeModeHistoryReply(slot, mode)
                for (negative in listOf(
                    "This is a historical example:\n$template",
                    "$instruction\n" + PromptTemplates.narrativeModeHistoryReply("{state: true}", mode),
                    "$instruction\n" + PromptTemplates.narrativeModeHistoryReply("{story} and other prose", mode),
                    "$instruction\n" + PromptTemplates.narrativeModeHistoryReply("{{story}}", mode),
                    "$instruction\n" + PromptTemplates.narrativeModeHistoryReply("[story]", mode),
                )) {
                    assertNarrativeReplyDeltas(
                        listOf(reply), listOf(story), story, previousAssistant = prior, experimental = experimental,
                        toolCall = toolCall, currentUserText = negative,
                    )
                }
            }
        }
    }

    @Test
    fun `quoted possessive keeps the inner marker and still permits the true outer close`() = runBlocking {
        for (mode in ChatNarrativeMode.entries) {
            val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "Earlier scene.").copy(narrativeMode = mode)
            val sample = PromptTemplates.narrativeModeHistoryReply("History", mode)
            val opening = sample.substringBefore('\n')
            val closing = sample.substringAfterLast('\n')
            for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
                for ((start, end) in listOf('\'' to '\'', '‘' to '’')) {
                    val lead = "${start}The visitors${end} log contains this line:"
                    val intro = "$lead\n$closing"
                    val continuation = "\nThe passage continues here."
                    val body = "$intro$continuation$end"
                    assertNarrativeReplyDeltas(
                        listOf("$opening\n$intro", continuation, "$end\n$closing"),
                        listOf(intro, intro + continuation, body), body, previousAssistant = prior,
                        experimental = experimental, toolCall = toolCall,
                    )
                    val noWitness = PromptTemplates.narrativeModeHistoryReply(body, mode)
                    assertNarrativeReplyDeltas(
                        listOf(noWitness), listOf(noWitness), noWitness, fixtureHistory = false,
                        experimental = experimental, toolCall = toolCall,
                    )
                    for (plain in listOf(
                        "She said ${start}the visitors${end} arrived.",
                        "She called it ${start}Visitors${end}, then left.",
                        "The visitors${end} log is on the desk.",
                        "The visitors don't need another guide.",
                        "She said ${start}the visitors${end} wrote ${start}another log${end}.",
                        "She said ${start}the visitors${end} then told us:",
                        "${start}The visitors${end} arrived before sunrise.",
                    )) {
                        val wrapped = PromptTemplates.narrativeModeHistoryReply(plain, mode)
                        assertNarrativeReplyDeltas(
                            listOf(wrapped), listOf(plain), plain, previousAssistant = prior,
                            experimental = experimental, toolCall = toolCall,
                        )
                    }
                    val fenced = "```text\n$body\n```"
                    val frame = PromptTemplates.narrativeModeHistoryReply(fenced, mode)
                    assertNarrativeReplyDeltas(
                        listOf(frame), listOf(fenced), fenced, previousAssistant = prior,
                        experimental = experimental, toolCall = toolCall,
                    )
                }
            }
        }
    }

    @Test
    fun `format repair retains the marker inside an actual straight quoted possessive`() = runBlocking {
        val original = "The lamp opened."
        for (mode in ChatNarrativeMode.entries) {
            val sample = PromptTemplates.narrativeModeHistoryReply("Incorrect wrapper example.", mode)
            val card = "The following is a counterexample:\n$sample"
            val close = sample.substringAfterLast('\n')
            val body = "'The visitors' log contains this line:\n$close\nThe passage continues here.'"
            val raw = PromptTemplates.narrativeModeHistoryReply(body, mode)
            for (experimental in listOf(false, true)) {
                val frame = if (experimental) chunk(buildJsonObject { put("content", raw) }.toString(), "game_content")
                    else ordinaryDelta(raw)
                GatedExperimentalResponse(
                    sse(listOf(frame)), sse(listOf(chunk(reason = if (experimental) "tool_calls" else "stop"), "[DONE]")),
                ).use { server ->
                    val repair = MessageFormatRepairService(StreamingChatService(
                        experimentalApiModeEnabled = { experimental }, allowCleartextHttp = { true },
                    ))
                    val events = withTimeout(5_000) {
                        repair.streamRepair(original, card, null, model(server.baseUrl))
                            .onEach { if (it is StreamEvent.Delta) server.releaseTail() }.toList()
                    }
                    assertEquals(StreamEvent.Done, events.last())
                    val decoded = events.filterIsInstance<StreamEvent.Delta>().joinToString("") { it.text }
                    assertEquals(raw, decoded)
                    assertTrue("Expected the actual prohibition in the repair request", card.lineSequence().first() in server.requestBody)
                    val cleanup = NarrativeHistoryEcho.forFormatRepair(card, original)
                    assertEquals("Repair Preview, Stop and Done", listOf(body, body, body), listOf(
                        cleanup.completedBody(decoded, streaming = true),
                        cleanup.completedBody(decoded, interrupted = true),
                        cleanup.completedBody(decoded),
                    ))
                }
            }
        }
    }

    @Test
    fun `held repair Stop preserves the label introduced inside an unfinished possessive quote`() = runBlocking {
        val original = "A repaired first line."
        for (mode in ChatNarrativeMode.entries) {
            val sample = PromptTemplates.narrativeModeHistoryReply("Incorrect wrapper example.", mode)
            val card = "The following is a counterexample:\n$sample"
            val opening = sample.substringBefore('\n')
            val close = sample.substringAfterLast('\n')
            for ((start, end) in listOf('\'' to '\'', '‘' to '’')) {
                val body = "${start}The visitors${end} log contains this line:\n$close"
                val raw = "$opening\n$body"
                for (experimental in listOf(false, true)) {
                    val frame = if (experimental) chunk(buildJsonObject { put("content", raw) }.toString(), "game_content")
                        else ordinaryDelta(raw)
                    GatedExperimentalResponse(sse(listOf(frame)), sse(listOf(
                        chunk(reason = if (experimental) "tool_calls" else "stop"), "[DONE]",
                    ))).use { server ->
                        var readBuffered: (() -> List<String>)? = null
                        val repair = MessageFormatRepairService(StreamingChatService(
                            experimentalApiModeEnabled = { experimental }, allowCleartextHttp = { true },
                        ))
                        val preview = withTimeout(5_000) {
                            repair.streamRepair(original, card, null, model(server.baseUrl)) {
                                readBuffered = it
                            }.first { it is StreamEvent.Delta }
                        } as StreamEvent.Delta
                        assertFalse(server.tailSent)
                        val buffered = readBuffered?.invoke()?.takeIf(List<String>::isNotEmpty) ?: listOf(preview.text)
                        assertEquals(listOf(raw), buffered)
                        val cleanup = NarrativeHistoryEcho.forFormatRepair(card, original)
                        assertEquals("Repair Preview and actual Stop", listOf(body, body), listOf(
                            cleanup.completedBody(preview.text, streaming = true),
                            cleanup.completedBody(buffered.single(), interrupted = true),
                        ))
                    }
                }
            }
        }
    }

    @Test
    fun `actual rendered supplementary settings can declare reply tags in the sent stable prefix`() = runBlocking {
        val story = "The door opens."
        for (mode in ChatNarrativeMode.entries) {
            val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "An earlier story.").copy(narrativeMode = mode)
            val raw = PromptTemplates.narrativeModeHistoryReply(story, mode)
            val sample = PromptTemplates.narrativeModeHistoryReply("[story]", mode)
            val exposed = "Use this XML template, replace [story] with new prose and keep both tags verbatim:\n$sample"
            for (experimental in listOf(false, true)) {
                val source = "Character is {{char}}.\n\n$exposed"
                assertNarrativeReplyDeltas(
                    listOf(raw), listOf(raw), raw, previousAssistant = prior, experimental = experimental,
                    currentUserText = "Continue.", supplementaryInstructions = source,
                )
                val modePrompt = PromptTemplates.narrativeModeSystemPrompt(mode)
                val echoed = "${modePrompt.lineSequence().first()}\n$raw"
                assertNarrativeReplyDeltas(
                    listOf(echoed), listOf(raw), raw, previousAssistant = prior, experimental = experimental,
                    currentUserText = "Continue.", narrativePrompt = modePrompt, supplementaryInstructions = source,
                )
                for (reference in listOf("Earlier history:\n$sample", "```text\n$exposed\n```")) {
                    assertNarrativeReplyDeltas(
                        listOf(raw), listOf(story), story, previousAssistant = prior, experimental = experimental,
                        currentUserText = "Continue.", supplementaryInstructions = reference,
                    )
                }
                assertNarrativeReplyDeltas(
                    listOf(raw), listOf(story), story, previousAssistant = prior, experimental = experimental,
                    currentUserText = "Do not output the following history tags:\n$sample", supplementaryInstructions = source,
                )
            }
        }
    }

    @Test
    fun `no-user greeting regeneration ignores an unsent END-only history counterexample`() = runBlocking {
        val story = "A card is pinned by the door."
        for (mode in ChatNarrativeMode.entries) {
            val sample = PromptTemplates.narrativeModeHistoryReply("Example metadata", mode)
            val card = "The following is a counterexample:\n$sample"
            val layers = PromptAssembler().assembleCachePromptLayers(CharacterCard.create("Gatekeeper"), narrativeMode = mode)
            val requirements = PromptTemplates.currentTurnOutputRequirementsSystemPrompt(card, 300)
            val raw = PromptTemplates.narrativeModeHistoryReply(story, mode)
            for (position in FormatPromptPosition.entries) for (experimental in listOf(false, true)) {
                val request = buildCcbStablePrefixMessages(
                    layers.coreSystemPrompt, layers.stableContextSystemPrompt, requirements, position,
                    narrativeModeSystemPrompt = layers.narrativeModeSystemPrompt,
                ) + ChatApiMessage.text("system", PromptTemplates.CCB_CONTINUATION_SYSTEM_PROMPT.trimIndent().trim())
                val cleanup = NarrativeHistoryEcho.forDispatchedRequest(mode, card, position, hasCurrentUser = false)
                cleanup.recordNarrativePrompt(layers.narrativeModeSystemPrompt, null)
                assertFalse(request.any { it.role == "user" &&
                    it.content.jsonPrimitive.content != PromptTemplates.CCB_CREATIVE_CONTRACT_USER_PROMPT.trimIndent().trim() })
                TaskResponseServer(sse(listOf(ordinaryDelta(raw), chunk(reason = "stop"), "[DONE]")), "text/event-stream").use { server ->
                    val service = StreamingChatService(experimentalApiModeEnabled = { experimental }, allowCleartextHttp = { true })
                    val events = withTimeout(5_000) { service.streamChat("initial-greeting", request, model(server)).toList() }
                    assertEquals(StreamEvent.Done, events.last())
                    assertEquals(raw, events.filterIsInstance<StreamEvent.Delta>().joinToString("") { it.text })
                    assertEquals(position.includesStart, card.lineSequence().first() in server.requestBody)
                    assertTrue(PromptTemplates.SECTION_NARRATIVE_MODE in server.requestBody)
                    val expected = if (position.includesStart) story else raw
                    assertEquals("Reply Preview, Stop and Done", listOf(expected, expected, expected), listOf(
                        cleanup.streamingPreview().appendedBody(raw, 0),
                        cleanup.completedBody(raw, interrupted = true),
                        cleanup.completedBody(raw),
                    ))
                }
            }
        }
    }

    @Test
    fun `actual automatic repair excludes explicitly prohibited user marker occurrences`() = runBlocking {
        val original = "Mira enters."
        val repaired = "Mira: Mira enters."
        for (mode in ChatNarrativeMode.entries) {
            val raw = PromptTemplates.narrativeModeHistoryReply(repaired, mode)
            val close = raw.substringAfterLast('\n')
            val sample = PromptTemplates.narrativeModeHistoryReply("An old turn.", mode)
            val proven = "The following is a counterexample:\n$sample\n\nStart with the character name."
            val denied = "Do not output the following history tags:\n$close"
            val deniedPair = "Do not output the following history tags:\n$sample"
            for (user in listOf(denied, deniedPair, null)) for (experimental in listOf(false, true)) {
                assertActualRepairBody(raw, repaired, proven, original, user, experimental)
            }
            assertActualRepairBody(raw, raw, "Start with the character name.", original, denied, experimental = false)
        }
    }

    @Test
    fun `repeated paragraph openers keep literal markers across real streams and tool parts`() = runBlocking {
        for (mode in ChatNarrativeMode.entries) {
            val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "An earlier story.").copy(narrativeMode = mode)
            val sample = PromptTemplates.narrativeModeHistoryReply("Shape.", mode)
            val closing = sample.substringAfterLast('\n')
            val paragraphs = "She read the following, “The first paragraph gives context.\n\n" +
                "“The second paragraph gives this exact sample:\n$closing\nThen she stopped.”"
            val simple = "She read, “The second paragraph gives this exact sample:\n$closing\nThen she stopped.”"
            val nested = "She read, “The first phrase was “quoted.”\n$closing\nThen she stopped.”"
            val closed = "She read, “The explanation ends here.”"
            val paddedParagraphs = paragraphs.replace("\n\n", "\n \n\n").replace("\n", "\r\n")
            for (body in listOf(paragraphs, paddedParagraphs, simple, nested, closed)) {
                val raw = PromptTemplates.narrativeModeHistoryReply(body, mode)
                for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
                    assertNarrativeReplyDeltas(
                        listOf(raw), listOf(body), body, previousAssistant = prior, experimental = experimental, toolCall = toolCall,
                    )
                }
                val card = "The following is a counterexample:\n$sample"
                assertActualRepairBody(raw, body, card, "A plain draft.", null, experimental = false)
            }
            val raw = PromptTemplates.narrativeModeHistoryReply(paragraphs, mode)
            val separator = raw.indexOf("\n\n")
            assertMultipartNarrativeReply(listOf(raw.substring(0, separator), raw.substring(separator + 1)), paragraphs)
        }
    }

    @Test
    fun `standard German opening and closing quotation marks retain the literal history tag`() = runBlocking {
        for (mode in ChatNarrativeMode.entries) {
            val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "An earlier story.").copy(narrativeMode = mode)
            val sample = PromptTemplates.narrativeModeHistoryReply("Shape.", mode)
            val closing = sample.substringAfterLast('\n')
            for ((open, close) in listOf('„' to '“', '“' to '”')) {
                val body = "Sie las vor: ${open}Hier beginnt das Beispiel:\n$closing\nDas war das Beispiel.$close"
                val raw = PromptTemplates.narrativeModeHistoryReply(body, mode)
                for (experimental in listOf(false, true)) {
                    assertNarrativeReplyDeltas(
                        listOf(raw), listOf(body), body, previousAssistant = prior, experimental = experimental,
                    )
                    assertActualRepairBody(raw, body, "The following is a counterexample:\n$sample",
                        "Ein einfacher Entwurf.", null, experimental)
                }
            }
        }
    }

    private suspend fun assertActualRepairBody(
        raw: String,
        expected: String,
        card: String,
        original: String,
        user: String?,
        experimental: Boolean,
    ) {
        val prepared = MessageFormatRepairService.PreparedRequest(original, card, null)
        val delta = if (experimental) chunk(buildJsonObject { put("content", raw) }.toString(), "game_content")
            else ordinaryDelta(raw)
        val tail = sse(listOf(chunk(reason = if (experimental) "tool_calls" else "stop"), "[DONE]"))
        for (stop in listOf(false, true)) GatedExperimentalResponse(sse(listOf(delta)), tail).use { server ->
            val service = MessageFormatRepairService(StreamingChatService(
                experimentalApiModeEnabled = { experimental }, allowCleartextHttp = { true },
            ))
            var reader: (() -> List<String>)? = null
            val stream = service.streamRepair(prepared, model(server.baseUrl)) { reader = it }
            val body = withTimeout(5_000) {
                if (stop) (stream.first { it is StreamEvent.Delta } as StreamEvent.Delta).text
                else {
                    val events = stream.onEach { if (it is StreamEvent.Delta) server.releaseTail() }.toList()
                    assertEquals(StreamEvent.Done, events.last())
                    events.filterIsInstance<StreamEvent.Delta>().joinToString("") { it.text }
                }
            }
            assertEquals(!stop, server.tailSent)
            assertEquals(raw, body)
            if (stop && experimental) {
                val complete = checkNotNull(reader).invoke().ifEmpty { listOf(body) }
                assertEquals(listOf(raw), complete)
            }
            val request = Json.parseToJsonElement(server.requestBody).jsonObject
            val sent = if (experimental) {
                val encoded = request.getValue("tools").jsonArray.map { it.jsonObject.getValue("function").jsonObject }
                    .single { it.getValue("name").jsonPrimitive.content == "context" }
                    .getValue("description").jsonPrimitive.content
                Json.parseToJsonElement(encoded + "\"}]").jsonArray
            } else request.getValue("messages").jsonArray
            val wireUser = sent.asSequence().map { it.jsonObject }
                .filter { it.getValue("role").jsonPrimitive.content == "user" }
                .map { it.getValue("content").jsonPrimitive.content }
                .single { "{待修复消息}\n" in it }
            assertEquals(PromptTemplates.messageFormatRepairUserPrompt(card, null, original), wireUser)
            user?.let { assertFalse(it.lineSequence().first() in wireUser) }
            val cleanup = prepared.historyCleanup()
            assertEquals("Repair Preview and ${if (stop) "actual Stop" else "Done"}", listOf(expected, expected), listOf(
                cleanup.streamingPreview().appendedBody(body, 0),
                cleanup.completedBody(body, interrupted = stop),
            ))
        }
    }

    @Test
    fun `rendered player override and global persona can explicitly declare the response template`() = runBlocking {
        for (mode in ChatNarrativeMode.entries) {
            val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "An earlier story.").copy(narrativeMode = mode)
            val prose = "The keeper opened the gate."
            val reply = PromptTemplates.narrativeModeHistoryReply(prose, mode)
            val template = PromptTemplates.narrativeModeHistoryReply("[Story]", mode)
            val author = "The player is {{user}}.\n\n" +
                "Use exactly this XML template, replace [Story] with new prose and keep both tags verbatim:\n$template"
            for (experimental in listOf(false, true)) {
                for ((override, global) in listOf(author to "Unused global persona.", " " to author)) {
                    assertNarrativeReplyDeltas(
                        listOf(reply), listOf(reply), reply, previousAssistant = prior, experimental = experimental,
                        currentUserText = "Continue.", playerOverride = override, globalPersona = global,
                    )
                }
                for (setting in listOf("Earlier turn quoted here:\n$template", "```text\n$author\n```")) {
                    assertNarrativeReplyDeltas(
                        listOf(reply), listOf(prose), prose, previousAssistant = prior, experimental = experimental,
                        currentUserText = "Continue.", playerOverride = setting, globalPersona = author,
                    )
                }
                val forbidden = "Do not output the following history tags:\n$template"
                assertNarrativeReplyDeltas(
                    listOf(reply), listOf(prose), prose, previousAssistant = prior, experimental = experimental,
                    currentUserText = forbidden, playerOverride = author, globalPersona = "Unused global persona.",
                )
                assertNarrativeReplyDeltas(
                    listOf(reply), listOf(reply), reply, previousAssistant = prior, experimental = experimental,
                    currentUserText = "Continue.", playerOverride = author, supplementaryInstructions = forbidden,
                )
            }
        }
    }

    @Test
    fun `a witnessed frame does not resurrect a standalone identifiable partial marker on Done`() = runBlocking {
        val prose = "The keeper opened the gate."
        val partial = "</历史回"
        for (mode in ChatNarrativeMode.entries) {
            val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "An earlier story.").copy(narrativeMode = mode)
            val frame = PromptTemplates.narrativeModeHistoryReply(prose, mode)
            val opening = frame.substringBefore('\n')
            for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
                assertNarrativeReplyDeltas(
                    listOf("$opening\n$prose", "\n$partial"), listOf(prose, prose), prose, previousAssistant = prior,
                    experimental = experimental, toolCall = toolCall,
                )
                val literal = "She typed `$partial` into the console."
                val wrapped = PromptTemplates.narrativeModeHistoryReply(literal, mode)
                assertNarrativeReplyDeltas(
                    listOf(wrapped), listOf(literal), literal, previousAssistant = prior,
                    experimental = experimental, toolCall = toolCall,
                )
                for (unwitnessed in listOf("$prose\n$partial", "$opening\n$prose\n$partial")) {
                    assertNarrativeReplyDeltas(
                        listOf(unwitnessed), listOf(unwitnessed), unwitnessed, fixtureHistory = false,
                        experimental = experimental, toolCall = toolCall,
                    )
                }
            }
        }
    }

    @Test
    fun `repair uses the normalized rendered card actually sent over the wire`() = runBlocking {
        val original = "  The keeper turned the brass key. \n"
        val repaired = "The keeper opened the gate."
        for (mode in ChatNarrativeMode.entries) {
            val sample = PromptTemplates.narrativeModeHistoryReply("An earlier story from {{char}}.", mode)
            val card = PlaceholderRenderer.render("Do not output these history tags in your reply:\n$sample", null, "Keeper")
            val raw = PromptTemplates.narrativeModeHistoryReply(repaired, mode)
            for (experimental in listOf(false, true)) {
                assertActualRepairBody(raw, repaired, "    $card  \n", original, null, experimental)
                assertActualRepairBody(raw, repaired, card, original, null, experimental)
                assertActualRepairBody(raw, raw, "    Output template:\n$sample  \n", original, null, experimental)
                assertActualRepairBody(raw, raw, card, sample, null, experimental)
            }
        }
    }

    @Test
    fun `unused generation user text does not reserve repair reply tags`() = runBlocking {
        val original = "The keeper turned the brass key."
        val repaired = "The keeper opened the gate."
        for (mode in ChatNarrativeMode.entries) {
            val sample = PromptTemplates.narrativeModeHistoryReply("An earlier story.", mode)
            val card = "Do not output these history tags in your reply:\n$sample"
            val irrelevant = "A player once used this example.\n" + PromptTemplates.narrativeModeHistoryReply("An unused literal.", mode)
            val raw = PromptTemplates.narrativeModeHistoryReply(repaired, mode)
            for (experimental in listOf(false, true)) {
                assertActualRepairBody(raw, repaired, card, original, irrelevant, experimental)
                // When these same bytes are the original assistant body, they really are in the repair request.
                assertActualRepairBody(raw, raw, card, irrelevant, null, experimental)
            }
        }
    }

    @Test
    fun `positive XML output intent uses the same visible slot template in actual editable and format layers`() = runBlocking {
        val story = "The harbor lights came on."
        val directive = "Use the following XML template for each reply and keep both tags verbatim:"
        for (mode in ChatNarrativeMode.entries) {
            val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "An earlier story.").copy(narrativeMode = mode)
            val raw = PromptTemplates.narrativeModeHistoryReply(story, mode)
            val sample = PromptTemplates.narrativeModeHistoryReply("[story]", mode)
            for (experimental in listOf(false, true)) {
                for (example in listOf(sample, sample.replace("\n", ""), sample.replace("[story]", "{story}"))) {
                    val declaration = "$directive\n$example"
                    for (layer in listOf("user", "core", "supplementary", "player", "tail", "format")) {
                        assertNarrativeReplyDeltas(
                            listOf(raw), listOf(raw), raw, experimental = experimental, previousAssistant = prior,
                            currentUserText = declaration.takeIf { layer == "user" } ?: "Continue.",
                            characterCoreInstructions = declaration.takeIf { layer == "core" },
                            supplementaryInstructions = declaration.takeIf { layer == "supplementary" },
                            playerOverride = declaration.takeIf { layer == "player" },
                            characterPostHistoryInstructions = declaration.takeIf { layer == "tail" },
                            formatCard = declaration.takeIf { layer == "format" },
                        )
                    }
                }
                for (example in listOf(
                    "Earlier the story used the following XML template and kept both tags verbatim:\n$sample",
                    "Use the following XML template for each reply:\n$sample",
                    "$directive\n" + PromptTemplates.narrativeModeHistoryReply("{state: true}", mode),
                    ("$directive\n$sample").prependIndent("> "),
                    "$directive\n" + sample.replace("[story]", "[story] plus general prose"),
                )) {
                    assertNarrativeReplyDeltas(
                        listOf(raw), listOf(story), story, experimental = experimental, previousAssistant = prior,
                        currentUserText = example,
                    )
                }
                val forbidden = "Do not output these history tags:\n$directive\n$sample"
                assertNarrativeReplyDeltas(
                    listOf(raw), listOf(story), story, experimental = experimental, previousAssistant = prior,
                    currentUserText = forbidden,
                )
                assertNarrativeReplyDeltas(
                    listOf(raw), listOf(story), story, experimental = experimental, previousAssistant = prior,
                    currentUserText = "Continue.", formatCard = forbidden,
                )
                assertActualRepairBody(raw, raw, "$directive\n$sample", "A plain draft.", null, experimental)
            }
        }
    }

    @Test
    fun `an explicit verbatim title survives from each actually sent editable layer`() = runBlocking {
        val body = "The harbor lights came on."
        for (mode in ChatNarrativeMode.entries) {
            val modePrompt = PromptTemplates.narrativeModeSystemPrompt(mode)
            val heading = modePrompt.lineSequence().first()
            val raw = "$heading\n$body"
            val authored = "Begin each reply with the following exact title printed verbatim:\n$heading"
            val titlePrefix = heading.substringBefore('（')
            val neutral = "Continue."
            for (experimental in listOf(false, true)) {
                for (layer in listOf("user", "core", "supplementary", "player", "tail", "format")) {
                    assertNarrativeReplyDeltas(
                        listOf(titlePrefix, raw.removePrefix(titlePrefix)), listOf(titlePrefix, raw), raw,
                        experimental = experimental, narrativePrompt = modePrompt,
                        currentUserText = authored.takeIf { layer == "user" } ?: neutral,
                        characterCoreInstructions = authored.takeIf { layer == "core" },
                        supplementaryInstructions = authored.takeIf { layer == "supplementary" },
                        playerOverride = authored.takeIf { layer == "player" },
                        characterPostHistoryInstructions = authored.takeIf { layer == "tail" },
                        formatCard = authored.takeIf { layer == "format" },
                    )
                }
                for (example in listOf(
                    "Previous examples used this title:\n$heading",
                    authored.prependIndent("> "),
                    "```text\n$authored\n```",
                )) {
                    assertNarrativeReplyDeltas(
                        listOf(raw), listOf(body), body, experimental = experimental, narrativePrompt = modePrompt,
                        currentUserText = neutral, supplementaryInstructions = example,
                    )
                }
                assertNarrativeReplyDeltas(
                    listOf(raw), listOf(body), body, experimental = experimental, narrativePrompt = modePrompt, currentUserText = neutral,
                )
            }
        }
    }

    @Test
    fun `direct current no XML overrides only the inherited template for the reply`() = runBlocking {
        val story = "The harbor lights came on."
        for (mode in ChatNarrativeMode.entries) {
            val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "An earlier story.").copy(narrativeMode = mode)
            val raw = PromptTemplates.narrativeModeHistoryReply(story, mode)
            val sample = PromptTemplates.narrativeModeHistoryReply("[story]", mode)
            val template = "Use exactly this XML template, replace [story] with new prose and keep both tags verbatim:\n$sample"
            val opposite = ChatNarrativeMode.entries.first { it != mode }
            val wrongMode = "Do not output these history tags:\n" + PromptTemplates.narrativeModeHistoryReply("[story]", opposite)
            val noXml = "Continue in plain prose. This turn use no XML."
            for (experimental in listOf(false, true)) {
                assertNarrativeReplyDeltas(
                    listOf(raw), listOf(story), story, experimental = experimental, previousAssistant = prior,
                    previousUserText = template, currentUserText = noXml,
                )
                for (control in listOf(
                    "Prefer natural prose.", "Continue.", wrongMode,
                    "The manual says, \"This turn use no XML.\"",
                    "> This turn use no XML.", "```text\nThis turn use no XML.\n```",
                )) {
                    assertNarrativeReplyDeltas(
                        listOf(raw), listOf(raw), raw, experimental = experimental, previousAssistant = prior,
                        previousUserText = template, currentUserText = control,
                    )
                }
                assertNarrativeReplyDeltas(
                    listOf(raw), listOf(raw), raw, experimental = experimental, previousAssistant = prior,
                    previousUserText = template, currentUserText = noXml, formatCard = "Output template:\n$sample",
                )
                assertNarrativeReplyDeltas(
                    listOf(raw), listOf(raw), raw, experimental = experimental, previousAssistant = prior,
                    previousUserText = "Copy this text verbatim:\n$raw",
                    currentUserText = noXml,
                )
                assertNarrativeReplyDeltas(
                    listOf(raw), listOf(raw), raw, experimental = experimental, previousAssistant = prior,
                    previousUserText = template, currentUserText = noXml, characterPostHistoryInstructions = template,
                )
            }
        }
    }

    @Test
    fun `validated call boundaries do not resolve a still continuing lazy Markdown list close`() = runBlocking {
        val closing = "</历史回复>"
        for (mode in ChatNarrativeMode.entries) {
            val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "旧正文。").copy(narrativeMode = mode)
            val opening = PromptTemplates.narrativeModeHistoryReply("Body", mode).substringBefore('\n')
            val lazyParts = listOf(opening, "- Here is the example", closing, "  It is an example.", closing)
            val lazyRaw = lazyParts.joinToString("\n")
            assertNarrativeReplyDeltas(
                listOf(lazyRaw), listOf(lazyRaw), lazyRaw, previousAssistant = prior, experimental = false,
            )
            assertMultipartNarrativeReply(lazyParts, lazyRaw)
            // Match the native fixture's visible first call without changing the joined Markdown.
            assertMultipartNarrativeReply(listOf(lazyParts.take(2).joinToString("\n")) + lazyParts.drop(2), lazyRaw)
            val explicit = listOf(opening, "- Here is a tag example:", "  $closing", "  It is an example.", closing)
            assertMultipartNarrativeReply(explicit, explicit.subList(1, 4).joinToString("\n"))
            val first = PromptTemplates.narrativeModeHistoryReply("First complete scene.", mode)
            val second = PromptTemplates.narrativeModeHistoryReply("Second complete scene.", mode)
            assertMultipartNarrativeReply(listOf(first, second), "First complete scene.\nSecond complete scene.")
        }
    }

    @Test
    fun `an exact emitted history projection keeps every original body byte`() = runBlocking {
        for (mode in ChatNarrativeMode.entries) {
            for (body in listOf(
                "风吹过长廊。  ",
                "A hard break  \r\n    An indented line.  ",
                "\n    val key = 1\n",
                "\r\n    An indented line.  \r\n",
                "    An indented line.",
            )) {
                val historical = ChatMessage.create("fixture", MessageRole.ASSISTANT, body).copy(narrativeMode = mode)
                val frame = PromptTemplates.narrativeModeHistoryReply(body, mode)
                for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
                    assertNarrativeReplyDeltas(
                        listOf(frame), listOf(body), body,
                        previousAssistant = historical, experimental = experimental, toolCall = toolCall,
                    )
                    val authored = "Copy this text verbatim:\n$frame"
                    assertNarrativeReplyDeltas(
                        listOf(frame), listOf(frame), frame,
                        previousAssistant = historical, experimental = experimental, toolCall = toolCall,
                        currentUserText = authored,
                    )
                }
            }
            val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "Different previous prose.").copy(narrativeMode = mode)
            val plain = "A new sentence."
            val novel = PromptTemplates.narrativeModeHistoryReply("$plain  ", mode)
            assertNarrativeReplyDeltas(listOf(novel), listOf(plain), plain, previousAssistant = prior, experimental = false)
        }
    }

    @Test
    fun `actually sent mode blocks lose the copied core or switch line with the title`() = runBlocking {
        val body = "A kettle rang."
        for (mode in ChatNarrativeMode.entries) {
            val other = ChatNarrativeMode.entries.first { it != mode }
            val modePrompt = PromptTemplates.narrativeModeSystemPrompt(mode)
            val modeTail = PromptTemplates.narrativeModeTailSystemPrompt(mode, other)
            val blocks = listOf(modePrompt, modeTail).map { it.lineSequence().take(2).joinToString("\n") }
            for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
                for (block in blocks) {
                    val raw = "$block\n$body"
                    var cleaned: String? = null
                    assertNarrativeReplyDeltas(
                        listOf(block, "\n$body"), listOf("", body), body,
                        narrativePrompt = modePrompt, narrativeTail = modeTail, currentUserText = "Continue.",
                        experimental = experimental, toolCall = toolCall, onCompleted = { cleaned = it },
                    )
                    val saved = ChatMessage.create("fixture", MessageRole.ASSISTANT, checkNotNull(cleaned))
                        .copy(narrativeMode = mode)
                    val reloaded = Json.decodeFromString(ChatMessage.serializer(), Json.encodeToString(ChatMessage.serializer(), saved))
                    assertEquals(body, reloaded.displayContent)
                    val next = "She opened the cupboard."
                    assertNarrativeReplyDeltas(
                        listOf(next), listOf(next), next, previousAssistant = reloaded,
                        narrativePrompt = modePrompt, currentUserText = "Go on.",
                        experimental = experimental, toolCall = toolCall,
                    )
                    assertNarrativeReplyDeltas(
                        listOf(raw), listOf(raw), raw, narrativePrompt = modePrompt, narrativeTail = modeTail,
                        currentUserText = "Copy this text verbatim:\n$raw", experimental = experimental, toolCall = toolCall,
                    )
                    val independent = block.substringAfter('\n') + "\n$body"
                    assertNarrativeReplyDeltas(
                        listOf(independent), listOf(independent), independent, narrativePrompt = modePrompt, narrativeTail = modeTail,
                        currentUserText = "Continue.", experimental = experimental, toolCall = toolCall,
                    )
                    val varied = block.substringAfter('\n') + " Different wording."
                    assertNarrativeReplyDeltas(
                        listOf(block.substringBefore('\n') + "\n$varied\n$body"),
                        listOf("$varied\n$body"), "$varied\n$body",
                        narrativePrompt = modePrompt, narrativeTail = modeTail, currentUserText = "Continue.",
                        experimental = experimental, toolCall = toolCall,
                    )
                }
                val unsent = PromptTemplates.narrativeModeSystemPrompt(other).lineSequence().take(2).joinToString("\n")
                assertNarrativeReplyDeltas(
                    listOf("$unsent\n$body"), listOf("$unsent\n$body"), "$unsent\n$body",
                    narrativePrompt = modePrompt, narrativeTail = modeTail, currentUserText = "Continue.",
                    experimental = experimental, toolCall = toolCall,
                )
            }
        }
    }

    @Test
    fun `direct negative heading instructions do not reserve titles with trailing punctuation`() = runBlocking {
        val body = "The door closed."
        for (mode in ChatNarrativeMode.entries) {
            val prompt = PromptTemplates.narrativeModeSystemPrompt(mode)
            val title = prompt.lineSequence().first()
            val raw = "$title\n$body"
            for (experimental in listOf(false, true)) {
                for (deny in listOf("Do not output the internal heading: $title.", "不要在正文里复述$title。")) {
                    assertNarrativeReplyDeltas(
                        listOf(raw), listOf(body), body, currentUserText = deny,
                        narrativePrompt = prompt, experimental = experimental,
                    )
                }
                for (positive in listOf(
                    "Print this title verbatim: $title.",
                    "She read the quoted words \"Do not output the internal heading: $title.\"",
                    "Copy this text verbatim:\n$raw",
                )) {
                    assertNarrativeReplyDeltas(
                        listOf(raw), listOf(raw), raw, currentUserText = positive,
                        narrativePrompt = prompt, experimental = experimental,
                    )
                }
            }
        }
    }

    @Test
    fun `explicit citation and separate explanation sentence keep the actual latest XML projection`() = runBlocking {
        val mode = ChatNarrativeMode.FOCUS
        val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "The rain stopped.").copy(narrativeMode = mode)
        val projection = PromptTemplates.narrativeModeHistoryReply(prior.displayContent, mode)
        val explanation = "\n\nThe opening element identifies the reply mode."
        val raw = projection + explanation
        val directive = "Quote the last reply verbatim, including both XML history tags."
        val followup = "Then explain what the quotation demonstrates."
        val request = "$directive\n$followup"
        for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
            for (user in listOf("Continue.", "> $directive\n> $followup", "A manual example:\n$request", "$directive\nThen omit the tags.")) {
                val body = prior.displayContent + explanation
                assertNarrativeReplyDeltas(
                    listOf(raw), listOf(body), body, previousAssistant = prior, currentUserText = user,
                    experimental = experimental, toolCall = toolCall, requestNarrativeMode = mode,
                )
            }
            assertNarrativeReplyDeltas(
                listOf(projection, explanation), listOf(projection, raw), raw, previousAssistant = prior,
                currentUserText = request, experimental = experimental, toolCall = toolCall, requestNarrativeMode = mode,
            )
        }
    }

    @Test
    fun `a true history envelope on independent commentary does not become part of an exact citation`() = runBlocking {
        val mode = ChatNarrativeMode.FOCUS
        val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "The lamp was lit.").copy(narrativeMode = mode)
        val quote = PromptTemplates.narrativeModeHistoryReply(prior.displayContent, mode)
        val commentary = "The scene shows that they can still see."
        val separatelyWrapped = PromptTemplates.narrativeModeHistoryReply(commentary, mode)
        val request = "Quote the previous answer with its XML history tags verbatim, then explain in plain prose."
        for ((experimental, tool) in listOf(false to false, true to false, true to true)) {
            for (literal in listOf(
                commentary, "~~~xml\n$separatelyWrapped\n~~~", separatelyWrapped.prependIndent("> "),
                PromptTemplates.narrativeModeHistoryReply(commentary, ChatNarrativeMode.ADVANCE), quote, "$quote\n\n",
            )) {
                val answer = "$quote\n\n$literal"
                assertNarrativeReplyDeltas(
                    listOf(answer), listOf(answer), answer, previousAssistant = prior, currentUserText = request,
                    experimental = experimental, toolCall = tool, requestNarrativeMode = mode,
                )
            }
            val quoteOnly = "$quote\n\n$separatelyWrapped"
            assertNarrativeReplyDeltas(
                listOf(quoteOnly), listOf(quoteOnly), quoteOnly, previousAssistant = prior,
                currentUserText = request.substringBefore(',') + ".", experimental = experimental,
                toolCall = tool, requestNarrativeMode = mode,
            )
            val expected = "$quote\n\n$commentary"
            assertNarrativeReplyDeltas(
                listOf(quote, "\n\n$separatelyWrapped"), listOf(quote, expected), expected, previousAssistant = prior,
                currentUserText = request, experimental = experimental, toolCall = tool, requestNarrativeMode = mode, checkHeldStop = true,
            )
        }
    }

    @Test
    fun `a retained legacy citation keeps only its actual quoted projection before issued Advance commentary`() = runBlocking {
        val mode = ChatNarrativeMode.ADVANCE
        val oldBody = "The lamp was lit."
        val legacy = "<聚焦模式=\"沉浸\">\n$oldBody\n</聚焦模式>"
        val previous = ChatMessage.create("fixture", MessageRole.ASSISTANT, legacy).copy(narrativeMode = ChatNarrativeMode.FOCUS)
        val earlier = ChatMessage.create("fixture", MessageRole.ASSISTANT, "A previous patrol passed the lamp.").copy(narrativeMode = mode)
        val modeBlock = PromptTemplates.narrativeModeSystemPrompt(mode)
        val claimedHeading = modeBlock.lineSequence().first()
        val tail = PromptTemplates.narrativeModeTailSystemPrompt(mode, ChatNarrativeMode.FOCUS)
        val commentary = "The scene shows that they can still see."
        val frame = PromptTemplates.narrativeModeHistoryReply(commentary, mode)
        val request = "Quote the previous answer with its XML history tags verbatim, then explain in plain prose."
        val suffix = "\n\n$claimedHeading\n$frame"
        for ((experimental, tool) in listOf(false to false, true to false, true to true)) {
            val modern = previous.copy(content = oldBody)
            val modernQuote = PromptTemplates.narrativeModeHistoryReply(oldBody, ChatNarrativeMode.FOCUS)
            val expectedModern = "$modernQuote\n\n$commentary"
            assertNarrativeReplyDeltas(
                listOf(modernQuote + suffix), listOf(expectedModern), expectedModern, previousAssistant = modern,
                earlierAssistant = earlier, narrativePrompt = modeBlock, narrativeTail = tail,
                currentUserText = request, requestNarrativeMode = mode, dispatchActualModeTail = true,
                experimental = experimental, toolCall = tool,
            )
            for (literal in listOf(commentary, frame.prependIndent("> "), "~~~xml\n$frame\n~~~")) {
                val authored = "$legacy\n\n$literal"
                assertNarrativeReplyDeltas(
                    listOf(authored), listOf(authored), authored, previousAssistant = previous, earlierAssistant = earlier,
                    narrativePrompt = modeBlock, narrativeTail = tail, currentUserText = request, requestNarrativeMode = mode,
                    dispatchActualModeTail = true, experimental = experimental, toolCall = tool,
                )
            }
            val unprojected = "$legacy\n\n$frame"
            assertNarrativeReplyDeltas(
                listOf(unprojected), listOf(unprojected), unprojected, previousAssistant = previous.copy(narrativeMode = null),
                earlierAssistant = earlier, narrativePrompt = modeBlock, currentUserText = request, requestNarrativeMode = mode,
                experimental = experimental, toolCall = tool,
            )
            val expected = "$legacy\n\n$commentary"
            assertNarrativeReplyDeltas(
                listOf(legacy, suffix), listOf(legacy, expected), expected, previousAssistant = previous,
                earlierAssistant = earlier, narrativePrompt = modeBlock, narrativeTail = tail,
                currentUserText = request, requestNarrativeMode = mode, dispatchActualModeTail = true,
                experimental = experimental, toolCall = tool, checkHeldStop = true,
            )
        }
    }

    @Test
    fun `a direct exactly citation preserves the actual dispatched XML projection`() = runBlocking {
        val mode = ChatNarrativeMode.FOCUS
        val body = "门半开着。"
        val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, body).copy(narrativeMode = mode)
        val quote = PromptTemplates.narrativeModeHistoryReply(body, mode)
        val request = "Quote the previous assistant reply with its XML history tags exactly."
        for ((experimental, tool) in listOf(false to false, true to false, true to true)) {
            val negatives = listOf(
                "Quote the previous assistant reply exactly.",
                "Quote the previous assistant reply exactly, without its XML history tags.",
                "Quote the previous assistant reply without its XML history tags exactly.",
                "He read: “$request”",
                "The manual contains:\n$request",
                "> $request",
                "Continue.",
            )
            for (negative in negatives) assertNarrativeReplyDeltas(
                listOf(quote), listOf(body), body, previousAssistant = prior, currentUserText = negative,
                experimental = experimental, toolCall = tool, requestNarrativeMode = mode,
            )
            for (directive in listOf(request.replace("exactly", "verbatim"), request)) {
                assertNarrativeReplyDeltas(
                    listOf(quote), listOf(quote), quote, previousAssistant = prior, currentUserText = directive,
                    experimental = experimental, toolCall = tool, requestNarrativeMode = mode, checkHeldStop = true,
                )
            }
        }
    }

    @Test
    fun `a direct exactly citation cleans only its independently framed plain explanation`() = runBlocking {
        val mode = ChatNarrativeMode.FOCUS
        val body = "门半开着。"
        val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, body).copy(narrativeMode = mode)
        val quote = PromptTemplates.narrativeModeHistoryReply(body, mode)
        val commentary = "The mode appears in an XML attribute."
        val separateFrame = PromptTemplates.narrativeModeHistoryReply(commentary, mode)
        val request = "Quote the previous assistant reply with its XML history tags exactly, then explain in plain prose."
        val expected = "$quote\n\n$commentary"
        for ((experimental, tool) in listOf(false to false, true to false, true to true)) {
            for (negative in listOf("Continue.", "He read: “$request”", request.replace("with its", "without its"))) {
                val plain = "$body\n\n$commentary"
                assertNarrativeReplyDeltas(
                    listOf(quote, "\n\n$commentary"), listOf(body, plain), plain, previousAssistant = prior, currentUserText = negative,
                    experimental = experimental, toolCall = tool, requestNarrativeMode = mode, checkHeldStop = true,
                )
            }
            for (directive in listOf(request.replace("exactly", "verbatim"), request)) {
                for (explanation in listOf(commentary, separateFrame)) {
                    assertNarrativeReplyDeltas(
                        listOf(quote, "\n\n$explanation"), listOf(quote, expected), expected, previousAssistant = prior,
                        currentUserText = directive, experimental = experimental, toolCall = tool, requestNarrativeMode = mode, checkHeldStop = true,
                    )
                }
            }
        }
    }

    @Test
    fun `English XML citations tolerate outer blank physical lines without treating indented code as a request`() = runBlocking {
        val mode = ChatNarrativeMode.FOCUS
        val body = "The latch held.  \r\n    Then it opened.  "
        val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, body).copy(narrativeMode = mode)
        val quote = PromptTemplates.narrativeModeHistoryReply(body, mode)
        val request = "Quote the previous assistant reply with its XML history tags exactly."
        val clause = "Quote the last reply verbatim, including both XML history tags."
        val followup = "Then explain what the quotation demonstrates."
        val commentary = "\n\nThe opening tag describes the mode."
        for ((experimental, tool) in listOf(false to false, true to false, true to true)) {
            for (literal in listOf(
                "\n    $request\n", "\r\n\t$request\r\n", "\n> $request\n",
                "\nHe read: “$request”\n", "\n$request\nThen omit the XML history tags.\n",
                "\r\n$clause\r\n\r\n$followup\r\n", "\nQuote the previous assistant reply without its XML history tags exactly.\n",
                "\nContinue.\n",
            )) assertNarrativeReplyDeltas(
                listOf(quote), listOf(body), body, previousAssistant = prior, currentUserText = literal,
                experimental = experimental, toolCall = tool, requestNarrativeMode = mode, checkHeldStop = true,
            )
            for (padding in listOf(request, "\n$request\n", " \t\r\n\r\n$request\r\n  \r\n\t", "\n  $request \t\n")) {
                assertNarrativeReplyDeltas(
                    listOf(quote), listOf(quote), quote, previousAssistant = prior, currentUserText = padding,
                    experimental = experimental, toolCall = tool, requestNarrativeMode = mode, checkHeldStop = true,
                )
            }
            val paddedClause = "\r\n\t \r\n$clause\r\n$followup \t\r\n"
            assertNarrativeReplyDeltas(
                listOf(quote, commentary), listOf(quote, quote + commentary), quote + commentary,
                previousAssistant = prior, currentUserText = paddedClause,
                experimental = experimental, toolCall = tool, requestNarrativeMode = mode, checkHeldStop = true,
            )
        }
    }

    @Test
    fun `Chinese XML citations tolerate outer LF and CRLF without changing the quoted bytes`() = runBlocking {
        val mode = ChatNarrativeMode.FOCUS
        val body = "屋内还亮着灯。  \r\n    她停在门边。"
        val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, body).copy(narrativeMode = mode)
        val quote = PromptTemplates.narrativeModeHistoryReply(body, mode)
        val request = "请原样引用上一条助手回复，连同外层的 XML 历史标签。"
        val omit = "请原样引用上一条助手回复，但不要包含外层的 XML 历史标签。"
        for ((experimental, tool) in listOf(false to false, true to false, true to true)) {
            for (literal in listOf("\r\n    $request\r\n", "\n\t$request\n", "\n> $request\n", "\n$omit\n")) {
                assertNarrativeReplyDeltas(
                    listOf(quote), listOf(body), body, previousAssistant = prior, currentUserText = literal,
                    experimental = experimental, toolCall = tool, requestNarrativeMode = mode, checkHeldStop = true,
                )
            }
            for (padding in listOf(request, "\n$request\n", " \t\r\n$request\r\n   \r\n")) {
                assertNarrativeReplyDeltas(
                    listOf(quote), listOf(quote), quote, previousAssistant = prior, currentUserText = padding,
                    experimental = experimental, toolCall = tool, requestNarrativeMode = mode, checkHeldStop = true,
                )
            }
        }
    }

    @Test
    fun `classifier padding leaves an actual two-call XML citation and request byte exact`(): Unit = runBlocking {
        val mode = ChatNarrativeMode.FOCUS
        val body = "The letter was still sealed.  \r\n    The lock clicked.  "
        val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, body).copy(narrativeMode = mode)
        val quote = PromptTemplates.narrativeModeHistoryReply(body, mode)
        val parts = listOf(quote.substringBeforeLast('\n'), quote.substringAfterLast('\n'))
        val request = "Quote the last reply verbatim, including both XML history tags."
        assertMultipartNarrativeReply(parts, quote, previousAssistant = prior, userText = request, checkHeldStop = true)
        for (literal in listOf("\n    $request\n", "\r\n\t$request\r\n", "\n> $request\n", "\nContinue.\n")) {
            assertMultipartNarrativeReply(parts, body, previousAssistant = prior, userText = literal, checkHeldStop = true)
        }
        assertMultipartNarrativeReply(
            parts, quote, previousAssistant = prior, userText = "\r\n \t\r\n$request\r\n\t \r\n", checkHeldStop = true,
        )
    }

    @Test
    fun `sent format can declare paired exact boundary lines in inline code`() = runBlocking {
        val body = "The door closed."
        for (mode in ChatNarrativeMode.entries) {
            val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "Previous story.").copy(narrativeMode = mode)
            val raw = PromptTemplates.narrativeModeHistoryReply(body, mode)
            val opener = raw.substringBefore('\n')
            val closer = raw.substringAfterLast('\n')
            val instruction = "Always begin the reply with the exact line `$opener` and end it with the exact line `$closer`."
            for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
                assertNarrativeReplyDeltas(
                    listOf(raw), listOf(raw), raw, formatCard = instruction, previousAssistant = prior,
                    experimental = experimental, toolCall = toolCall,
                )
                for (sample in listOf(
                    "> $instruction", "```text\n$instruction\n```", instruction.replace(closer, "</聚焦模式>"),
                    "The log described the commands `$opener` and `$closer`.",
                )) {
                    assertNarrativeReplyDeltas(
                        listOf(raw), listOf(body), body, formatCard = sample, previousAssistant = prior,
                        experimental = experimental, toolCall = toolCall,
                    )
                }
            }
        }
    }

    @Test
    fun `exact historical projection followed by a separate new story removes only its frame`() = runBlocking {
        val priorBody = "The light went out.  \r\n    The window was shut.  "
        val suffix = "\n\nA kettle rang.  \r\n    A new note."
        for (mode in ChatNarrativeMode.entries) {
            val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, priorBody).copy(narrativeMode = mode)
            val known = PromptTemplates.narrativeModeHistoryReply(priorBody, mode)
            val raw = known + suffix
            val expected = priorBody + suffix
            for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
                assertNarrativeReplyDeltas(
                    listOf(known, suffix), listOf(priorBody, expected), expected, previousAssistant = prior,
                    experimental = experimental, toolCall = toolCall,
                )
                for ((variant, userText) in listOf(
                    raw to "Copy this text verbatim:\n$raw",
                    "$known\n\nThese history tags are only an example." to null,
                    "Here is an illustrative example:\n```xml\n$known\n```$suffix" to null,
                )) {
                    assertNarrativeReplyDeltas(
                        listOf(variant), listOf(variant), variant, previousAssistant = prior,
                        experimental = experimental, toolCall = toolCall, currentUserText = userText,
                    )
                }
                val novel = PromptTemplates.narrativeModeHistoryReply("Novel title and story.", mode) + suffix
                assertNarrativeReplyDeltas(
                    listOf(novel), listOf(novel), novel, previousAssistant = prior,
                    experimental = experimental, toolCall = toolCall,
                )
            }
            assertMultipartNarrativeReply(listOf(known, suffix.removePrefix("\n")), expected, previousAssistant = prior)
        }
    }

    @Test
    fun `complete dispatched narrative blocks are removed with their exact continuation lines`() = runBlocking {
        val story = "她点亮门廊的灯。"
        for (mode in ChatNarrativeMode.entries) {
            val other = ChatNarrativeMode.entries.first { it != mode }
            val core = PromptAssembler().assembleCachePromptLayers(CharacterCard.create("fixture"), narrativeMode = mode)
                .narrativeModeSystemPrompt
            val tail = PromptTemplates.narrativeModeTailSystemPrompt(mode, other)
            val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "Earlier scene.").copy(narrativeMode = mode)
            for ((experimental, toolCall) in listOf(false to false, true to true)) {
                for (block in listOf(core, tail)) {
                    val raw = "$block\n\n$story"
                    for (quoted in listOf("~~~text\n$block\n~~~\n\n$story", block.prependIndent("> ") + "\n\n$story")) {
                        assertNarrativeReplyDeltas(
                            listOf(quoted), listOf(quoted), quoted, previousAssistant = prior, narrativePrompt = core,
                            narrativeTail = tail, currentUserText = "Continue.", experimental = experimental, toolCall = toolCall,
                        )
                    }
                    assertNarrativeReplyDeltas(
                        listOf(raw), listOf(raw), raw, previousAssistant = prior, narrativePrompt = core, narrativeTail = tail,
                        currentUserText = "Copy this text verbatim:\n$raw", experimental = experimental, toolCall = toolCall,
                    )
                    assertNarrativeReplyDeltas(
                        listOf(block, "\n\n$story"), listOf("", story), story, previousAssistant = prior, narrativePrompt = core,
                        narrativeTail = tail, currentUserText = "Continue.", experimental = experimental, toolCall = toolCall,
                    )
                    val wrapped = PromptTemplates.narrativeModeHistoryReply(raw, mode)
                    assertNarrativeReplyDeltas(
                        listOf(wrapped), listOf(story), story, previousAssistant = prior, narrativePrompt = core,
                        narrativeTail = tail, currentUserText = "Continue.", experimental = experimental, toolCall = toolCall,
                    )
                }
            }
        }
    }

    @Test
    fun `only an anchored identifiable partial mode instruction is held until its bytes diverge`() = runBlocking {
        for (mode in ChatNarrativeMode.entries) {
            val other = ChatNarrativeMode.entries.first { it != mode }
            val tail = PromptTemplates.narrativeModeTailSystemPrompt(mode, other)
            val (title, next, third) = tail.lines()
            val partial = next.take(20)
            val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "Earlier scene.").copy(narrativeMode = mode)
            for ((experimental, toolCall) in listOf(false to false, true to true)) {
                assertNarrativeReplyDeltas(
                    listOf(partial), listOf(partial), partial, narrativeTail = tail, currentUserText = "Continue.",
                    previousAssistant = prior, experimental = experimental, toolCall = toolCall,
                )
                val aborted = "$partial is a fictional notice."
                assertNarrativeReplyDeltas(
                    listOf("$title\n", partial, aborted.removePrefix(partial)), listOf("", "", aborted), aborted,
                    previousAssistant = prior, narrativeTail = tail, currentUserText = "Continue.",
                    experimental = experimental, toolCall = toolCall,
                )
                val story = "The porch light went on."
                val afterSwitch = "$title\n$next\n" + third.take(20)
                assertNarrativeReplyDeltas(
                    listOf(afterSwitch, third.drop(20) + "\n$story"), listOf("", story), story,
                    previousAssistant = prior, narrativeTail = tail, currentUserText = "Continue.",
                    experimental = experimental, toolCall = toolCall,
                )
                val unsentSwitch = "$partial\n$story"
                assertNarrativeReplyDeltas(
                    listOf("$title\n$unsentSwitch"), listOf(unsentSwitch), unsentSwitch, previousAssistant = prior,
                    narrativeTail = PromptTemplates.narrativeModeTailSystemPrompt(mode, null), currentUserText = "Continue.",
                    experimental = experimental, toolCall = toolCall,
                )
            }
        }
    }

    @Test
    fun `sent trailing heading is removed without trimming exact history and new prose`() = runBlocking {
        val priorBody = "The lamp went out.  \r\n    Original indentation.  "
        val newStory = "\n\nThe new visitor danced.  \r\n    New indentation.  "
        for (mode in ChatNarrativeMode.entries) {
            val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, priorBody).copy(narrativeMode = mode)
            val tail = PromptTemplates.narrativeModeTailSystemPrompt(mode, null)
            val heading = tail.lineSequence().first()
            val projection = PromptTemplates.narrativeModeHistoryReply(priorBody, mode)
            for ((experimental, toolCall) in listOf(false to false, true to true)) {
                val raw = projection + newStory + "\n" + heading
                val wanted = priorBody + newStory
                assertNarrativeReplyDeltas(
                    listOf(raw), listOf(wanted + "\n" + heading), wanted + "\n" + heading, previousAssistant = prior,
                    currentUserText = "Print this title verbatim: $heading.", narrativeTail = tail,
                    experimental = experimental, toolCall = toolCall,
                )
                assertNarrativeReplyDeltas(
                    listOf(raw), listOf(wanted), wanted, previousAssistant = prior, narrativeTail = tail,
                    currentUserText = "Continue.", experimental = experimental, toolCall = toolCall,
                )
            }
        }
    }

    @Test
    fun `explicitly citing the previous answer with its history tags keeps the exact projected citation`() = runBlocking {
        val previous = "She left the key outside.  "
        val commentary = "\n\nThis XML element stores mode information for the previous turn."
        for (mode in ChatNarrativeMode.entries) {
            val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, previous).copy(narrativeMode = mode)
            val projection = PromptTemplates.narrativeModeHistoryReply(previous, mode)
            val raw = projection + commentary
            for ((experimental, toolCall) in listOf(false to false, true to true)) {
                assertNarrativeReplyDeltas(
                    listOf(raw), listOf(previous + commentary), previous + commentary, previousAssistant = prior,
                    currentUserText = "Quote the previous answer verbatim, without its XML history tags.",
                    experimental = experimental, toolCall = toolCall,
                )
                val requested = "Quote the previous answer with its XML history tags verbatim, then explain what purpose that wrapper serves."
                assertNarrativeReplyDeltas(
                    listOf(projection, commentary), listOf(projection, raw), raw, previousAssistant = prior,
                    currentUserText = requested, experimental = experimental, toolCall = toolCall,
                )
                val novel = PromptTemplates.narrativeModeHistoryReply("Unrelated replacement narration.", mode)
                assertNarrativeReplyDeltas(
                    listOf(novel), listOf("Unrelated replacement narration."), "Unrelated replacement narration.",
                    previousAssistant = prior, currentUserText = requested, experimental = experimental, toolCall = toolCall,
                )
            }
        }
    }

    @Test
    fun `a novel wrapped story can end before an exact dispatched mode suffix`() = runBlocking {
        val story = "The courier left through a narrow gate."
        for (mode in ChatNarrativeMode.entries) {
            val other = ChatNarrativeMode.entries.first { it != mode }
            val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "Yesterday it rained.").copy(narrativeMode = mode)
            val novel = PromptTemplates.narrativeModeHistoryReply(story, mode)
            for (previousMode in listOf(mode, other)) {
                val sentTail = PromptTemplates.narrativeModeTailSystemPrompt(mode, previousMode)
                val block = sentTail.lineSequence().take(2).joinToString("\n")
                val raw = "$novel\n$block"
                for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
                    for (literal in listOf(
                        "$novel\nShe read the title aloud:\n$block",
                        "$novel\n" + block.prependIndent("> "),
                        "$novel\n~~~text\n$block\n~~~",
                    )) {
                        assertNarrativeReplyDeltas(
                            listOf(literal), listOf(literal), literal, previousAssistant = prior, narrativeTail = sentTail,
                            currentUserText = "Continue.", experimental = experimental, toolCall = toolCall,
                        )
                    }
                    var persisted: String? = null
                    assertNarrativeReplyDeltas(
                        listOf(novel, "\n$block"), listOf(story, story), story, previousAssistant = prior, narrativeTail = sentTail,
                        currentUserText = "Continue.", experimental = experimental, toolCall = toolCall, onCompleted = { persisted = it },
                    )
                    val status = "${MarkdownCodeFences.legacyStatusOpening}\n时间：晚上\n${MarkdownCodeFences.legacyStatusLabel}```"
                    val withStatus = "$raw\n$status"
                    val storyWithStatus = "$story\n$status"
                    assertNarrativeReplyDeltas(
                        listOf(withStatus), listOf(storyWithStatus), storyWithStatus, previousAssistant = prior,
                        currentUserText = "Continue.", narrativeTail = sentTail, experimental = experimental, toolCall = toolCall,
                    )
                    if (toolCall) {
                        assertMultipartNarrativeReply(listOf(novel, block), story, narrativePrompt = sentTail, previousAssistant = prior)
                        assertMultipartNarrativeReply(
                            listOf(novel, block, status), storyWithStatus, narrativePrompt = sentTail, previousAssistant = prior,
                        )
                    }
                    val saved = ChatMessage.create("fixture", MessageRole.ASSISTANT, checkNotNull(persisted)).copy(narrativeMode = mode)
                    val reloaded = Json.decodeFromString(ChatMessage.serializer(), Json.encodeToString(ChatMessage.serializer(), saved))
                    assertEquals(story, reloaded.displayContent)
                    val fresh = "She returned in the morning."
                    assertNarrativeReplyDeltas(
                        listOf(fresh), listOf(fresh), fresh, previousAssistant = reloaded,
                        currentUserText = "Go on.", experimental = experimental, toolCall = toolCall,
                    )
                    val unrelated = PromptTemplates.narrativeModeTailSystemPrompt(other, mode).lineSequence().take(2).joinToString("\n")
                    val unrelatedWire = "$novel\n$unrelated"
                    assertNarrativeReplyDeltas(
                        listOf(unrelatedWire), listOf(unrelatedWire), unrelatedWire, previousAssistant = prior,
                        currentUserText = "Continue.", narrativeTail = sentTail, experimental = experimental, toolCall = toolCall,
                    )
                    assertNarrativeReplyDeltas(
                        listOf(raw), listOf(raw), raw, previousAssistant = prior, narrativeTail = sentTail,
                        currentUserText = "Copy this text verbatim:\n$raw", experimental = experimental, toolCall = toolCall,
                    )
                }
            }
        }
    }

    @Test
    fun `a direct Chinese request keeps only the exact latest projected XML citation`() = runBlocking {
        val priorStory = "她从书架上取下旧信。  \r\n    雨声慢了下来。"
        val explanation = "\n\n外层元素标记上一轮采用的模式。"
        val wanted = "请原样引用上一条助手回复，连同外层的 XML 历史标签。"
        for (mode in ChatNarrativeMode.entries) {
            val previous = ChatMessage.create("fixture", MessageRole.ASSISTANT, priorStory).copy(narrativeMode = mode)
            val projection = PromptTemplates.narrativeModeHistoryReply(priorStory, mode)
            val raw = projection + explanation
            for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
                for (neutral in listOf(
                    "请原样引用上一条助手回复，但不要包含外层的 XML 历史标签。",
                    "她在纸条上抄写了一句请求：“$wanted”",
                    "讨论上一条助手回复与 XML 历史标签的关系。",
                )) {
                    assertNarrativeReplyDeltas(
                        listOf(raw), listOf(priorStory + explanation), priorStory + explanation, previousAssistant = previous,
                        currentUserText = neutral, experimental = experimental, toolCall = toolCall,
                    )
                }
                assertNarrativeReplyDeltas(
                    listOf(projection, explanation), listOf(projection, raw), raw, previousAssistant = previous,
                    currentUserText = wanted, experimental = experimental, toolCall = toolCall,
                )
                val novel = PromptTemplates.narrativeModeHistoryReply("一只燕子在屋檐下停住。", mode)
                assertNarrativeReplyDeltas(
                    listOf(novel), listOf("一只燕子在屋檐下停住。"), "一只燕子在屋檐下停住。", previousAssistant = previous,
                    currentUserText = wanted, experimental = experimental, toolCall = toolCall,
                )
                val earlier = "那天，她刚抵达车站。"
                assertNarrativeReplyDeltas(
                    listOf(PromptTemplates.narrativeModeHistoryReply(earlier, mode)), listOf(earlier), earlier,
                    previousAssistant = previous,
                    earlierAssistant = ChatMessage.create("fixture", MessageRole.ASSISTANT, earlier).copy(narrativeMode = mode),
                    currentUserText = wanted, experimental = experimental, toolCall = toolCall,
                )
                assertNarrativeReplyDeltas(
                    listOf(raw), listOf(priorStory + explanation), priorStory + explanation, previousAssistant = previous,
                    previousUserText = wanted, currentUserText = "继续故事。", experimental = experimental, toolCall = toolCall,
                )
            }
        }
    }

    @Test
    fun `disabled narrative keeps routine headings scoped to the actual sent history`() = runBlocking {
        val heading = PromptTemplates.sectionHeading(PromptTemplates.SECTION_PREVIOUS_TURN)
        val story = "She closed the shutters."
        val previous = ChatMessage.create("fixture", MessageRole.ASSISTANT, "The wind rose.").copy(narrativeMode = ChatNarrativeMode.FOCUS)
        val raw = "$heading\n$story"
        for (experimental in listOf(false, true)) {
            assertNarrativeReplyDeltas(
                listOf(raw), listOf(raw), raw, currentUserText = "Begin the story.", experimental = experimental,
                fixtureHistory = false, narrativeModesEnabled = false, issueRoutineHistoryHeading = true,
            )
            for ((candidate, instruction) in listOf(
                raw to "Print this title verbatim: $heading.",
                "> $heading\n$story" to "Continue.",
                PromptTemplates.narrativeModeHistoryReply(story, ChatNarrativeMode.FOCUS) to "Continue.",
            )) {
                assertNarrativeReplyDeltas(
                    listOf(candidate), listOf(candidate), candidate, previousAssistant = previous, currentUserText = instruction,
                    experimental = experimental, narrativeModesEnabled = false, issueRoutineHistoryHeading = true,
                )
            }
            var persisted: String? = null
            assertNarrativeReplyDeltas(
                listOf(heading, "\n$story"), listOf("", story), story, previousAssistant = previous,
                currentUserText = "Continue the scene.", experimental = experimental, narrativeModesEnabled = false,
                issueRoutineHistoryHeading = true, onCompleted = { persisted = it },
            )
            val saved = ChatMessage.create("fixture", MessageRole.ASSISTANT, checkNotNull(persisted)).copy(narrativeMode = null)
            val reloaded = Json.decodeFromString(ChatMessage.serializer(), Json.encodeToString(ChatMessage.serializer(), saved))
            assertEquals(story, reloaded.displayContent)
            assertNull(reloaded.narrativeMode)
            val next = "Then she lit a candle."
            assertNarrativeReplyDeltas(
                listOf(next), listOf(next), next, previousAssistant = reloaded, currentUserText = "Go on.",
                experimental = experimental, narrativeModesEnabled = false, issueRoutineHistoryHeading = true,
            )
        }
    }

    @Test
    fun `including both XML history tags after verbatim reserves only the latest citation`() = runBlocking {
        val saved = "The latch held.  \r\n    She tried the window."
        val explanation = "\n\nThis XML records an earlier mode."
        val request = "Quote the last reply verbatim, including both XML history tags."
        for (mode in ChatNarrativeMode.entries) {
            val previous = ChatMessage.create("fixture", MessageRole.ASSISTANT, saved).copy(narrativeMode = mode)
            val projection = PromptTemplates.narrativeModeHistoryReply(saved, mode)
            val raw = projection + explanation
            for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
                for (copy in listOf(
                    "Quote the previous answer with its XML history tags verbatim, then explain what purpose that wrapper serves.",
                    "请原样引用上一条助手回复，连同外层的 XML 历史标签。",
                )) {
                    assertNarrativeReplyDeltas(
                        listOf(raw), listOf(raw), raw, previousAssistant = previous,
                        currentUserText = copy, experimental = experimental, toolCall = toolCall,
                    )
                }
                for (other in listOf(
                    "Quote the last reply verbatim, without either XML history tag.",
                    "The letter says: “$request”",
                    "Describe both XML history tags.",
                )) {
                    assertNarrativeReplyDeltas(
                        listOf(raw), listOf(saved + explanation), saved + explanation, previousAssistant = previous,
                        currentUserText = other, experimental = experimental, toolCall = toolCall,
                    )
                }
                assertNarrativeReplyDeltas(
                    listOf(projection, explanation), listOf(projection, raw), raw, previousAssistant = previous,
                    currentUserText = request, experimental = experimental, toolCall = toolCall,
                )
                val different = "An owl called from the roof."
                val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, different).copy(narrativeMode = mode)
                val otherProjection = PromptTemplates.narrativeModeHistoryReply(different, mode)
                for (earlier in listOf(null, prior)) {
                    assertNarrativeReplyDeltas(
                        listOf(otherProjection), listOf(different), different, previousAssistant = previous,
                        earlierAssistant = earlier, currentUserText = request, experimental = experimental, toolCall = toolCall,
                    )
                }
            }
        }
    }

    @Test
    fun `disabled narrative cleans only a dispatched standalone middle tool heading`(): Unit = runBlocking {
        val title = PromptTemplates.sectionHeading(PromptTemplates.SECTION_PREVIOUS_TURN)
        val first = "The first door opened.  "
        val second = "    The second door was locked."
        val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "Yesterday the porch was empty.")
        val parts = listOf(first, title, second)
        assertMultipartNarrativeReply(
            parts, parts.joinToString("\n"), narrativeModesEnabled = false, issueRoutineHistoryHeading = true,
        )
        val caption = listOf("The sign showed this title:", title, second)
        assertMultipartNarrativeReply(
            caption, caption.joinToString("\n"), previousAssistant = prior,
            narrativeModesEnabled = false, issueRoutineHistoryHeading = true,
        )
        val quoted = listOf(first, "> $title", second)
        assertMultipartNarrativeReply(
            quoted, quoted.joinToString("\n"), previousAssistant = prior,
            narrativeModesEnabled = false, issueRoutineHistoryHeading = true,
        )
        assertMultipartNarrativeReply(
            parts, parts.joinToString("\n"), previousAssistant = prior, userText = "Print this title verbatim: $title.",
            narrativeModesEnabled = false, issueRoutineHistoryHeading = true,
        )
        assertMultipartNarrativeReply(
            parts, "$first\n$second", previousAssistant = prior,
            narrativeModesEnabled = false, issueRoutineHistoryHeading = true,
        )
    }

    @Test
    fun `only a dispatched explicit WorldBook title declaration reserves a routine title`() = runBlocking {
        val heading = PromptTemplates.sectionHeading(PromptTemplates.SECTION_PREVIOUS_TURN)
        val declaration = "Please start each reply with this literal heading verbatim:\n$heading"
        val prose = "She crossed the canal."
        val raw = "$heading\n$prose"
        val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "They reached the pier.")
        for (experimental in listOf(false, true)) {
            for (entry in listOf(
                "The archive contains a reference named $heading.",
                "The archive quotes its rules here:\n" + declaration.prependIndent("> "),
            )) {
                assertNarrativeReplyDeltas(
                    listOf(raw), listOf(prose), prose, previousAssistant = prior, currentUserText = "Check the sigil.",
                    experimental = experimental, narrativeModesEnabled = false, issueRoutineHistoryHeading = true,
                    worldBookEntry = entry,
                )
            }
            for ((core, player) in listOf(declaration to null, null to declaration)) {
                assertNarrativeReplyDeltas(
                    listOf(raw), listOf(raw), raw, previousAssistant = prior, currentUserText = "Check the sigil.",
                    experimental = experimental, narrativeModesEnabled = false, issueRoutineHistoryHeading = true,
                    characterCoreInstructions = core, playerOverride = player,
                )
            }
            assertNarrativeReplyDeltas(
                listOf(raw), listOf(prose), prose, previousAssistant = prior, currentUserText = "Open the door.",
                experimental = experimental, narrativeModesEnabled = false, issueRoutineHistoryHeading = true,
                worldBookEntry = declaration,
            )
            assertNarrativeReplyDeltas(
                listOf(raw), listOf(prose), prose, previousAssistant = prior, currentUserText = "Check the sigil.",
                experimental = experimental, narrativeModesEnabled = false, issueRoutineHistoryHeading = true,
                ragReferenceText = declaration,
            )
            assertNarrativeReplyDeltas(
                listOf(raw), listOf(raw), raw, previousAssistant = prior, currentUserText = "Check the sigil.",
                experimental = experimental, narrativeModesEnabled = false, issueRoutineHistoryHeading = true,
                worldBookEntry = declaration,
            )
        }
    }

    @Test
    fun `only complete dispatched mode blocks leave interior revised narration`() = runBlocking {
        val first = "The boat passed the old quay.  "
        val second = "The passengers watched the quay recede."
        for (mode in ChatNarrativeMode.entries) {
            val other = ChatNarrativeMode.entries.first { it != mode }
            val tail = PromptTemplates.narrativeModeTailSystemPrompt(mode, other)
            val core = PromptTemplates.narrativeModeSystemPrompt(mode)
            for ((experimental, toolCall) in listOf(false to false, true to true)) {
                for (block in listOf(core, tail)) {
                    for (example in listOf(
                        "$first\n~~~text\n$block\n~~~\n$second",
                        "$first\n" + block.prependIndent("> ") + "\n$second",
                        "$first\nThe plaque quoted these rules:\n$block\n$second",
                        "$first\n" + block.lineSequence().take(2).joinToString("\n") + "\n$second",
                    )) {
                        assertNarrativeReplyDeltas(
                            listOf(example), listOf(example), example, narrativePrompt = core, narrativeTail = tail,
                            currentUserText = "Continue.", experimental = experimental, toolCall = toolCall,
                        )
                    }
                    val raw = "$first\n$block\n$second"
                    assertNarrativeReplyDeltas(
                        listOf(raw), listOf(raw), raw, narrativePrompt = core, narrativeTail = tail,
                        currentUserText = "Copy this text verbatim:\n$raw", experimental = experimental, toolCall = toolCall,
                    )
                    val wanted = "$first\n$second"
                    assertNarrativeReplyDeltas(
                        listOf(raw), listOf(wanted), wanted, narrativePrompt = core, narrativeTail = tail,
                        currentUserText = "Continue.", experimental = experimental, toolCall = toolCall,
                    )
                }
            }
        }
    }

    @Test
    fun `a single reply exact boundary instruction expires in past user history`() = runBlocking {
        val mode = ChatNarrativeMode.FOCUS
        val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "An earlier scene.").copy(narrativeMode = mode)
        val story = "The new lamp still worked."
        val wrapped = PromptTemplates.narrativeModeHistoryReply(story, mode)
        val opener = wrapped.substringBefore('\n')
        val close = wrapped.substringAfterLast('\n')
        val boundary = "Begin the reply with the exact line `$opener` and end it with the exact line `$close`."
        val scoped = "For this reply only.\n$boundary"
        for ((experimental, tool) in listOf(false to false, true to false, true to true)) {
            for ((current, past) in listOf(scoped to "Light the lamp.", "Check the sigil." to scoped)) {
                assertNarrativeReplyDeltas(
                    listOf(wrapped), listOf(wrapped), wrapped, previousAssistant = prior, previousUserText = past,
                    currentUserText = current, worldBookEntry = boundary.takeIf { current == "Check the sigil." },
                    experimental = experimental, toolCall = tool, requestNarrativeMode = mode,
                )
            }
            assertNarrativeReplyDeltas(
                listOf(wrapped), listOf(wrapped), wrapped, previousAssistant = prior, previousUserText = scoped,
                currentUserText = "Continue.", characterBasicSetting = boundary,
                experimental = experimental, toolCall = tool, requestNarrativeMode = mode,
            )
            assertNarrativeReplyDeltas(
                listOf(wrapped), listOf(story), story, previousAssistant = prior, previousUserText = scoped,
                currentUserText = "Continue.", experimental = experimental, toolCall = tool,
                requestNarrativeMode = mode, checkHeldStop = true,
            )
        }
    }

    @Test
    fun `actual Character can declare paired exact boundary lines in inline code`() = runBlocking {
        assertDispatchedInlineBoundaries(fromWorldBook = false)
    }

    @Test
    fun `active WorldBook can declare paired exact boundary lines in inline code`() = runBlocking {
        assertDispatchedInlineBoundaries(fromWorldBook = true)
    }

    private suspend fun assertDispatchedInlineBoundaries(fromWorldBook: Boolean) {
        val mode = ChatNarrativeMode.FOCUS
        val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "Earlier scene.").copy(narrativeMode = mode)
        val story = "The lamp was still lit."
        val raw = PromptTemplates.narrativeModeHistoryReply(story, mode)
        val opener = raw.substringBefore('\n')
        val closer = raw.substringAfterLast('\n')
        val directive = "Always begin the reply with the exact line `$opener` and end it with the exact line `$closer`."
        val mention = "A lecture refers to `$opener` and `$closer`."
        for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
            for ((character, world, user) in listOf(
                Triple("> $directive", null, "Continue."), Triple(null, mention, "Check the sigil."),
                Triple(null, directive, "Stay at the gate."),
            )) assertNarrativeReplyDeltas(
                listOf(raw), listOf(story), story, previousAssistant = prior, currentUserText = user,
                characterBasicSetting = character, worldBookEntry = world,
                experimental = experimental, toolCall = toolCall, requestNarrativeMode = mode,
            )
            assertNarrativeReplyDeltas(
                listOf(raw), listOf(raw), raw, previousAssistant = prior, currentUserText = "Check the sigil.",
                characterBasicSetting = directive.takeUnless { fromWorldBook },
                worldBookEntry = directive.takeIf { fromWorldBook }, checkHeldStop = true,
                experimental = experimental, toolCall = toolCall, requestNarrativeMode = mode,
            )
        }
    }

    @Test
    fun `actual sent Character tail inline heading instruction preserves the separate routine title`() = runBlocking {
        val heading = PromptTemplates.sectionHeading(PromptTemplates.SECTION_PREVIOUS_TURN)
        val previous = ChatMessage.create("fixture", MessageRole.ASSISTANT, "She lit the lamp.").copy(narrativeMode = ChatNarrativeMode.FOCUS)
        val story = "The lamp was still lit."
        val raw = "$heading\n$story"
        val directive = "Print this heading verbatim: $heading"
        val multiline = "Begin each reply with this exact heading verbatim:\n$heading"
        for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
            for (tail in listOf(null, "The manual references $heading.", "```text\n$directive\n```")) {
                assertNarrativeReplyDeltas(
                    listOf(raw), listOf(story), story, previousAssistant = previous, previousUserText = "Light the lamp.",
                    currentUserText = "Continue.", issueRoutineHistoryHeading = true, characterPostHistoryInstructions = tail,
                    experimental = experimental, toolCall = toolCall, requestNarrativeMode = ChatNarrativeMode.FOCUS,
                )
            }
            for ((user, tail) in listOf(directive to null, "Continue." to multiline, "Continue." to directive)) {
                assertNarrativeReplyDeltas(
                    listOf(raw), listOf(raw), raw, previousAssistant = previous, previousUserText = "Light the lamp.",
                    currentUserText = user, issueRoutineHistoryHeading = true, characterPostHistoryInstructions = tail,
                    experimental = experimental, toolCall = toolCall, requestNarrativeMode = ChatNarrativeMode.FOCUS,
                    checkHeldStop = tail == directive,
                )
            }
        }
    }

    @Test
    fun `complete mode echo is independent from a separately authored matching title`() = runBlocking {
        val story = "She turned the page."
        for (mode in ChatNarrativeMode.entries) {
            val other = ChatNarrativeMode.entries.first { it != mode }
            val tail = PromptTemplates.narrativeModeTailSystemPrompt(mode, other)
            val heading = tail.lineSequence().first()
            val wanted = "$heading\n$story"
            val raw = "$tail\n\n$wanted"
            for ((experimental, toolCall) in listOf(false to false, true to true)) {
                assertNarrativeReplyDeltas(
                    listOf(raw), listOf(raw), raw, narrativeTail = tail,
                    currentUserText = "Copy this text verbatim:\n$raw", experimental = experimental, toolCall = toolCall,
                )
                assertNarrativeReplyDeltas(
                    listOf(raw), listOf(wanted), wanted, narrativeTail = tail,
                    currentUserText = "Print this title verbatim: $heading.", experimental = experimental, toolCall = toolCall,
                )
            }
        }
    }

    @Test
    fun `a complete mode block split across buffered calls disappears without shifting the prose parts`() = runBlocking {
        val first = "The old sail caught the wind.  \r\n    A dark bow."
        val next = "    The boat stayed close to shore."
        val expected = "$first\n$next"
        for (mode in ChatNarrativeMode.entries) {
            val other = ChatNarrativeMode.entries.first { it != mode }
            val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "Earlier it was snowing.").copy(narrativeMode = mode)
            val issued = listOf(
                PromptTemplates.narrativeModeSystemPrompt(mode),
                PromptTemplates.narrativeModeTailSystemPrompt(mode, other),
            )
            for (modeBlock in issued) {
                val lines = modeBlock.lines()
                val head = lines.take(2).joinToString("\n")
                val tail = lines.drop(2).joinToString("\n")
                val parts = listOf("$first\n$head", "$tail\n$next")
                val done = assertMultipartNarrativeReply(
                    parts, expected, narrativePrompt = modeBlock, previousAssistant = prior, checkHeldStop = true,
                )
                val third = "A last independent scene followed."
                val secondProse = "The crew lowered the sail."
                val separatelyFramed = PromptTemplates.narrativeModeHistoryReply(third, mode)
                assertMultipartNarrativeReply(
                    listOf(parts.first(), "$tail\n$secondProse", separatelyFramed), "$first\n$secondProse\n$third",
                    narrativePrompt = modeBlock, previousAssistant = prior,
                )
                for (literal in listOf(
                    listOf("$first\n~~~text\n$head", "$tail\n~~~\n$next"),
                    listOf("$first\n" + head.prependIndent("> "), tail.prependIndent("> ") + "\n\n$next"),
                    listOf("$first\nThe plaque quoted these rules:\n$head", "$tail\n$next"),
                )) {
                    assertMultipartNarrativeReply(
                        literal, literal.joinToString("\n"), narrativePrompt = modeBlock, previousAssistant = prior,
                    )
                }
                val raw = parts.joinToString("\n")
                assertMultipartNarrativeReply(
                    parts, raw, narrativePrompt = modeBlock, previousAssistant = prior,
                    userText = "Copy this text verbatim:\n$raw",
                )
                val variant = "A genuine line that differs from the dispatched instruction."
                assertMultipartNarrativeReply(
                    listOf("$first\n$head", "$variant\n$next"), "$first\n$variant\n$next",
                    narrativePrompt = modeBlock, previousAssistant = prior,
                )
                val message = ChatMessage.create("fixture", MessageRole.ASSISTANT, done).copy(narrativeMode = mode)
                val reloaded = Json.decodeFromString(ChatMessage.serializer(), Json.encodeToString(ChatMessage.serializer(), message))
                assertEquals(expected, reloaded.displayContent)
                val following = "The sailor tightened the knot."
                assertNarrativeReplyDeltas(
                    listOf(following), listOf(following), following, previousAssistant = reloaded,
                    currentUserText = "Continue.", experimental = true, toolCall = true,
                )
            }
        }
    }

    @Test
    fun `a dispatched explicit WorldBook XML copy is protected without granting RAG wrapper authority`() = runBlocking {
        val copyBody = "The copied inscription reads: do not ring twice."
        for (mode in ChatNarrativeMode.entries) {
            val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "They passed the first watchtower.").copy(narrativeMode = mode)
            val pair = PromptTemplates.narrativeModeHistoryReply(copyBody, mode)
            val directive = "Copy the following XML exactly:\n$pair"
            for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
                for ((entry, rag, user) in listOf(
                    Triple(directive, null, "Continue without the keyword."),
                    Triple("The log quotes a sample below:\n$pair", null, "Check the sigil."),
                    Triple(null, directive, "Check the sigil."),
                )) {
                    assertNarrativeReplyDeltas(
                        listOf(pair), listOf(copyBody), copyBody, previousAssistant = prior, currentUserText = user,
                        worldBookEntry = entry, ragReferenceText = rag, experimental = experimental, toolCall = toolCall,
                        requestNarrativeMode = mode,
                    )
                }
                for ((core, player) in listOf(directive to null, null to directive)) {
                    assertNarrativeReplyDeltas(
                        listOf(pair), listOf(pair), pair, previousAssistant = prior, currentUserText = "Continue.",
                        characterCoreInstructions = core, playerOverride = player, experimental = experimental, toolCall = toolCall,
                        requestNarrativeMode = mode,
                    )
                }
                val prefix = pair.substringBeforeLast('\n')
                assertNarrativeReplyDeltas(
                    listOf(prefix, pair.removePrefix(prefix)), listOf(prefix, pair), pair,
                    previousAssistant = prior, currentUserText = "Check the sigil.",
                    worldBookEntry = directive, experimental = experimental, toolCall = toolCall, requestNarrativeMode = mode,
                )
                assertNarrativeReplyDeltas(
                    listOf(pair), listOf(pair), pair, previousAssistant = prior.copy(content = copyBody),
                    currentUserText = "Check the sigil.", worldBookEntry = directive, experimental = experimental,
                    toolCall = toolCall, requestNarrativeMode = mode,
                )
                assertNarrativeReplyDeltas(
                    listOf(pair), listOf(pair), pair, fixtureHistory = false, currentUserText = "Continue.",
                    experimental = experimental, toolCall = toolCall, requestNarrativeMode = mode,
                )
            }
        }
    }

    @Test
    fun `only a sent active WorldBook can reserve an expressly requested standalone XML close`() = runBlocking {
        val closer = "</历史回复>"
        val instruction = "Print the literal closing tag only on its own line:\n$closer"
        val story = "She folded the paper."
        val raw = "$story\n$closer"
        for (mode in ChatNarrativeMode.entries) {
            val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "They crossed the bridge.").copy(narrativeMode = mode)
            for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
                for ((worldBook, rag, user) in listOf(
                    Triple(instruction, null, "Continue the tale."),
                    Triple("The log stores a sample marker:\n$closer", null, "Check the sigil."),
                    Triple(null, instruction, "Check the sigil."),
                )) {
                    assertNarrativeReplyDeltas(
                        listOf(raw), listOf(story), story, previousAssistant = prior, currentUserText = user,
                        worldBookEntry = worldBook, ragReferenceText = rag, experimental = experimental, toolCall = toolCall,
                        requestNarrativeMode = mode,
                    )
                }
                assertNarrativeReplyDeltas(
                    listOf(raw), listOf(raw), raw, previousAssistant = prior, currentUserText = instruction,
                    experimental = experimental, toolCall = toolCall, requestNarrativeMode = mode,
                )
                assertNarrativeReplyDeltas(
                    listOf(story, "\n$closer"), listOf(story, raw), raw, previousAssistant = prior,
                    currentUserText = "Check the sigil.", worldBookEntry = instruction,
                    experimental = experimental, toolCall = toolCall, requestNarrativeMode = mode,
                )
            }
        }
    }

    @Test
    fun `a visible display caption can introduce a literal terminal mode title`() = runBlocking {
        for (mode in ChatNarrativeMode.entries) {
            val prompt = PromptTemplates.narrativeModeSystemPrompt(mode)
            val heading = prompt.lineSequence().first()
            for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
                for (sentence in listOf("She switched off the display.", "The screen shows a different story:", "屏幕的亮度调高了。")) {
                    assertNarrativeReplyDeltas(
                        listOf("$sentence\n$heading"), listOf(sentence), sentence, narrativePrompt = prompt,
                        experimental = experimental, toolCall = toolCall,
                    )
                }
                for (caption in listOf(
                    "She read the title aloud:", "The screen displays only this title:",
                    "设备的屏幕上只显示了这一行标题：",
                )) {
                    val raw = "$caption\n$heading"
                    assertNarrativeReplyDeltas(
                        listOf(raw), listOf(raw), raw, narrativePrompt = prompt, experimental = experimental, toolCall = toolCall,
                    )
                }
            }
        }
    }

    @Test
    fun `unlabelled exact switched-mode directive needs actual issued authority and separate prose`() = runBlocking {
        val prose = "门终于开了。"
        for (mode in ChatNarrativeMode.entries) {
            val previous = ChatNarrativeMode.entries.first { it != mode }
            val issued = PromptTemplates.narrativeModeTailSystemPrompt(mode, previous)
            val switch = issued.lineSequence().drop(1).first()
            val raw = "$switch\n$prose"
            val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "门还没有打开。").copy(narrativeMode = previous)
            for ((experimental, toolCall) in listOf(false to false, true to true)) {
                for (candidate in listOf(
                    "> $switch\n\n$prose", "~~~text\n$switch\n~~~\n$prose", "$switch as cited below.\n$prose",
                    switch.take(25) + "\n$prose",
                )) {
                    assertNarrativeReplyDeltas(
                        listOf(candidate), listOf(candidate), candidate, narrativeTail = issued, previousAssistant = prior,
                        experimental = experimental, toolCall = toolCall,
                    )
                }
                assertNarrativeReplyDeltas(
                    listOf(raw), listOf(raw), raw, narrativeTail = PromptTemplates.narrativeModeTailSystemPrompt(mode, mode),
                    previousAssistant = prior, experimental = experimental, toolCall = toolCall,
                )
                assertNarrativeReplyDeltas(
                    listOf(raw), listOf(raw), raw, narrativeTail = issued, previousAssistant = prior.copy(narrativeMode = mode),
                    experimental = experimental, toolCall = toolCall,
                )
                assertNarrativeReplyDeltas(
                    listOf(raw), listOf(raw), raw, narrativeTail = issued, currentUserText = "Copy this text verbatim:\n$raw",
                    previousAssistant = prior,
                    experimental = experimental, toolCall = toolCall,
                )
                assertNarrativeReplyDeltas(
                    listOf(raw), listOf(prose), prose, narrativeTail = issued, currentUserText = "Continue.", previousAssistant = prior,
                    experimental = experimental, toolCall = toolCall,
                )
            }
        }
    }

    @Test
    fun `truncated interior stock lines leave the short uncertain list fragment intact`() = runBlocking {
        val prose = "门开了。"
        for (mode in ChatNarrativeMode.entries) {
            val prompt = PromptTemplates.narrativeModeSystemPrompt(mode)
            val (heading, instruction) = prompt.lines()
            val fragment = "1. 镜"
            val raw = "$prose\n$heading\n$instruction\n\n$fragment"
            val expected = "$prose\n$fragment"
            for (experimental in listOf(false, true)) {
                val authored = "$prose\n~~~text\n$heading\n$instruction\n\n$fragment\n~~~"
                assertNarrativeReplyDeltas(
                    listOf(authored), listOf(authored), authored, narrativePrompt = prompt, experimental = experimental,
                    finishReason = "length",
                )
                assertNarrativeReplyDeltas(
                    listOf(raw), listOf(expected), expected, narrativePrompt = prompt, experimental = experimental,
                    finishReason = "length",
                )
            }
        }
    }

    @Test
    fun `a newly generated own-line frame preserves intentional body line endings and hard-break spaces`() = runBlocking {
        val bodies = listOf(
            "\n\r\nThe door closed.  \r\n    She turned the key.  \r\n\n",
            "\nThe door closed.  \nShe turned the key.\n",
            "\r\nThe door closed.\r\nShe turned the key.\r\n",
        )
        for (mode in ChatNarrativeMode.entries) {
            val earlier = ChatMessage.create("fixture", MessageRole.ASSISTANT, "An earlier paragraph.").copy(narrativeMode = mode)
            for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
                for (body in bodies) {
                    val raw = PromptTemplates.narrativeModeHistoryReply(body, mode)
                    assertNarrativeReplyDeltas(
                        listOf(raw), listOf(raw), raw, fixtureHistory = false, currentUserText = "Continue.",
                        experimental = experimental, toolCall = toolCall, requestNarrativeMode = mode,
                    )
                    assertNarrativeReplyDeltas(
                        listOf(raw), listOf(body), body, previousAssistant = earlier, currentUserText = "Continue.",
                        experimental = experimental, toolCall = toolCall, requestNarrativeMode = mode,
                    )
                }
            }
        }
    }

    @Test
    fun `historical incidental title text cannot reserve an entire newly issued echo`() = runBlocking {
        val scene = "She raised the sail."
        for (mode in ChatNarrativeMode.entries) {
            val issued = PromptTemplates.narrativeModeTailSystemPrompt(mode, null)
            val title = issued.lineSequence().first()
            val raw = "$issued\n$scene"
            for ((experimental, toolCall) in listOf(false to false, true to true)) {
                assertNarrativeReplyDeltas(
                    listOf(raw), listOf(scene), scene, narrativeTail = issued,
                    previousUserText = "Yesterday we discussed the title $title in an example.", currentUserText = "Continue.",
                    experimental = experimental, toolCall = toolCall,
                )
                assertNarrativeReplyDeltas(
                    listOf(raw), listOf(raw), raw, narrativeTail = issued, previousUserText = "Print this title verbatim: $title.",
                    currentUserText = "Continue.", experimental = experimental, toolCall = toolCall,
                )
                assertNarrativeReplyDeltas(
                    listOf(raw), listOf(raw), raw, narrativeTail = issued,
                    currentUserText = "Copy this text verbatim:\n$raw", experimental = experimental, toolCall = toolCall,
                )
            }
        }
    }

    @Test
    fun `an active sent WorldBook can explicitly reserve a literal standalone XML opening`() = runBlocking {
        val mode = ChatNarrativeMode.FOCUS
        val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "Earlier scene.").copy(narrativeMode = mode)
        val opening = PromptTemplates.narrativeModeHistoryReply("", mode).substringBefore('\n')
        val directive = "Please show the literal opening tag only on its own line, then explain it:\n$opening"
        val prose = "This line is the label."
        val raw = "$opening\n$prose"
        for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
            for ((world, rag, user) in listOf(
                Triple(directive, null, "Continue without the keyword."),
                Triple("The archive mentions this marker:\n$opening", null, "Check the sigil."),
                Triple(null, directive, "Check the sigil."),
            )) assertNarrativeReplyDeltas(
                listOf(raw), listOf(prose), prose, previousAssistant = prior, currentUserText = user,
                worldBookEntry = world, ragReferenceText = rag, experimental = experimental, toolCall = toolCall,
                requestNarrativeMode = mode,
            )
            assertNarrativeReplyDeltas(
                listOf(raw), listOf(raw), raw, previousAssistant = prior, currentUserText = directive,
                experimental = experimental, toolCall = toolCall, requestNarrativeMode = mode,
            )
            assertNarrativeReplyDeltas(
                listOf(raw), listOf(raw), raw, previousAssistant = prior, currentUserText = "Check the sigil.",
                worldBookEntry = directive, experimental = experimental, toolCall = toolCall, requestNarrativeMode = mode,
            )
        }
    }

    @Test
    fun `explicit title directions in actual sent character examples and settings reserve the title`() = runBlocking {
        val heading = PromptTemplates.sectionHeading(PromptTemplates.SECTION_PREVIOUS_TURN)
        val directive = "Please start each reply with this literal heading verbatim:\n$heading"
        val story = "She drew the map."
        val raw = "$heading\n$story"
        val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "The room was dark.")
        for (experimental in listOf(false, true)) {
            for ((example, rag) in listOf("The notebook mentions $heading." to null, directive.prependIndent("> ") to null, null to directive)) {
                assertNarrativeReplyDeltas(
                    listOf(raw), listOf(story), story, previousAssistant = prior, currentUserText = "Continue.",
                    narrativeModesEnabled = false, issueRoutineHistoryHeading = true, experimental = experimental,
                    characterExample = example, ragReferenceText = rag,
                )
            }
            for ((example, setting) in listOf(directive to null, null to directive)) assertNarrativeReplyDeltas(
                listOf(raw), listOf(raw), raw, previousAssistant = prior, currentUserText = "Continue.",
                narrativeModesEnabled = false, issueRoutineHistoryHeading = true, experimental = experimental,
                characterExample = example, characterBasicSetting = setting,
            )
        }
    }

    @Test
    fun `an entire stable issued narrative section can be removed between separate prose lines`() = runBlocking {
        val wanted = "The sailor raised the lamp.\nShe heard another bell."
        for (mode in ChatNarrativeMode.entries) {
            val block = PromptAssembler().assembleCachePromptLayers(CharacterCard.create("Fixture"), narrativeMode = mode)
                .narrativeModeSystemPrompt
            assertTrue(block.startsWith(PromptTemplates.sectionHeading(PromptTemplates.SECTION_NARRATIVE_MODE)))
            val raw = "The sailor raised the lamp.\n$block\nShe heard another bell."
            for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
                assertNarrativeReplyDeltas(
                    listOf(raw), listOf(wanted), wanted, narrativePrompt = block, currentUserText = "Continue.",
                    experimental = experimental, toolCall = toolCall,
                )
                val literal = "The sailor raised the lamp.\n~~~text\n$block\n~~~\nShe heard another bell."
                assertNarrativeReplyDeltas(
                    listOf(literal), listOf(literal), literal, narrativePrompt = block, experimental = experimental, toolCall = toolCall,
                )
            }
        }
    }

    @Test
    fun `a logged whole issued narrative section remains literal at EOF and before status`() = runBlocking {
        val lead = "Below is a literal quotation copied from the history log."
        val status = "${MarkdownCodeFences.legacyStatusOpening}\n时间：晚上\n${MarkdownCodeFences.legacyStatusLabel}```"
        for (mode in ChatNarrativeMode.entries) {
            val stable = PromptAssembler().assembleCachePromptLayers(CharacterCard.create("Fixture"), narrativeMode = mode)
                .narrativeModeSystemPrompt
            val tail = PromptTemplates.narrativeModeTailSystemPrompt(mode, null)
            for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
                for (block in listOf(stable, tail)) for (ending in listOf("", "\n$status")) {
                    val raw = "$lead\n$block$ending"
                    assertNarrativeReplyDeltas(
                        listOf(raw), listOf(raw), raw, narrativePrompt = stable, narrativeTail = tail,
                        experimental = experimental, toolCall = toolCall,
                    )
                }
            }
        }
    }

    @Test
    fun `an actual regeneration target can supply switch provenance when earlier history is in another mode`() = runBlocking {
        val mode = ChatNarrativeMode.ADVANCE
        val earlier = ChatMessage.create("fixture", MessageRole.ASSISTANT, "The first door was open.").copy(narrativeMode = mode)
        val target = ChatMessage.create("fixture", MessageRole.ASSISTANT, "The second door was shut.").copy(narrativeMode = ChatNarrativeMode.FOCUS)
        val chosen = ChatHistoryPromptPolicy.previousNarrativeMode(listOf(earlier), target)
        assertEquals(ChatNarrativeMode.FOCUS, chosen)
        val tail = PromptTemplates.narrativeModeTailSystemPrompt(mode, chosen)
        val story = "The restored door opened."
        val raw = tail.lineSequence().drop(1).first() + "\n$story"
        for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
            assertNarrativeReplyDeltas(
                listOf(raw), listOf(raw), raw, previousAssistant = earlier, narrativeTail = tail,
                currentUserText = "Try again.", experimental = experimental, toolCall = toolCall,
            )
            assertNarrativeReplyDeltas(
                listOf(raw), listOf(story), story, previousAssistant = earlier, narrativeTail = tail,
                currentUserText = "Try again.", experimental = experimental, toolCall = toolCall,
                dispatchActualModeTail = true, regenerationTarget = target,
            )
        }
    }

    @Test
    fun `the actual first request default-to-advance transition has proof without projected history`() = runBlocking {
        val previous = ChatHistoryPromptPolicy.previousNarrativeMode(emptyList())
        assertEquals(ChatNarrativeMode.FOCUS, previous)
        val mode = ChatNarrativeMode.ADVANCE
        val tail = PromptTemplates.narrativeModeTailSystemPrompt(mode, previous)
        val stable = PromptAssembler().assembleCachePromptLayers(CharacterCard.create("Fixture"), narrativeMode = mode)
            .narrativeModeSystemPrompt
        val switch = tail.lineSequence().drop(1).first()
        val story = "The journey began."
        val raw = "$switch\n$story"
        for (experimental in listOf(false, true)) {
            for (quoted in listOf("> $switch\n\n$story", "$switch ... and that was fictional.\n$story")) assertNarrativeReplyDeltas(
                listOf(quoted), listOf(quoted), quoted, fixtureHistory = false, narrativeTail = tail, narrativePrompt = stable,
                currentUserText = "Begin the scene.", experimental = experimental,
            )
            assertNarrativeReplyDeltas(
                listOf(raw), listOf(story), story, fixtureHistory = false, narrativeTail = tail, narrativePrompt = stable,
                currentUserText = "Begin the scene.", experimental = experimental, dispatchActualModeTail = true,
            )
        }
    }

    @Test
    fun `actually sent character settings can declare a new XML reply template`() = runBlocking {
        assertDispatchedXmlTemplates(fromWorldBook = false)
    }

    @Test
    fun `actually sent active WorldBook can declare a new XML reply template`() = runBlocking {
        assertDispatchedXmlTemplates(fromWorldBook = true)
    }

    private suspend fun assertDispatchedXmlTemplates(fromWorldBook: Boolean) {
        val mode = ChatNarrativeMode.FOCUS
        val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "The old door closed.").copy(narrativeMode = mode)
        val template = PromptTemplates.narrativeModeHistoryReply("[Story]", mode)
        val instruction = "Use the following XML template for each reply and keep both tags verbatim:\n$template"
        val fresh = "The widow opened the shutters."
        val raw = PromptTemplates.narrativeModeHistoryReply(fresh, mode)
        for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
            assertNarrativeReplyDeltas(
                listOf(raw), listOf(fresh), fresh, previousAssistant = prior, currentUserText = "Check the sigil.",
                ragReferenceText = instruction, experimental = experimental, toolCall = toolCall, requestNarrativeMode = mode,
            )
            assertNarrativeReplyDeltas(
                listOf(raw), listOf(fresh), fresh, previousAssistant = prior, currentUserText = "Stay at the gate.",
                worldBookEntry = instruction, experimental = experimental, toolCall = toolCall, requestNarrativeMode = mode,
            )
            for ((character, world) in listOf(instruction.prependIndent("> ") to null, null to "The log mentions this example:\n$template")) {
                assertNarrativeReplyDeltas(
                    listOf(raw), listOf(fresh), fresh, previousAssistant = prior, currentUserText = "Check the sigil.",
                    characterBasicSetting = character, worldBookEntry = world,
                    experimental = experimental, toolCall = toolCall, requestNarrativeMode = mode,
                )
            }
            assertNarrativeReplyDeltas(
                listOf(raw), listOf(raw), raw, previousAssistant = prior, currentUserText = "Continue.",
                characterCoreInstructions = instruction, experimental = experimental, toolCall = toolCall, requestNarrativeMode = mode,
            )
            assertNarrativeReplyDeltas(
                listOf(raw), listOf(raw), raw, previousAssistant = prior, currentUserText = "Check the sigil.",
                characterBasicSetting = instruction.takeUnless { fromWorldBook },
                worldBookEntry = instruction.takeIf { fromWorldBook },
                experimental = experimental, toolCall = toolCall, requestNarrativeMode = mode,
            )
            if (fromWorldBook) {
                assertNarrativeReplyDeltas(
                    listOf(raw), listOf(fresh), fresh, previousAssistant = prior, currentUserText = "Check the sigil.",
                    worldBookEntry = instruction, playerOverride = "Do not output the following history tags:\n$template",
                    experimental = experimental, toolCall = toolCall, requestNarrativeMode = mode,
                )
            }
        }
    }

    @Test
    fun `explicit immediate displayed reply citation with surrounding XML keeps only the exact projection`() = runBlocking {
        val priorBody = "A candle burned  \r\n    on the shelf."
        val commentary = "\n\nThe quotation shows the lamp was still burning."
        val directive = "Reproduce the immediately preceding displayed reply literally, with the surrounding XML included, then tell me what the quote demonstrates."
        for (mode in ChatNarrativeMode.entries) {
            val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, priorBody).copy(narrativeMode = mode)
            val projection = PromptTemplates.narrativeModeHistoryReply(priorBody, mode)
            val raw = projection + commentary
            for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
                for (other in listOf(
                    "Go on with the scene.",
                    directive.replace("with the surrounding XML included", "without the surrounding XML"),
                    "The panel said: “$directive”",
                    "Reproduce the immediately preceding displayed reply literally; explain the XML separately.",
                )) assertNarrativeReplyDeltas(
                    listOf(raw), listOf(priorBody + commentary), priorBody + commentary, previousAssistant = prior,
                    currentUserText = other, experimental = experimental, toolCall = toolCall, requestNarrativeMode = mode,
                )
                val documented = "$projection\n\nThese history tags are only an example."
                assertNarrativeReplyDeltas(
                    listOf(documented), listOf(documented), documented, previousAssistant = prior,
                    currentUserText = "Continue.", experimental = experimental, toolCall = toolCall, requestNarrativeMode = mode,
                )
                assertNarrativeReplyDeltas(
                    listOf(projection, commentary), listOf(projection, raw), raw, previousAssistant = prior,
                    currentUserText = directive, experimental = experimental, toolCall = toolCall, requestNarrativeMode = mode,
                )
                val novel = "A different passage."
                assertNarrativeReplyDeltas(
                    listOf(PromptTemplates.narrativeModeHistoryReply(novel, mode)), listOf(novel), novel, previousAssistant = prior,
                    currentUserText = directive, experimental = experimental, toolCall = toolCall, requestNarrativeMode = mode,
                )
            }
        }
    }

    @Test
    fun `a genuine issued frame around new indented LF code keeps its own extra inner breaks`() = runBlocking {
        val mode = ChatNarrativeMode.FOCUS
        val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "An older example.").copy(narrativeMode = mode)
        val code = "\nThe example follows.\n\n    val stored = 7  \n"
        val raw = PromptTemplates.narrativeModeHistoryReply(code, mode)
        for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
            val paddedOrdinary = PromptTemplates.narrativeModeHistoryReply("\nA brief ordinary sentence.  \n", mode)
            for (reason in listOf(if (toolCall) "tool_calls" else "stop", "length")) {
                assertNarrativeReplyDeltas(
                    listOf(paddedOrdinary), listOf("A brief ordinary sentence."), "A brief ordinary sentence.",
                    previousAssistant = prior, experimental = experimental, toolCall = toolCall, finishReason = reason,
                    requestNarrativeMode = mode,
                )
                assertNarrativeReplyDeltas(
                    listOf(raw), listOf(code), code, previousAssistant = prior, experimental = experimental,
                    toolCall = toolCall, finishReason = reason, requestNarrativeMode = mode,
                )
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
    fun `held Stop keeps the exact sample when the next call explains the XML mode attribute`(): Unit = runBlocking {
        assertXmlAttributeExample(checkHeldStop = true)
    }

    @Test
    fun `Done keeps the exact sample when the next call explains the XML mode attribute`(): Unit = runBlocking {
        assertXmlAttributeExample(checkHeldStop = false)
    }

    private suspend fun assertXmlAttributeExample(checkHeldStop: Boolean) {
        val mode = ChatNarrativeMode.FOCUS
        val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "The rain stopped.").copy(narrativeMode = mode)
        val sample = PromptTemplates.narrativeModeHistoryReply("示例内容", mode)
        val explanation = "The opening element declares the narrative mode in its attribute."
        val request = "Explain this kind of XML."
        val expected = "$sample\n$explanation"
        assertNarrativeReplyDeltas(
            listOf(expected), listOf(expected), expected, experimental = false, previousAssistant = prior,
            currentUserText = request, requestNarrativeMode = mode,
        )
        val second = PromptTemplates.narrativeModeHistoryReply("The storm passed.", mode)
        assertMultipartNarrativeReply(
            listOf(sample, second), "示例内容\nThe storm passed.", userText = request, previousAssistant = prior, checkHeldStop = true,
        )
        val prose = "Her opening remark declares what she intends to do."
        assertMultipartNarrativeReply(
            listOf(sample, prose), "示例内容\n$prose", userText = request, previousAssistant = prior,
        )
        assertMultipartNarrativeReply(
            listOf("The storm passed.\n</历史回复>", explanation),
            "The storm passed.\n$explanation", userText = request, previousAssistant = prior,
        )
        assertMultipartNarrativeReply(
            listOf(sample, explanation), expected, userText = request, previousAssistant = prior, checkHeldStop = checkHeldStop,
        )
    }

    @Test
    fun `verbatim before with its XML history tags preserves the actual latest projection`(): Unit = runBlocking {
        val mode = ChatNarrativeMode.FOCUS
        val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "The rain stopped.").copy(narrativeMode = mode)
        val projection = PromptTemplates.narrativeModeHistoryReply(prior.displayContent, mode)
        val tail = "\n\nThe opening element carries the narrative mode."
        val raw = projection + tail
        val directive = "Quote the previous assistant reply verbatim with its XML history tags, then explain it."
        for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
            for (negative in listOf("Continue.", directive.replace("with its", "without its"), "He read: “$directive”")) {
                val ordinary = prior.displayContent + tail
                assertNarrativeReplyDeltas(
                    listOf(raw), listOf(ordinary), ordinary, previousAssistant = prior, requestNarrativeMode = mode,
                    currentUserText = negative, experimental = experimental, toolCall = toolCall,
                )
            }
            assertNarrativeReplyDeltas(
                listOf(projection, tail), listOf(projection, raw), raw, previousAssistant = prior, requestNarrativeMode = mode,
                currentUserText = directive, experimental = experimental, toolCall = toolCall, onCompleted = { done ->
                    val saved = ChatMessage.create("fixture", MessageRole.ASSISTANT, done).copy(narrativeMode = mode)
                    val restored = Json.decodeFromString(ChatMessage.serializer(), Json.encodeToString(ChatMessage.serializer(), saved))
                    assertEquals(raw, restored.displayContent)
                },
            )
        }
    }

    @Test
    fun `a reply consisting only of the actually issued first switch sentence has no story`(): Unit = runBlocking {
        val mode = ChatNarrativeMode.ADVANCE
        val tail = PromptTemplates.narrativeModeTailSystemPrompt(mode, ChatNarrativeMode.FOCUS)
        val switch = tail.lineSequence().drop(1).first()
        for (experimental in listOf(false, true)) {
            for (control in listOf("> $switch", "$switch That's all she remembered.")) {
                assertNarrativeReplyDeltas(
                    listOf(control), listOf(control), control, fixtureHistory = false, narrativeTail = tail,
                    currentUserText = "Begin the scene.", experimental = experimental, dispatchActualModeTail = true,
                    requestNarrativeMode = mode,
                )
            }
            for (copied in listOf(switch, "$switch\n \n")) {
                assertNarrativeReplyDeltas(
                    listOf(copied), listOf(copied), copied, fixtureHistory = false, narrativeTail = tail,
                    currentUserText = "Print this text verbatim:\n$switch", experimental = experimental,
                    dispatchActualModeTail = true, requestNarrativeMode = mode,
                )
            }
            for (echo in listOf(switch, "$switch\n")) {
                assertNarrativeReplyDeltas(
                    listOf(echo), listOf(""), "", fixtureHistory = false, narrativeTail = tail,
                    currentUserText = "Begin the scene.", experimental = experimental, dispatchActualModeTail = true,
                    requestNarrativeMode = mode, onCompleted = { done ->
                        assertTrue(runCatching { ChatHistoryPromptPolicy.requirePersistableAssistantBody(done) }.isFailure)
                    },
                )
            }
        }
    }

    @Test
    fun `a genuine frame around fenced code keeps its internal and external breaks`(): Unit = runBlocking {
        val mode = ChatNarrativeMode.FOCUS
        val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "The rain stopped.").copy(narrativeMode = mode)
        val body = "\n```kotlin\nval stored = 7  \n```\n"
        val suffix = "\n\n"
        val raw = PromptTemplates.narrativeModeHistoryReply(body, mode) + suffix
        for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
            val prose = "An ordinary reply."
            assertNarrativeReplyDeltas(
                listOf(PromptTemplates.narrativeModeHistoryReply("\n$prose  \n", mode) + suffix), listOf(prose), prose,
                previousAssistant = prior, experimental = experimental, toolCall = toolCall, requestNarrativeMode = mode,
            )
            for (reason in listOf(if (toolCall) "tool_calls" else "stop", "length")) {
                val expected = body + suffix
                assertNarrativeReplyDeltas(
                    listOf(raw), listOf(expected), expected, previousAssistant = prior, experimental = experimental,
                    toolCall = toolCall, finishReason = reason, requestNarrativeMode = mode,
                )
            }
        }
    }

    @Test
    fun `a genuine code fence carries its inner breaks without Markdown hard break spaces`(): Unit = runBlocking {
        val mode = ChatNarrativeMode.FOCUS
        val prior = ChatMessage.create("fixture", MessageRole.ASSISTANT, "The rain stopped.").copy(narrativeMode = mode)
        val suffix = "\n\n"
        val code = "\n```kotlin\nval stored = 7\n```\n"
        val prose = "The value was stored."
        for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
            for (reason in listOf(if (toolCall) "tool_calls" else "stop", "length")) {
                for ((payload, expected) in listOf("\n$prose\n" to prose, code to code + suffix)) {
                    assertNarrativeReplyDeltas(
                        listOf(PromptTemplates.narrativeModeHistoryReply(payload, mode) + suffix), listOf(expected), expected,
                        previousAssistant = prior, experimental = experimental, toolCall = toolCall,
                        finishReason = reason, requestNarrativeMode = mode,
                    )
                }
            }
        }
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

    private fun projectAssistant(
        cleanup: NarrativeHistoryEcho.RequestCleanup, assistant: ChatMessage, narrativeModesEnabled: Boolean = true,
    ): ChatApiMessage {
        val body = assistant.displayContent
        val projection = ChatHistoryPromptPolicy.renderedHistory(
            assistant, body, narrativeModesEnabled = narrativeModesEnabled,
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
        characterCoreInstructions: String? = null,
        supplementaryInstructions: String? = null,
        playerOverride: String? = null,
        globalPersona: String? = null,
        finishReason: String = if (toolCall) "tool_calls" else "stop",
        onCompleted: (String) -> Unit = {},
        earlierAssistant: ChatMessage? = null,
        narrativeModesEnabled: Boolean = true,
        issueRoutineHistoryHeading: Boolean = false,
        worldBookEntry: String? = null,
        ragReferenceText: String? = null,
        requestNarrativeMode: ChatNarrativeMode = ChatNarrativeMode.ADVANCE,
        characterExample: String? = null,
        characterBasicSetting: String? = null,
        dispatchActualModeTail: Boolean = false,
        regenerationTarget: ChatMessage? = null,
        checkHeldStop: Boolean = false,
        releaseTailAfter: Int = deltaTexts.lastIndex,
    ) {
        val cleanup = NarrativeHistoryEcho.forRequest(requestNarrativeMode.takeIf { narrativeModesEnabled }, formatCard)
        val histories = if (previousAssistant != null) listOfNotNull(earlierAssistant, previousAssistant)
        else if (fixtureHistory) narrativeHistoryFixtures() else emptyList()
        val dispatchedModeTail = if (dispatchActualModeTail) {
            ChatHistoryPromptPolicy.narrativeTailDispatch(requestNarrativeMode.takeIf { narrativeModesEnabled }, histories, regenerationTarget)
        } else null
        val wireModeTail = dispatchedModeTail?.text ?: narrativeTail
        if (dispatchedModeTail != null) assertEquals(narrativeTail, wireModeTail)
        previousUserText?.let { cleanup.recordNarrativePrompt("", it) }
        val playerSetting = playerOverride?.takeIf(String::isNotBlank) ?: globalPersona
        val card = CharacterCard.create("Gatekeeper").copy(
            systemPrompt = characterCoreInstructions.orEmpty(), postHistoryInstructions = characterPostHistoryInstructions.orEmpty(),
            mesExample = characterExample.orEmpty(), basicSetting = characterBasicSetting.orEmpty(),
        )
        val worldBookPrompt = worldBookEntry?.let {
            val engine = WorldBookEngine()
            val book = WorldBook.create("Synthetic active setting").copy(
                entries = listOf(WorldBookEntry.create(listOf("sigil"), it)),
            )
            val user = ChatMessage.create("fixture", MessageRole.USER, currentUserText.orEmpty())
            val active = engine.evaluateAll(listOf(book), listOfNotNull(previousAssistant, user))
            assertEquals(if ("sigil" in user.displayContent) 1 else 0, active.size)
            engine.buildWorldBookPrompt(active, card.effectiveBotName, "Keeper")
        }
        val hasStableLayers = characterCoreInstructions != null || supplementaryInstructions != null ||
            playerSetting != null || worldBookEntry != null || ragReferenceText != null ||
            characterExample != null || characterBasicSetting != null
        val layers = if (hasStableLayers || characterPostHistoryInstructions != null) {
            PromptAssembler().assembleCachePromptLayers(
                card,
                worldBookPrompt = worldBookPrompt,
                ragResults = listOfNotNull(ragReferenceText?.let {
                    RetrievedKnowledgeCard("fixture-document", ChunkSourceType.DOCUMENT, "document", "Fixture document", it)
                }),
                supplementarySetting = supplementaryInstructions, playerSetting = playerSetting, playerName = "Keeper",
            )
        } else null
        val corePrefix = if (layers != null && hasStableLayers) {
            cleanup.recordDispatchedCharacterCore(layers.coreSystemPrompt, !characterCoreInstructions.isNullOrBlank())
            cleanup.recordDispatchedCharacterSettingsHeadings(layers.stableContextSystemPrompt)
            layers.recordDispatchedWorldBookHeadings(cleanup)
            cleanup.recordDispatchedSupplementarySettings(layers.supplementarySystemPrompt)
            cleanup.recordDispatchedPlayerSettings(layers.playerSystemPrompt)
            buildCcbStablePrefixMessages(
                layers.coreSystemPrompt, layers.stableContextSystemPrompt,
                positionedRequirementsSystemPrompt = "", formatPromptPosition = FormatPromptPosition.END,
                supplementarySystemPrompt = layers.supplementarySystemPrompt, playerSystemPrompt = layers.playerSystemPrompt,
                settingReferenceSystemPrompt = layers.settingReferenceSystemPrompt,
            )
        } else emptyList()
        cleanup.recordNarrativePrompt(narrativePrompt.orEmpty(), currentUserText)
        val requestMessages = (corePrefix.ifEmpty { messages.take(1) } + messages.drop(1)).toMutableList()
        if (currentUserText != null) {
            val userIndex = requestMessages.indexOfLast { it.role == "user" }
            requestMessages[userIndex] = ChatApiMessage.text("user", currentUserText)
            cleanup.recordCurrentLiteralTags(currentUserText)
        }
        if (narrativePrompt != null) requestMessages.add(1, ChatApiMessage.text("system", narrativePrompt))
        val cardRequirements = formatCard?.let { PromptTemplates.currentTurnOutputRequirementsSystemPrompt(it, 300) }
        cardRequirements?.let { requestMessages.add(ChatApiMessage.text("system", it)) }
        if (wireModeTail != null) {
            requestMessages.add(ChatApiMessage.text("system", wireModeTail))
            if (dispatchedModeTail != null) dispatchedModeTail.record(cleanup, currentUserText)
            else cleanup.recordNarrativePrompt(wireModeTail, currentUserText)
        }
        val historyMessages = histories.map { projectAssistant(cleanup, it, narrativeModesEnabled) }
        for (history in historyMessages) {
            requestMessages.add(1, history)
        }
        previousUserText?.let { requestMessages.add(1, ChatApiMessage.text("user", it)) }
        val historyHeading = PromptTemplates.sectionHeading(PromptTemplates.SECTION_PREVIOUS_TURN)
        if (issueRoutineHistoryHeading && historyMessages.isNotEmpty()) {
            requestMessages.add(1, ChatApiMessage.text("system", historyHeading))
            cleanup.recordDispatchedHistoryHeading(historyHeading)
        }
        val acknowledgement = PromptTemplates.CCB_POST_USER_ACK_ASSISTANT_PROMPT.trimIndent().trim()
        val strongSuffix = strongPrompt?.let { text ->
            FormatCardUserToolPolicy.strongPromptSystemSuffix(
                listOf(FormatCardUserToolConfig.strongPromptSuffix().copy(text = text)),
            )
        }
        val postHistory = layers?.tailSystemPrompt.takeIf { characterPostHistoryInstructions != null }
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
        if (strongSuffix != null && currentUserText != null) {
            assertStrongPromptDispatchRoles(requestMessages, currentUserText, strongSuffix)
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
            sse(deltas.take(releaseTailAfter + 1)),
            sse(deltas.drop(releaseTailAfter + 1) + listOf(chunk(reason = finishReason), "[DONE]")),
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
                        if (previews.lastIndex == releaseTailAfter) {
                            assertFalse(server.tailSent)
                            if (checkHeldStop) assertEquals(
                                "Held Stop", expectedPreviews[releaseTailAfter], cleanup.completedBody(preview, interrupted = true),
                            )
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
            if (corePrefix.isNotEmpty()) {
                val firstSystem = payload.getValue("messages").jsonArray.first().jsonObject
                assertEquals("system", firstSystem.getValue("role").jsonPrimitive.content)
                assertEquals(corePrefix.first().content, firstSystem.getValue("content"))
                characterCoreInstructions?.takeIf(String::isNotBlank)?.let { authored ->
                    assertTrue(authored.replace("{{char}}", "Gatekeeper") in firstSystem.getValue("content").jsonPrimitive.content)
                }
            }
            if (strongSuffix != null && currentUserText != null) {
                val user = context.indexOfFirst { entry ->
                    entry.jsonObject.getValue("role").jsonPrimitive.content == "user" &&
                        entry.jsonObject.getValue("content").jsonPrimitive.content == currentUserText
                }
                val wireRole = if (experimental) "system" else "assistant"
                val strong = context.indexOfFirst { entry ->
                    entry.jsonObject.getValue("role").jsonPrimitive.content == wireRole &&
                        entry.jsonObject.getValue("content").jsonPrimitive.content == strongSuffix
                }
                assertTrue("Expected the actual strong prompt after the active user", user >= 0 && strong > user)
            }
            if (supplementaryInstructions != null) {
                val setting = requireNotNull(layers).supplementarySystemPrompt
                assertTrue(supplementaryInstructions.replace("{{char}}", "Gatekeeper").trim() in setting)
                val settingIndex = context.indexOfFirst { it.jsonObject.getValue("content").jsonPrimitive.content == setting }
                val userIndex = context.indexOfFirst { it.jsonObject.getValue("content").jsonPrimitive.content == currentUserText }
                assertTrue("The evaluated supplementary setting must precede the current USER on the wire",
                    settingIndex >= 0 && userIndex > settingIndex)
            }
            if (playerSetting != null) {
                val player = requireNotNull(layers).playerSystemPrompt
                assertTrue(playerSetting.replace("{{user}}", "Keeper").trim() in player)
                val playerIndex = context.indexOfFirst { it.jsonObject.getValue("content").jsonPrimitive.content == player }
                val userIndex = context.indexOfFirst { it.jsonObject.getValue("content").jsonPrimitive.content == currentUserText }
                assertTrue("The evaluated player setting must precede the current USER", playerIndex >= 0 && userIndex > playerIndex)
                if (supplementaryInstructions != null) {
                    val settingsIndex = context.indexOfFirst {
                        it.jsonObject.getValue("content").jsonPrimitive.content == layers.supplementarySystemPrompt
                    }
                    assertTrue("The player setting must follow supplementary settings", settingsIndex >= 0 && playerIndex > settingsIndex)
                }
            }
            val sourceTexts = listOfNotNull(narrativePrompt, wireModeTail, currentUserText, previousUserText, cardRequirements, strongSuffix, postUserPrompt) +
                historyMessages.map { it.content.jsonPrimitive.content }
            for (source in sourceTexts) {
                assertTrue("Expected source in serialized reply request", context.any {
                    it.jsonObject.getValue("content").jsonPrimitive.content == source
                })
            }
            if (dispatchActualModeTail && regenerationTarget != null) {
                assertTrue(context.none {
                    regenerationTarget.displayContent in it.jsonObject.getValue("content").jsonPrimitive.content
                })
            }
            if (worldBookEntry != null) {
                val assembled = requireNotNull(layers).settingReferenceSystemPrompt
                val sources = context.map { it.jsonObject.getValue("content").jsonPrimitive.content }
                if (worldBookPrompt.isNullOrBlank()) {
                    assertTrue(worldBookEntry !in sources.joinToString("\n"))
                } else {
                    assertTrue(worldBookEntry in assembled)
                    assertTrue(assembled in sources)
                    if (playerSetting != null) assertTrue(
                        "The player setting must follow the direct WorldBook context on the wire",
                        sources.indexOf(assembled) < sources.indexOf(layers.playerSystemPrompt),
                    )
                }
            }
            if (ragReferenceText != null) {
                val assembled = requireNotNull(layers).settingReferenceSystemPrompt
                assertTrue(ragReferenceText in assembled)
                assertTrue(context.any { it.jsonObject.getValue("content").jsonPrimitive.content == assembled })
            }
            for (source in listOfNotNull(characterExample, characterBasicSetting)) {
                val setting = requireNotNull(layers).stableContextSystemPrompt
                assertTrue(source in setting)
                assertTrue(context.any { it.jsonObject.getValue("content").jsonPrimitive.content == setting })
            }
            if (issueRoutineHistoryHeading) {
                val wireRole = if (experimental) "system" else "assistant"
                assertEquals(historyMessages.isNotEmpty(), context.any {
                    val message = it.jsonObject
                    message.getValue("role").jsonPrimitive.content == wireRole &&
                        message.getValue("content").jsonPrimitive.content == historyHeading
                })
                if (!narrativeModesEnabled) {
                    val assistantBodies = context.filter { it.jsonObject.getValue("role").jsonPrimitive.content == "assistant" }
                        .map { it.jsonObject.getValue("content").jsonPrimitive.content }
                    assertTrue(histories.all { it.displayContent in assistantBodies })
                    assertTrue(assistantBodies.none { "<历史回复" in it })
                }
            }
            assertEquals(acknowledgement, context.last().jsonObject.getValue("content").jsonPrimitive.content)
            assertEquals(StreamEvent.Done, events.last())
            assertEquals(finishReason, completion?.finishReason)
            val completed = events.filterIsInstance<StreamEvent.Delta>().joinToString("") { it.text }
            assertEquals(rawPreviews.last(), completed)
            val previewCleanup = cleanup.streamingPreview()
            var previousLength = 0
            val live = previews.map { raw ->
                previewCleanup.appendedBody(raw, previousLength).also { previousLength = raw.length }
            }
            val stopped = previews.map { cleanup.completedBody(it, interrupted = true) }
            val done = cleanup.completedBody(completed, interrupted = finishReason == "length")
            assertEquals(
                "Reply Preview, Stop and Done",
                listOf(expectedPreviews, expectedPreviews, listOf(expectedCompleted)),
                listOf(live, stopped, listOf(done)),
            )
            onCompleted(done)
        }
    }

    private fun assertStrongPromptDispatchRoles(
        request: List<ChatApiMessage>,
        currentUserText: String,
        strongSuffix: String,
    ) {
        fun serialize(baseUrl: String): List<Pair<String, String>> {
            val transport = StreamingChatService(experimentalApiModeEnabled = { false }, allowCleartextHttp = { true })
            val body = transport.buildRequestBody(request, model(baseUrl), stream = true)
            return Json.parseToJsonElement(body).jsonObject.getValue("messages").jsonArray.map { entry ->
                val message = entry.jsonObject
                message.getValue("role").jsonPrimitive.content to message.getValue("content").jsonPrimitive.content
            }
        }
        val original = serialize("https://127.0.0.1/v1")
        val user = original.indexOf("user" to currentUserText)
        val strong = original.indexOf("system" to strongSuffix)
        assertTrue(user >= 0 && strong > user)
        val cleartext = serialize("http://127.0.0.1/v1")
        assertEquals("user" to currentUserText, cleartext[user])
        assertEquals("assistant" to strongSuffix, cleartext[strong])
        val geminiUser = GeminiChatTemplatePolicy.adaptMessages(request).last { it.role == "user" }
        val quotedTail = geminiUser.content.jsonPrimitive.content.substringAfterLast("$currentUserText\n\n")
        assertTrue(Json.parseToJsonElement(quotedTail).jsonArray.any { entry ->
            val message = entry.jsonObject
            message.getValue("role").jsonPrimitive.content == "system" &&
                message.getValue("content").jsonPrimitive.content == strongSuffix
        })
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
    fun `repair removes only exact dispatched boundary lines from live stopped and finished output`() = runBlocking {
        val original = "The visitor left."
        val revised = "The visitor returned by the garden path and laid the keys on the bench."
        val actualCard = "Keep paragraph breaks."
        val actualBubble = "Use separate bubbles for narration and dialogue."
        val cardBoundary = "{格式卡要求}（禁止覆盖应用格式卡中的占位文本，如对选项的描述）"
        val bubbleBoundary = "{分段气泡格式}"
        val originalBoundary = "{待修复消息}"
        for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
            for (header in listOf(cardBoundary, bubbleBoundary)) {
                assertRepairBoundaryReply(original, null, null, listOf("$header\n$revised"), "$header\n$revised", experimental, toolCall)
            }
            for (raw in listOf(
                "~~~text\n$originalBoundary\n~~~\n$revised",
                "> $originalBoundary\n\n$revised",
                "The sign read:\n$originalBoundary",
                PromptTemplates.narrativeModeHistoryReply(revised, ChatNarrativeMode.FOCUS),
                PromptTemplates.MESSAGE_FORMAT_REPAIR_UNCHANGED_MARKER,
            )) {
                assertRepairBoundaryReply(original, actualCard, actualBubble, listOf(raw), raw, experimental, toolCall)
            }
            assertRepairBoundaryReply(
                "$originalBoundary\n$original", actualCard, actualBubble,
                listOf("$originalBoundary\n$revised"), "$originalBoundary\n$revised", experimental, toolCall,
            )
            for (header in listOf(originalBoundary, cardBoundary, bubbleBoundary)) {
                assertRepairBoundaryReply(original, actualCard, actualBubble, listOf("$header\n$revised"), revised, experimental, toolCall)
            }
        }
        assertRepairBoundaryReply(
            original, actualCard, actualBubble, listOf(originalBoundary, revised), revised,
            experimental = true, tool = true, expectedFirstPreview = "",
        )
        assertRepairBoundaryReply(
            original, actualCard, actualBubble, listOf("$originalBoundary\n$revised", bubbleBoundary), revised,
            experimental = true, tool = true,
        )
        val secondStory = "She rang the bell."
        assertRepairBoundaryReply(
            original, actualCard, actualBubble,
            listOf("$originalBoundary\n$revised", "$bubbleBoundary\n$secondStory"), "$revised\n$secondStory",
            experimental = true, tool = true, expectedFirstPreview = revised,
        )
        val caption = "She read the heading aloud:"
        assertRepairBoundaryReply(
            original, actualCard, actualBubble,
            listOf(caption, "$bubbleBoundary\n$secondStory"), "$caption\n$bubbleBoundary\n$secondStory",
            experimental = true, tool = true, expectedFirstPreview = caption,
        )
    }

    @Test
    fun `an uncompleted issued repair boundary does not cover original text on Preview or Stop`() = runBlocking {
        val original = "She waited for the return of the carriage."
        val revised = "She stayed at the gate until the carriage returned."
        val partial = "{待修复"
        val complete = "{待修复消息}"
        for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
            for (authored in listOf("$partial written on the envelope.", "> $partial")) {
                assertRepairBoundaryReply(original, null, null, listOf(authored), authored, experimental, toolCall)
            }
            assertRepairBoundaryReply(
                "$partial 表示残缺的标题。", null, null, listOf(partial), partial, experimental, toolCall,
            )
            assertRepairBoundaryReply(
                original, null, null, listOf(partial), partial, experimental, toolCall,
                expectedFirstPreview = "", expectedStopped = "",
            )
            assertRepairBoundaryReply(
                original, null, null, listOf("$complete\n$revised"), revised, experimental, toolCall,
                expectedFirstPreview = "", expectedStopped = "", firstWireFragment = partial,
            )
        }
    }

    @Test
    fun `a repair user boundary only authorizes the exact sent system preamble`() = runBlocking {
        val paragraph = PromptTemplates.MESSAGE_FORMAT_REPAIR_SYSTEM_PROMPT.trim().lineSequence().first()
        val appSentence = paragraph.substringBefore('。') + "。"
        val boundary = "{待修复消息}"
        val original = "她在树下等着。"
        val revised = "她在庭院的老树下等着来信。"
        for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
            for (literal in listOf(
                "$appSentence\n$revised",
                "$boundary\n> $appSentence\n\n$revised",
                "$boundary\n~~~text\n$appSentence\n~~~\n$revised",
                "$boundary\n你是负责校对消息的修复器。\n$revised",
            )) {
                val cleaned = literal.removePrefix("$boundary\n")
                assertRepairBoundaryReply(original, null, null, listOf(literal), cleaned, experimental, toolCall)
            }
            for (text in listOf(appSentence, paragraph)) {
                val raw = "$boundary\n$text\n$revised"
                assertRepairBoundaryReply(
                    "$text\n$original", null, null, listOf(raw), "$text\n$revised", experimental, toolCall,
                )
                assertRepairBoundaryReply(original, null, null, listOf(raw), revised, experimental, toolCall)
            }
        }
    }

    @Test
    fun `interior repair echo needs the exact paired boundary and sent system wording`() = runBlocking {
        val original = "A thunderclap woke her."
        val first = "A distant thunderclap woke her.  "
        val second = "She watched the old shutters from her chair."
        val heading = "{待修复消息}"
        val paragraph = PromptTemplates.MESSAGE_FORMAT_REPAIR_SYSTEM_PROMPT.trim().lineSequence().first()
        val firstSentence = paragraph.substringBefore('。') + "。"
        val pair = "$heading\r\n$firstSentence"
        val raw = "$first\r\n$pair\r\n$second"
        for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
            for (literal in listOf(
                "$first\n$heading\n$second",
                "$first\n$heading\nYou are a message format proofreader and repairer.\n$second",
                "$first\n> $heading\n> $firstSentence\n\n$second",
                "$first\n~~~text\n$pair\n~~~\n$second",
                "$first\nThe note showed the following messages:\n$pair\n$second",
                "$first\nBelow is a literal quotation copied from the history log.\n$pair\nThat was a verbatim quotation from the log.\n$second",
            )) {
                assertRepairBoundaryReply(original, null, null, listOf(literal), literal, experimental, toolCall)
            }
            assertRepairBoundaryReply("$original\n$pair", null, null, listOf(raw), raw, experimental, toolCall)
            assertRepairBoundaryReply(original, null, null, listOf(raw), "$first\r\n$second", experimental, toolCall)
            assertRepairBoundaryReply(
                original, null, null, listOf("$first\n$heading\n$paragraph\n$second"),
                "$first\n$second", experimental, toolCall,
            )
        }
    }

    @Test
    fun `an exact original inside revised repair is retained without independent echo evidence`() = runBlocking {
        val heading = "{待修复消息}"
        val first = "The note still hangs beside the door."
        val original = "Do not leave the lamps burning.  \r\n    Please lock the west gate."
        val last = "She closed the shutters before leaving."
        val possibleCitation = "$heading\r\n$original"
        for ((experimental, toolCall) in listOf(false to false, true to true)) {
            for (candidate in listOf(
                "$first\r\n$possibleCitation\r\n$last",
                "$first\nThe note transcribes the earlier wording:\n$possibleCitation\n$last",
                "$first\n<quotation>\n$possibleCitation\n</quotation>\n$last",
                "$first\n$original\n$last",
            )) {
                assertRepairBoundaryReply(original, null, null, listOf(candidate), candidate, experimental, toolCall)
            }
            val withAuthoredMarker = "$heading\n$original"
            val authored = "$first\n$withAuthoredMarker\n$last"
            assertRepairBoundaryReply(withAuthoredMarker, null, null, listOf(authored), authored, experimental, toolCall)
        }
    }

    @Test
    fun `repair removes a split issued preamble while keeping the original authored or altered lines`() = runBlocking {
        val original = "The door was open."
        val first = "The ancient door stood open.  "
        val next = "    She entered the hall."
        val heading = "{待修复消息}"
        val systemFirst = PromptTemplates.MESSAGE_FORMAT_REPAIR_SYSTEM_PROMPT.trim().substringBefore('。') + "。"
        val parts = listOf("$first\n$heading", "$systemFirst\n$next")
        val literal = "$heading\n$systemFirst"
        assertRepairBoundaryReply(
            "$original\n$literal", null, null, parts, parts.joinToString("\n"), experimental = true, tool = true,
            expectedFirstPreview = parts.first(),
        )
        val protected = listOf("$first\n~~~text\n$heading", "$systemFirst\n~~~\n$next")
        assertRepairBoundaryReply(
            original, null, null, protected, protected.joinToString("\n"), experimental = true, tool = true,
            expectedFirstPreview = protected.first(),
        )
        val altered = "你在日志里看到了这句校对说明。"
        assertRepairBoundaryReply(
            original, null, null, listOf(parts.first(), "$altered\n$next"), "$first\n$altered\n$next",
            experimental = true, tool = true, expectedFirstPreview = first,
        )
        assertRepairBoundaryReply(
            original, null, null, parts, "$first\n$next", experimental = true, tool = true,
            expectedFirstPreview = first,
        )
    }

    @Test
    fun `repair recognizes the complete unlabelled system introduction while preserving the ambiguous short sentence`() = runBlocking {
        val original = "The door is closed."
        val revised = "The old door is closed and latched."
        val full = PromptTemplates.MESSAGE_FORMAT_REPAIR_SYSTEM_PROMPT.trim().lineSequence().first()
        val short = full.substringBefore('。') + "。"
        val raw = "$full\n$revised"
        for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
            for (literal in listOf("$short\n$revised", "~~~text\n$full\n~~~\n$revised", "> $full\n\n$revised", "$full\n\n")) {
                assertRepairBoundaryReply(original, null, null, listOf(literal), literal, experimental, toolCall)
            }
            assertRepairBoundaryReply("$full\n$original", null, null, listOf(raw), raw, experimental, toolCall)
            assertRepairBoundaryReply(original, null, null, listOf(raw), revised, experimental, toolCall)
        }
    }

    @Test
    fun `a whole exact sent repair system block does not leak rules after its introduction`() = runBlocking {
        val original = "The door was closed."
        val repaired = "She closed the old door and fastened the latch."
        val system = PromptTemplates.MESSAGE_FORMAT_REPAIR_SYSTEM_PROMPT.trim()
        val raw = "$system\n\n$repaired"
        for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
            for (literal in listOf("~~~text\n$system\n~~~\n$repaired", system.prependIndent("> ") + "\n\n$repaired")) {
                assertRepairBoundaryReply(original, null, null, listOf(literal), literal, experimental, toolCall)
            }
            assertRepairBoundaryReply("$system\n$original", null, null, listOf(raw), raw, experimental, toolCall)
            assertRepairBoundaryReply(original, null, null, listOf(raw), repaired, experimental, toolCall)
        }
    }

    @Test
    fun `an exact repair system reply does not become replacement text`() = runBlocking {
        val original = "The door was closed"
        val card = "End the sentence with a period."
        val system = PromptTemplates.MESSAGE_FORMAT_REPAIR_SYSTEM_PROMPT.trim()
        val preamble = system.lineSequence().first()
        for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
            for (literal in listOf(preamble, "~~~text\n$system\n~~~", system.prependIndent("> "))) {
                assertRepairBoundaryReply(original, card, null, listOf(literal), literal, experimental, toolCall)
            }
            assertRepairBoundaryReply("$original\n\n$system", card, null, listOf(system), system, experimental, toolCall)
            assertRepairBoundaryReply(original, "$card\n\n$system", null, listOf(system), system, experimental, toolCall)
            assertRepairBoundaryReply(original, card, null, listOf(system), "", experimental, toolCall)
        }
    }

    @Test
    fun `held repair Stop keeps changed prose and withholds only the partial terminal boundary`() = runBlocking {
        val original = "门没关严。"
        val revised = "门已关严。"
        val partial = "{待修复"
        val full = "$revised\n{待修复消息}"
        for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
            for (intentional in listOf("$revised\n> $partial", "$revised\n`$partial`", "$revised\n$partial 是字面示例。")) {
                assertRepairBoundaryReply(original, null, null, listOf(intentional), intentional, experimental, toolCall)
            }
            assertRepairBoundaryReply("$original\n$partial 是字面示例。", null, null, listOf("$revised\n$partial"),
                "$revised\n$partial", experimental, toolCall)
            assertRepairBoundaryReply(
                original, null, null, listOf(full), revised, experimental, toolCall,
                expectedFirstPreview = revised, expectedStopped = revised, firstWireFragment = "$revised\n$partial",
            )
        }
    }

    @Test
    fun `the exact sent repair system block is removed when appended to a repaired paragraph`() = runBlocking {
        val original = "The door was closed"
        val repaired = "$original."
        val card = "End the sentence with a period."
        val system = PromptTemplates.MESSAGE_FORMAT_REPAIR_SYSTEM_PROMPT.trim()
        val raw = "$repaired\n\n$system"
        val altered = system.dropLast(1) + "!"
        val fragment = system.substringBefore('。') + "。"
        for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
            for (authored in listOf(
                "~~~text\n$system\n~~~",
                "~~~text\n$system",
                system.prependIndent("> "),
                "The following is a literal quotation from the history log.\n\n$system",
                altered,
                fragment,
            )) {
                val content = "$repaired\n\n$authored"
                assertRepairBoundaryReply(original, card, null, listOf(content), content, experimental, toolCall)
            }
            assertRepairBoundaryReply("$original\n\n$system", card, null, listOf(raw), raw, experimental, toolCall)
            assertRepairBoundaryReply(original, "$card\n\n$system", null, listOf(raw), raw, experimental, toolCall)
            val noParagraph = "$repaired\n$system"
            assertRepairBoundaryReply(original, card, null, listOf(noParagraph), noParagraph, experimental, toolCall)
            assertRepairBoundaryReply(original, card, null, listOf(raw), repaired, experimental, toolCall)
        }
        assertRepairBoundaryReply(
            original, card, null, listOf("$repaired\n", system), repaired, experimental = true, tool = true,
            expectedFirstPreview = "$repaired\n",
        )
    }

    @Test
    fun `whole issued repair system text between ordinary paragraphs stays out of the replacement`() = runBlocking {
        val original = "The door was open. The lamp was out."
        val card = "Keep both revised paragraphs."
        val first = "The door was closed.  "
        val second = "The lamp was lit."
        val block = MessageFormatRepairService.PreparedRequest(original, card, null).systemPrompt
        val raw = "$first\r\n\r\n$block\n\n$second"
        val preamble = block.lineSequence().first()
        val partial = block.substringBeforeLast('\n')
        val changed = block.replace("7. ", "8. ")
        val quoted = block.prependIndent("> ")
        for (experimental in listOf(false, true)) {
            val controls = listOf(
                "$first\n$block\n$second",
                "$first\n\n$preamble\n\n$second",
                "$first\n\n$partial\n\n$second",
                "$first\n\n$changed\n\n$second",
                "$first\n\n~~~text\n$block\n~~~\n\n$second",
                "$first\n\n$quoted\n\n$second",
                "$first\n\nThe following is a literal quotation from the history log.\n\n$block\n\n$second",
                "$first\n\n$block\n\nThat was a verbatim quotation from the log.",
            )
            for (literal in controls) assertRepairBoundaryReply(original, card, null, listOf(literal), literal, experimental, tool = false)
            assertRepairBoundaryReply("$original\n\n$block", card, null, listOf(raw), raw, experimental, tool = false)
            assertRepairBoundaryReply(original, "$card\n\n$block", null, listOf(raw), raw, experimental, tool = false)
            assertRepairBoundaryReply(original, card, null, listOf(raw), "$first\r\n\r\n$second", experimental, tool = false)
        }
    }

    @Test
    fun `whole issued repair system text isolated in a validated middle tool call stays out of the replacement`() = runBlocking {
        val original = "The door was open. The lamp was out."
        val card = "Keep both revised paragraphs."
        val first = "The door was closed."
        val second = "The lamp was lit."
        val block = MessageFormatRepairService.PreparedRequest(original, card, null).systemPrompt
        val parts = listOf(first, block, second)
        val raw = parts.joinToString("\n")
        val controls = listOf(
            listOf(first, block.lineSequence().first(), second),
            listOf(first, block.replace("7. ", "8. "), second),
            listOf("$first\n~~~text", block, "~~~\n$second"),
            listOf(first, block.prependIndent("> "), second),
            listOf("Below is a literal quotation copied from the history log.", block, "That was a verbatim quotation from the log."),
        )
        for (literal in controls) assertRepairBoundaryReply(
            original, card, null, literal, literal.joinToString("\n"), experimental = true, tool = true,
            expectedFirstPreview = literal.first(),
        )
        assertRepairBoundaryReply(
            "$original\n\n$block", card, null, parts, raw, experimental = true, tool = true, expectedFirstPreview = first,
        )
        assertRepairBoundaryReply(
            original, card, null, parts, "$first\n$second", experimental = true, tool = true, expectedFirstPreview = first,
        )
    }

    @Test
    fun `terminal repair log quotes retain the actual label and system sentence`() = runBlocking {
        val original = "The key stayed in the door."
        val heading = "{待修复消息}"
        val sentence = PromptTemplates.MESSAGE_FORMAT_REPAIR_SYSTEM_PROMPT.trim().substringBefore('。') + "。"
        val pair = "$heading\n$sentence"
        val caption = "The following is a literal quotation from the history log."
        val quote = "$caption\n$pair"
        for ((experimental, toolCall) in listOf(false to false, true to false, true to true)) {
            val revised = "She removed the key and went outside."
            assertRepairBoundaryReply(original, null, null, listOf("$revised\n$pair"), revised, experimental, toolCall)
            assertRepairBoundaryReply("$original\n$pair", null, null, listOf(quote), quote, experimental, toolCall)
            assertRepairBoundaryReply(original, null, null, listOf("$quote\n\n$revised"), "$quote\n\n$revised", experimental, toolCall)
            assertRepairBoundaryReply(original, null, null, listOf(quote), quote, experimental, toolCall)
        }
        assertRepairBoundaryReply(
            original, null, null, listOf(caption, pair), quote, experimental = true, tool = true,
            expectedFirstPreview = caption,
        )
    }

    @Test
    fun `whole issued repair suffix accepts physical CRLF without changing the preceding text`() = runBlocking {
        val original = "The lamp was out."
        val card = "Preserve the prose and its spacing."
        val repaired = "The lamp flickered.  \r\nShe reached for the key."
        val issued = MessageFormatRepairService.PreparedRequest(original, card, null).systemPrompt.replace("\n", "\r\n")
        val echoed = "$repaired\r\n\r\n$issued"
        for (experimental in listOf(false, true)) {
            assertRepairBoundaryReply(original, card, null, listOf(echoed), repaired, experimental, tool = false)
            assertRepairBoundaryReply("$original\n\n$issued", card, null, listOf(echoed), echoed, experimental, tool = false)
            val literal = "$repaired\r\n\r\n~~~text\r\n$issued\r\n~~~"
            assertRepairBoundaryReply(original, card, null, listOf(literal), literal, experimental, tool = false)
        }
    }

    @Test
    fun `whole issued repair between ordinary paragraphs accepts mixed physical line endings`() = runBlocking {
        val original = "The door was closed. The lamp was out."
        val card = "Preserve independent paragraphs."
        val first = "The door was open.  \nShe stepped inside."
        val second = "The room was silent.\r\nA light moved."
        val issued = MessageFormatRepairService.PreparedRequest(original, card, null).systemPrompt.replaceFirst("\n", "\r\n")
        val echoed = "$first\r\n\n$issued\n\r\n$second"
        val expected = "$first\r\n\n$second"
        val literal = "$first\r\n\nThe following is a literal quotation from the history log.\n\n$issued\r\n\r\n$second"
        assertRepairBoundaryReply(original, card, null, listOf(echoed), expected, experimental = false, tool = false)
        assertRepairBoundaryReply(original, card, null, listOf(literal), literal, experimental = false, tool = false)
    }

    @Test
    fun `whole issued CRLF repair stays out of the validated middle tool call only`() = runBlocking {
        val original = "The lamp was out. The wind blew."
        val card = "Return just the revised paragraphs."
        val first = "A candle lit.\r\nThe flame swayed."
        val second = "The window was open.\nA curtain stirred."
        val issued = MessageFormatRepairService.PreparedRequest(original, card, null).systemPrompt.replace("\n", "\r\n")
        assertRepairBoundaryReply(original, card, null, listOf(first, issued, second), "$first\n$second",
            experimental = true, tool = true, expectedFirstPreview = first)
        val quoted = issued.lineSequence().joinToString("\r\n") { "> $it" }
        val literal = listOf(first, quoted, second)
        assertRepairBoundaryReply(original, card, null, literal, literal.joinToString("\n"),
            experimental = true, tool = true, expectedFirstPreview = first)
    }

    private suspend fun assertRepairBoundaryReply(
        original: String,
        card: String?,
        bubble: String?,
        parts: List<String>,
        expected: String,
        experimental: Boolean,
        tool: Boolean,
        expectedFirstPreview: String = expected,
        expectedStopped: String = expected,
        firstWireFragment: String? = null,
    ) {
        val frame = when {
            parts.size > 1 -> repairToolFrame(parts.mapIndexed { index, part ->
                index to buildJsonObject { put("content", part) }.toString()
            })
            tool -> chunk(buildJsonObject { put("content", parts.single()) }.toString(), "game_content")
            else -> ordinaryDelta(parts.single())
        }
        val terminal = sse(listOf(chunk(reason = if (tool) "tool_calls" else "stop"), "[DONE]"))
        val originalRequest = MessageFormatRepairService.PreparedRequest(original, card, bubble)
        val formatRepair = MessageFormatRepairService(
            StreamingChatService(experimentalApiModeEnabled = { experimental }, allowCleartextHttp = { true }),
        )
        val cleanup = originalRequest.historyCleanup()
        val raw = parts.joinToString("\n")
        val expectedWire = PromptTemplates.messageFormatRepairUserPrompt(card, bubble, original)
        val lengths = parts.map(String::length).takeIf { it.size > 1 }.orEmpty()
        val frames = if (firstWireFragment == null) listOf(frame) else {
            val remainder = parts.single().removePrefix(firstWireFragment)
            if (tool) {
                val prefix = JsonPrimitive(firstWireFragment).toString().removeSurrounding("\"")
                val suffix = JsonPrimitive(remainder).toString().removeSurrounding("\"")
                listOf(chunk("{\"content\":\"$prefix", "game_content"), chunk("$suffix\"}"))
            } else listOf(ordinaryDelta(firstWireFragment), ordinaryDelta(remainder))
        }
        GatedExperimentalResponse(sse(frames), terminal).use { server ->
            var reader: (() -> List<String>)? = null
            val event = withTimeout(5_000) {
                formatRepair.streamRepair(originalRequest, model(server.baseUrl)) { reader = it }
                    .first { it is StreamEvent.Delta || it is StreamEvent.ContentPreview }
            }
            val live = if (event is StreamEvent.Delta) event.text else (event as StreamEvent.ContentPreview).text
            assertEquals(firstWireFragment ?: parts.first(), live)
            val payload = Json.parseToJsonElement(server.requestBody).jsonObject
            val system = payload.getValue("messages").jsonArray.first().jsonObject
            assertEquals("system", system.getValue("role").jsonPrimitive.content)
            assertTrue(PromptTemplates.MESSAGE_FORMAT_REPAIR_SYSTEM_PROMPT.trim() in system.getValue("content").jsonPrimitive.content)
            val wireMessages = if (experimental) {
                val context = payload.getValue("tools").jsonArray.map { it.jsonObject.getValue("function").jsonObject }
                    .single { it.getValue("name").jsonPrimitive.content == "context" }.getValue("description").jsonPrimitive.content
                Json.parseToJsonElement(context + "\"}]").jsonArray
            } else payload.getValue("messages").jsonArray
            assertTrue(wireMessages.any {
                val message = it.jsonObject
                message.getValue("role").jsonPrimitive.content == "user" &&
                    message.getValue("content").jsonPrimitive.content == expectedWire
            })
            assertFalse(server.tailSent)
            val stopped = reader?.invoke()?.takeIf(List<String>::isNotEmpty)?.joinToString("\n") ?: live
            assertEquals(firstWireFragment ?: raw, stopped)
            val stoppedBody = cleanup.completedBody(stopped, interrupted = true, partLengths = lengths)
            val liveBody = cleanup.streamingPreview().appendedBody(live, 0)
            assertEquals(expectedStopped, stoppedBody)
            if (parts.size > 2) assertEquals(
                expectedStopped, cleanup.streamingPreview().appendedBody(stopped, 0, lengths),
            )
            assertEquals(expectedFirstPreview, liveBody)
            assertEquals(
                MessageFormatRepairPolicy.progressiveOverlay(original, expectedStopped),
                MessageFormatRepairPolicy.progressiveOverlay(original, stoppedBody),
            )
            assertEquals(
                MessageFormatRepairPolicy.progressiveOverlay(original, expectedFirstPreview),
                MessageFormatRepairPolicy.progressiveOverlay(original, liveBody),
            )
        }
        GatedExperimentalResponse(sse(frames), terminal).use { server ->
            val events = withTimeout(5_000) {
                formatRepair.streamRepair(originalRequest, model(server.baseUrl))
                    .onEach { if (it is StreamEvent.Delta || it is StreamEvent.ContentPreview) server.releaseTail() }.toList()
            }
            val deltas = events.filterIsInstance<StreamEvent.Delta>()
            if (firstWireFragment != null) {
                val previews = events.filterIsInstance<StreamEvent.ContentPreview>().map { it.text }.ifEmpty {
                    deltas.runningFold("", { prefix, delta -> prefix + delta.text }).drop(1)
                }
                val incremental = cleanup.streamingPreview()
                var previousLength = 0
                val cleaned = previews.map { preview ->
                    incremental.appendedBody(preview, previousLength).also { previousLength = preview.length }
                }
                assertEquals(listOf(expectedFirstPreview, expected), cleaned)
            }
            assertEquals(raw, deltas.joinToString("") { it.text })
            assertEquals(lengths, deltas.last().toolReplyPartLengths)
            assertEquals(StreamEvent.Done, events.last())
            assertEquals(expected, cleanup.completedBody(raw, partLengths = lengths))
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

    private fun repairToolFrame(first: String, followingIndex: Int, followingArguments: String): String = repairToolFrame(listOf(
        0 to buildJsonObject { put("content", first) }.toString(),
        followingIndex to followingArguments,
    ))

    private fun repairToolFrame(calls: List<Pair<Int, String>>): String {
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
                            for ((index, arguments) in calls) add(call(index, arguments))
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
