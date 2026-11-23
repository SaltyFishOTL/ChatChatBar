package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ChatMessage
import com.example.chatbar.data.local.entity.ChatNarrativeMode
import com.example.chatbar.data.local.entity.MessageRole
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.prompt.PromptTemplates
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatHistoryPromptPolicyTest {
    @Test
    fun standaloneLegacyFocusHistoryIsProjectedOnlyOnce() {
        val open = "<聚焦模式=\"沉浸\">"
        val story = "她把书合上。"
        val bare = "$open\n$story\n</聚焦模式>"
        for (closing in listOf("</聚焦模式>", "</聚焦模式=\"沉浸\">")) {
            for (lineBreak in listOf("\n", "\r\n", "\r")) {
                val legacy = listOf(open, story, closing).joinToString(lineBreak)
                val assistant = message(MessageRole.ASSISTANT, legacy)
                val text = ChatHistoryPromptPolicy.renderedHistoryText(assistant, legacy)
                assertEquals(JsonPrimitive(legacy), ChatApiMessage.text("assistant", text).content)
                assertEquals(legacy, assistant.displayContent)
            }
        }

        val differentMode = message(MessageRole.ASSISTANT, bare).copy(narrativeMode = ChatNarrativeMode.ADVANCE)
        assertEquals(PromptTemplates.narrativeModeHistoryReply(bare, ChatNarrativeMode.ADVANCE),
            ChatHistoryPromptPolicy.renderedHistoryText(differentMode, bare))
        assertEquals(bare, ChatHistoryPromptPolicy.renderedHistoryText(message(MessageRole.USER, bare), bare))
        assertEquals(PromptTemplates.narrativeModeHistoryReply(bare, ChatNarrativeMode.FOCUS),
            ChatHistoryPromptPolicy.renderedHistoryText(message(MessageRole.ASSISTANT, "未包裹原文"), bare))

        val insufficient = listOf(
            "$open$story</聚焦模式>",
            "$open\n$story\n</聚焦模式>\n这里只是示例。",
            "```xml\n$bare\n```",
            PromptTemplates.narrativeModeHistoryReply(bare, ChatNarrativeMode.FOCUS),
        )
        for (raw in insufficient) {
            assertEquals(PromptTemplates.narrativeModeHistoryReply(raw, ChatNarrativeMode.FOCUS),
                ChatHistoryPromptPolicy.renderedHistoryText(message(MessageRole.ASSISTANT, raw), raw))
        }
    }

    @Test
    fun declaredLegacyFormatRemainsLiteralInsideTheRequestWrapper() {
        val body = "<聚焦模式=\"沉浸\">\n她把书合上。\n</聚焦模式>"
        val assistant = message(MessageRole.ASSISTANT, body)
        val cleanup = NarrativeHistoryEcho.forRequest(ChatNarrativeMode.FOCUS, "输出格式：<!-- 注记 -->$body")
        assertEquals(body, cleanup.completedBody(body))
        assertEquals(PromptTemplates.narrativeModeHistoryReply(body, ChatNarrativeMode.FOCUS),
            ChatHistoryPromptPolicy.renderedHistoryText(
                assistant, body, legacyHistoryWrapperDeclaredAsOutput = cleanup.declaresLegacyHistoryWrapper,
            ))
    }

    @Test
    fun statusExclusionAppliesOnlyToEarlierAssistantHistory() {
        val formattedContent = """
            正文
            ```status
            状态栏
            ```
            ------
            [选项]()
            ------
        """.trimIndent()
        val assistant = message(MessageRole.ASSISTANT, formattedContent)

        assertEquals(
            "正文",
            ChatHistoryPromptPolicy.sourceText(
                message = assistant,
                excludeAssistantStatusFromHistory = true,
                zone = ChatHistoryPromptZone.EARLIER_HISTORY
            )
        )
        assertEquals(
            formattedContent,
            ChatHistoryPromptPolicy.sourceText(
                message = assistant,
                excludeAssistantStatusFromHistory = true,
                zone = ChatHistoryPromptZone.PREVIOUS_TURN
            )
        )
        assertEquals(
            formattedContent,
            ChatHistoryPromptPolicy.sourceText(
                message = assistant,
                excludeAssistantStatusFromHistory = false,
                zone = ChatHistoryPromptZone.EARLIER_HISTORY
            )
        )
    }

    @Test
    fun legacyStatusExclusionKeepsAdjacentTypedCodeInTheRequest() {
        val label = "（三个点为状态包裹格式，必须完整输出）"
        val status = "```text$label\n生命：10\n$label```"
        val code = "```kotlin\nval sample = \"<n=\\\"Mina\\\"/> [literal]\"  \n```"
        val full = "开场。\n\n$status\n\n$code\n\n落幕。"
        val expected = "开场。\n\n$code\n\n落幕。"
        val assistant = message(MessageRole.ASSISTANT, full)

        val earlier = ChatHistoryPromptPolicy.sourceText(assistant, true, ChatHistoryPromptZone.EARLIER_HISTORY)
        val earlierRequest = ChatHistoryPromptPolicy.renderedHistoryText(assistant, earlier)
        assertEquals(JsonPrimitive(PromptTemplates.narrativeModeHistoryReply(expected, ChatNarrativeMode.FOCUS)),
            ChatApiMessage.text("assistant", earlierRequest).content)
        assertEquals(full, ChatHistoryPromptPolicy.sourceText(assistant, true, ChatHistoryPromptZone.PREVIOUS_TURN))
    }

    @Test
    fun historyStatusExclusionKeepsLongBacktickCodeBytes() {
        val bare = "````\n<!-- literal -->\n```text\n代码中的样例  \n```\n````"
        val typed = "`````text\n<n=\"Mina\"/>[literal]  \n`````"
        val status = "```\n真实状态\n```"
        val full = "$bare\n\n$status\n\n$typed"
        val assistant = message(MessageRole.ASSISTANT, full)
        val earlier = ChatHistoryPromptPolicy.sourceText(assistant, true, ChatHistoryPromptZone.EARLIER_HISTORY)
        val request = ChatHistoryPromptPolicy.renderedHistoryText(assistant, earlier)
        val expected = full.replace(status, "")
        assertEquals(JsonPrimitive(PromptTemplates.narrativeModeHistoryReply(expected, ChatNarrativeMode.FOCUS)),
            ChatApiMessage.text("assistant", request).content)
    }

    @Test
    fun nestedShortMarkdownFencesRemainInEarlierHistoryWhenStatusIsExcluded() {
        val opening = "<历史回复 模式=\"推进\">"
        val closing = "</历史回复>"
        val quotedCode = "> > ```text\n> > $closing\n> > ```"
        val listCode = "- ```\n  [literal]  \n  ```"
        val metadataCode = "> ```text example\n> $closing\n> ```"
        val label = "（三个点为状态包裹格式，必须完整输出）"
        val annotatedCode = "- ```text$label\n  $closing\n  $label```"
        val story = "她读出教程。\n\n$quotedCode\n\n$listCode\n\n$metadataCode\n\n$annotatedCode\n\n她关上电脑。"
        val status = "```text$label\n生命：10\n$label```"
        val wrapped = "$opening\n$story\n\n$status\n$closing"
        val cleaned = NarrativeHistoryEcho.forRequest(ChatNarrativeMode.ADVANCE, null).completedBody(wrapped)
        val assistant = message(MessageRole.ASSISTANT, cleaned).copy(narrativeMode = ChatNarrativeMode.ADVANCE)

        assertEquals("$story\n\n$status", cleaned)
        val earlier = ChatHistoryPromptPolicy.sourceText(assistant, true, ChatHistoryPromptZone.EARLIER_HISTORY)
        assertEquals(JsonPrimitive("$opening\n$story\n$closing"),
            ChatApiMessage.text("assistant", ChatHistoryPromptPolicy.renderedHistoryText(assistant, earlier)).content)
        assertEquals(cleaned, ChatHistoryPromptPolicy.sourceText(assistant, true, ChatHistoryPromptZone.PREVIOUS_TURN))
    }

    @Test
    fun cdataRemainsLiteralInReadAndSerializedHistory() {
        val mode = ChatNarrativeMode.FOCUS
        val xml = "<example><![CDATA[\n</历史回复>\n<!-- literal -->\n```text\n[example]\n```\n]]></example>"
        val status = "```text\n真实状态\n```"
        val reply = PromptTemplates.narrativeModeHistoryReply("$xml\n\n$status", mode)
        val cleaned = NarrativeHistoryEcho.forRequest(mode, null).completedBody(reply)
        val assistant = message(MessageRole.ASSISTANT, cleaned).copy(narrativeMode = mode)

        assertEquals("$xml\n\n$status", cleaned)
        val segments = parseRoleplayTextSegments(assistant.displayContent)
        assertEquals(listOf(RoleplaySegmentKind.NARRATION, RoleplaySegmentKind.STATUS), segments.map { it.kind })
        assertEquals(xml, segments.first().rawText.trimEnd())
        val earlier = ChatHistoryPromptPolicy.sourceText(assistant, true, ChatHistoryPromptZone.EARLIER_HISTORY)
        assertEquals(JsonPrimitive(PromptTemplates.narrativeModeHistoryReply(xml, mode)),
            ChatApiMessage.text("assistant", ChatHistoryPromptPolicy.renderedHistoryText(assistant, earlier)).content)
    }

    @Test
    fun replyWithAnnotatedStatusReplaysWithOneHistoryWrapper() {
        val label = "（三个点为状态包裹格式，必须完整输出）"
        val before = "```text$label\n当前地点：门厅\n$label```"
        val after = "```text$label\n穿搭：大衣\n$label```"
        val opening = "<历史回复 模式=\"推进\">"
        val closing = "</历史回复>"
        val story = "她推开门。"
        val reply = "$before\n\n$opening\n$story\n$closing\n\n$after"
        val cleaned = NarrativeHistoryEcho.forRequest(ChatNarrativeMode.ADVANCE, null).completedBody(reply)
        val assistant = message(MessageRole.ASSISTANT, cleaned).copy(narrativeMode = ChatNarrativeMode.ADVANCE)

        assertEquals("$before\n\n$story\n\n$after", cleaned)
        assertEquals(listOf(RoleplaySegmentKind.STATUS, RoleplaySegmentKind.NARRATION, RoleplaySegmentKind.STATUS),
            parseRoleplayTextSegments(cleaned).map { it.kind })
        val previous = ChatHistoryPromptPolicy.sourceText(assistant, true, ChatHistoryPromptZone.PREVIOUS_TURN)
        assertEquals(JsonPrimitive("$opening\n$before\n\n$story\n\n$after\n$closing"),
            ChatApiMessage.text("assistant", ChatHistoryPromptPolicy.renderedHistoryText(assistant, previous)).content)
    }

    @Test
    fun pairedPlainActionOptionsStayVisibleAndReplayWithOneHistoryWrapper() {
        val mode = ChatNarrativeMode.ADVANCE
        val story = "门开了。"
        val options = "---\n1. 进门\n2. 等候\n---"
        val body = "$story\n\n$options"
        val reply = PromptTemplates.narrativeModeHistoryReply(story, mode) + "\n\n$options"
        val cleanup = NarrativeHistoryEcho.forRequest(mode, null)
        val rawDraft = message(MessageRole.ASSISTANT, reply).copy(narrativeMode = mode)
        val stop = requireNotNull(InterruptedReplyPolicy.persistableDraft(
            rawDraft.copy(content = cleanup.completedBody(reply, interrupted = true)),
        ))
        val done = rawDraft.copy(content = ChatHistoryPromptPolicy.requirePersistableAssistantBody(
            cleanup.completedBody(reply),
        ))
        for (assistant in listOf(stop, done)) {
            assertEquals(body, assistant.displayContent)
            val segments = parseRoleplayTextSegments(assistant.displayContent)
            assertEquals(listOf(RoleplaySegmentKind.NARRATION, RoleplaySegmentKind.STATUS), segments.map { it.kind })
            assertEquals("1. 进门\n2. 等候", segments.last().displayText)
            assertTrue(segments.last().statusDefaultExpanded)

            val previous = ChatHistoryPromptPolicy.sourceText(assistant, true, ChatHistoryPromptZone.PREVIOUS_TURN)
            val earlier = ChatHistoryPromptPolicy.sourceText(assistant, true, ChatHistoryPromptZone.EARLIER_HISTORY)
            assertEquals(JsonPrimitive(PromptTemplates.narrativeModeHistoryReply(body, mode)),
                ChatApiMessage.text("assistant", ChatHistoryPromptPolicy.renderedHistoryText(assistant, previous)).content)
            assertEquals(JsonPrimitive(PromptTemplates.narrativeModeHistoryReply(story, mode)),
                ChatApiMessage.text("assistant", ChatHistoryPromptPolicy.renderedHistoryText(assistant, earlier)).content)
        }
    }

    @Test
    fun affirmedMultilineTemplateAndLiteralCodeSurviveIntoSerializedHistory() {
        val mode = ChatNarrativeMode.ADVANCE
        val story = "她把书合上。"
        val authored = PromptTemplates.narrativeModeHistoryReply(story, ChatNarrativeMode.FOCUS)
        val format = "本轮输出模板：\n<!-- 固定模板 -->\n$authored（标签是固定输出的一部分）"
        val code = "    </历史回复>  "
        val cases = listOf(
            Triple(format, authored, authored),
            Triple("以下是禁止输出的反例。\n$format", authored, story),
            Triple(null, PromptTemplates.narrativeModeHistoryReply(code, mode), code),
        )
        val service = StreamingChatService()
        val model = ModelConfig(
            id = "fixture", displayName = "fixture", modelName = "fixture",
            baseUrl = "https://example.com/v1", apiKey = "", createdAt = 0L,
        )
        for ((card, reply, expected) in cases) {
            val cleanup = NarrativeHistoryEcho.forRequest(mode, card)
            val draft = message(MessageRole.ASSISTANT, reply).copy(narrativeMode = mode)
            assertEquals(expected, cleanup.completedBody(reply, streaming = true))
            val stopped = requireNotNull(InterruptedReplyPolicy.persistableDraft(
                draft.copy(content = cleanup.completedBody(reply, interrupted = true)),
            ))
            val completed = draft.copy(content = ChatHistoryPromptPolicy.requirePersistableAssistantBody(
                cleanup.completedBody(reply),
            ))
            for (assistant in listOf(stopped, completed)) {
                assertEquals(expected, assistant.displayContent)
                for (zone in ChatHistoryPromptZone.entries) {
                    val source = ChatHistoryPromptPolicy.sourceText(assistant, true, zone)
                    val text = ChatHistoryPromptPolicy.renderedHistoryText(assistant, source)
                    val apiMessages = listOf(ChatApiMessage.text("assistant", text))
                    val body = service.buildRequestBody(apiMessages, model, stream = true)
                    val messages = Json.parseToJsonElement(body).jsonObject.getValue("messages").jsonArray
                    val serializedAssistant = messages.single().jsonObject
                    assertEquals(JsonPrimitive("assistant"), serializedAssistant.getValue("role"))
                    assertEquals(JsonPrimitive(PromptTemplates.narrativeModeHistoryReply(expected, mode)),
                        serializedAssistant.getValue("content"))
                }
            }
        }
    }

    @Test
    fun statusOnlyPreviousAssistantRemainsAvailableWhileEarlierHistoryCanBeOmitted() {
        val statusOnly = """
            ```status
            状态栏
            ```
        """.trimIndent()
        val assistant = message(MessageRole.ASSISTANT, statusOnly)

        assertEquals(
            "",
            ChatHistoryPromptPolicy.sourceText(
                message = assistant,
                excludeAssistantStatusFromHistory = true,
                zone = ChatHistoryPromptZone.EARLIER_HISTORY
            )
        )
        assertEquals(
            statusOnly,
            ChatHistoryPromptPolicy.sourceText(
                message = assistant,
                excludeAssistantStatusFromHistory = true,
                zone = ChatHistoryPromptZone.PREVIOUS_TURN
            )
        )
    }

    @Test
    fun formatContinuityNoticeRequiresExclusionFormatCardAndEarlierAssistant() {
        val assistantHistory = listOf(message(MessageRole.ASSISTANT, "回复"))
        val userHistory = listOf(message(MessageRole.USER, "输入"))

        assertTrue(
            ChatHistoryPromptPolicy.shouldIncludeFormatContinuityNotice(
                excludeAssistantStatusFromHistory = true,
                formatCardContent = "格式卡",
                earlierHistoryMessages = assistantHistory
            )
        )
        assertFalse(
            ChatHistoryPromptPolicy.shouldIncludeFormatContinuityNotice(
                excludeAssistantStatusFromHistory = false,
                formatCardContent = "格式卡",
                earlierHistoryMessages = assistantHistory
            )
        )
        assertFalse(
            ChatHistoryPromptPolicy.shouldIncludeFormatContinuityNotice(
                excludeAssistantStatusFromHistory = true,
                formatCardContent = null,
                earlierHistoryMessages = assistantHistory
            )
        )
        assertFalse(
            ChatHistoryPromptPolicy.shouldIncludeFormatContinuityNotice(
                excludeAssistantStatusFromHistory = true,
                formatCardContent = "格式卡",
                earlierHistoryMessages = userHistory
            )
        )
    }

    @Test
    fun blankAssistantBodyIsOmitted() {
        assertNull(
            ChatHistoryPromptPolicy.payloadText(
                renderedBody = "",
                hasSupportedImage = false
            )
        )
    }

    @Test
    fun statusOnlyAssistantBodyDoesNotProduceTimelineOnlyPayloadAfterExclusion() {
        val renderedBody = stripRoleplayStatusSegments(
            """
            ```status
            状态栏
            ```
            """.trimIndent()
        )

        assertNull(
            ChatHistoryPromptPolicy.payloadText(
                renderedBody = renderedBody,
                hasSupportedImage = false
            )
        )
    }

    @Test
    fun repeatedBlankAssistantImageRecordsAreAllOmitted() {
        val payloads = List(5) {
            ChatHistoryPromptPolicy.payloadText(
                renderedBody = "",
                hasSupportedImage = false
            )
        }

        assertEquals(emptyList<String>(), payloads.filterNotNull())
    }

    @Test
    fun nonBlankBodyContainsNoTimelinePrefix() {
        assertEquals(
            "正文",
            ChatHistoryPromptPolicy.payloadText(
                renderedBody = "正文",
                hasSupportedImage = false
            )
        )
    }

    @Test
    fun blankUserBodyIsAllowedOnlyWhenSupportedImageExists() {
        assertEquals(
            "",
            ChatHistoryPromptPolicy.payloadText(
                renderedBody = "",
                hasSupportedImage = true
            )
        )
    }

    @Test
    fun blankCompletedAssistantResponseCannotBePersisted() {
        assertThrows(IllegalStateException::class.java) {
            ChatHistoryPromptPolicy.requirePersistableAssistantBody("   ")
        }
    }

    private fun message(role: MessageRole, content: String): ChatMessage =
        ChatMessage(
            id = "$role-$content",
            sessionId = "session",
            role = role,
            content = content,
            createdAt = 1,
            updatedAt = 1
        )
}
