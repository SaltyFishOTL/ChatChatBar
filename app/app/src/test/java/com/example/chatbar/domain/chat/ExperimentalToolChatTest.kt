package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.ParamValue
import com.example.chatbar.domain.prompt.PromptTemplates
import com.example.chatbar.domain.prompt.AiTaskContext
import com.example.chatbar.domain.prompt.AiTaskKind
import com.example.chatbar.domain.prompt.AiTaskMessageAssembler
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
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
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ExperimentalToolChatTest {
    @Test
    fun `auxiliary ordinary tail is assistant and experimental context leaves its content open`() {
        val logical = AiTaskMessageAssembler.assemble(
            listOf(ChatApiMessage.text("system", "fixture"), ChatApiMessage.text("user", "actual")),
            AiTaskContext(AiTaskKind.CHARACTER_FILL)
        )
        assertEquals("assistant", logical.last().role)
        assertEquals(JsonPrimitive(PromptTemplates.GENERAL_POST_USER_ACK_ASSISTANT_PROMPT), logical.last().content)
        val service = StreamingChatService()
        val ordinary = Json.parseToJsonElement(service.buildRequestBody(logical, model(), true)).jsonObject
        assertEquals("assistant", ordinary.getValue("messages").jsonArray.last().jsonObject.getValue("role").jsonPrimitive.content)
        val experimental = Json.parseToJsonElement(service.buildRequestBody(
            logical, model(), true, experimentalApiMode = true
        )).jsonObject
        val description = tool(experimental, "context").getValue("description").jsonPrimitive.content
        assertTrue(description.endsWith(JsonPrimitive(PromptTemplates.GENERAL_POST_USER_ACK_ASSISTANT_PROMPT).toString().dropLast(1)))
        val restored = Json.parseToJsonElement(description + "\"}]").jsonArray
        assertEquals(logical.drop(1).map { it.content }, restored.map { it.jsonObject.getValue("content") })
    }

    private val messages = listOf(
        ChatApiMessage.text("system", "核心原文\n保持原样"),
        ChatApiMessage.text("assistant", "确认原文"),
        ChatApiMessage.text("system", "资料原文"),
        ChatApiMessage.text("user", "本轮原文\n包含引号\"与反斜线\\"),
        ChatApiMessage.text("assistant", "开始原文：")
    )

    @Test
    fun `experimental request retains every logical message without rewriting text`() {
        val body = request(experimental = true)
        val sent = body.getValue("messages").jsonArray
        assertEquals(listOf("system", "user"), sent.map { it.jsonObject.getValue("role").jsonPrimitive.content })
        assertEquals(messages.first().content, sent.first().jsonObject["content"])
        assertEquals(PromptTemplates.EXPERIMENTAL_TOOL_USER_TAIL, sent.last().jsonObject.getValue("content").jsonPrimitive.content)
        val description = tool(body, "context").getValue("description").jsonPrimitive.content
        assertTrue(description.endsWith(JsonPrimitive(messages.last().content.jsonPrimitive.content).toString().dropLast(1)))
        assertTrue(runCatching { Json.parseToJsonElement(description) }.isFailure)
        val context = Json.parseToJsonElement(description + "\"}]").jsonArray
        assertEquals(messages.drop(1).map { it.role }, context.map { it.jsonObject.getValue("role").jsonPrimitive.content })
        assertEquals(messages.drop(1).map { it.content }, context.map { it.jsonObject.getValue("content") })
        assertEquals(listOf("content"), tool(body, "game_content").getValue("parameters").jsonObject
            .getValue("required").jsonArray.map { it.jsonPrimitive.content })
        assertFalse(body.containsKey("tool_choice"))
        assertEquals(JsonPrimitive(0.7), body["temperature"])
        assertEquals(JsonPrimitive(4096), body["max_tokens"])
    }

    @Test
    fun `default request keeps ordinary message roles and omits tools`() {
        val body = request(experimental = false)
        assertEquals(messages.map { it.role }, body.getValue("messages").jsonArray
            .map { it.jsonObject.getValue("role").jsonPrimitive.content })
        assertFalse(body.containsKey("tools"))
        assertEquals(JsonPrimitive("stable-cache"), body["prompt_cache_key"])
        assertNotEquals(body["prompt_cache_key"], request(experimental = true)["prompt_cache_key"])
        assertEquals(request(experimental = true)["prompt_cache_key"], request(experimental = true)["prompt_cache_key"])
    }

    @Test
    fun `media remains native and context retains references in original positions`() {
        val first = ChatApiMessage.withImages("user", "图片输入原文", listOf("first-image", "second-image"))
        val second = ChatApiMessage.withImage("user", "另一条输入", "third-image")
        val input = messages.take(1) + first + second
        val body = Json.parseToJsonElement(StreamingChatService(experimentalApiModeEnabled = { true })
            .buildRequestBody(input, model(), true)).jsonObject
        val tail = body.getValue("messages").jsonArray.last().jsonObject.getValue("content").jsonArray
        val originalMedia = (first.content.jsonArray + second.content.jsonArray)
            .filter { it.jsonObject.getValue("type").jsonPrimitive.content == "image_url" }
        assertEquals(originalMedia, tail.dropLast(1))
        val description = tool(body, "context").getValue("description").jsonPrimitive.content
        assertFalse(description.contains("first-image"))
        assertFalse(description.contains("second-image"))
        assertFalse(description.contains("third-image"))
        val context = Json.parseToJsonElement(description).jsonArray
        assertEquals(first.content.jsonArray.first(), context[0].jsonObject.getValue("content").jsonArray.first())
        assertEquals(listOf(0, 1, 2), context.flatMap { it.jsonObject.getValue("content").jsonArray }
            .mapNotNull { it.jsonObject["media_index"]?.jsonPrimitive?.content?.toInt() })
    }

    @Test
    fun `a user only request remains represented in context`() {
        val input = listOf(ChatApiMessage.text("user", "连接探测原文"))
        val body = Json.parseToJsonElement(StreamingChatService(experimentalApiModeEnabled = { true })
            .buildRequestBody(input, model(), false)).jsonObject
        val context = Json.parseToJsonElement(tool(body, "context").getValue("description").jsonPrimitive.content).jsonArray
        assertEquals(input.single().content, context.single().jsonObject["content"])
    }

    @Test
    fun `character by character arguments preserve split escapes and surrogate pairs`() {
        val arguments = """{"content":"正文\n\"引号\"\\路径\t\u4F60\u597D\uD83D\uDE3A"}"""
        val decoder = ExperimentalToolReplyDecoder()
        val result = StringBuilder()
        arguments.forEachIndexed { index, character ->
            result.append(decoder.append(null, calls(character.toString(), if (index == 0) "game_content" else null)).orEmpty())
        }
        val completion = decoder.finish()
        result.append(completion.remainingText)
        assertTrue(completion.toolReplyCompleted)
        assertEquals("正文\n\"引号\"\\路径\t你好😺", result.toString())
        assertEquals("", decoder.finish().remainingText)
    }

    @Test
    fun `ordinary preamble is excluded from tool content and extra fields can precede content`() {
        val decoder = ExperimentalToolReplyDecoder()
        assertEquals(null, decoder.append("前导解释", null))
        val prefix = decoder.append(null, calls("""{"other":{"content":"干扰"},"content":"正确正文"}""", "game_content"))
        assertEquals("正确正文", prefix)
        val completion = decoder.finish()
        assertEquals("正确正文", prefix.orEmpty() + completion.remainingText)
        assertTrue(completion.toolReplyCompleted)
    }

    @Test
    fun `content after nested fields streams before the enclosing JSON is complete`() {
        val decoder = ExperimentalToolReplyDecoder()
        val fragments = """{"metadata":{"content":"干扰\\\"","items":[{"content":"无关"}]},"co\u006etent":"正文\n"""
        val emitted = StringBuilder()
        fragments.forEachIndexed { index, character ->
            emitted.append(decoder.append(null, calls(character.toString(), if (index == 0) "game_content" else null)).orEmpty())
        }
        assertEquals("正文\n", emitted.toString())
        emitted.append(decoder.append(null, calls("后段\"}", null)).orEmpty())
        val completion = decoder.finish()
        assertEquals("正文\n后段", emitted.toString() + completion.remainingText)
        assertTrue(completion.toolReplyCompleted)
    }

    @Test
    fun `parallel calls are decoded in index order`() {
        val decoder = ExperimentalToolReplyDecoder()
        decoder.append(null, calls("""{"content":"后段"}""", "game_content", index = 1))
        val prefix = decoder.append(null, calls("""{"content":"前段"}""", "game_content"))
        assertEquals("前段\n后段", prefix.orEmpty() + decoder.finish().remainingText)
    }

    @Test
    fun `malformed arguments and unexpected tools remain explicit failures`() {
        listOf(
            "game_content" to """{"content":"未闭合""",
            "game_content" to """{"content":17}""",
            "game_content" to """{"wrong":"字段缺失"}""",
            "context" to "{}"
        ).forEach { (name, arguments) ->
            val decoder = ExperimentalToolReplyDecoder()
            decoder.append(null, calls(arguments, name))
            assertTrue(runCatching { decoder.finish() }.isFailure)
        }
    }

    @Test
    fun `truncated tool reply can retain decoded partial text without success evidence`() {
        val decoder = ExperimentalToolReplyDecoder()
        assertEquals("部分正文", decoder.append(null, calls("""{"content":"部分正文""", "game_content")))
        assertFalse(decoder.finish(validate = false).toolReplyCompleted)
    }

    @Test
    fun `ordinary response and non stream tool response are both understood`() {
        val decoder = ExperimentalToolReplyDecoder()
        decoder.append("普通", null)
        decoder.append("正文", null)
        assertEquals(ExperimentalToolReplyDecoder.Completion("普通正文", false), decoder.finish())
        val message = buildJsonObject {
            put("content", "不应混入的说明")
            put("tool_calls", calls("""{"content":"工具正文"}""", "game_content"))
        }
        assertEquals("工具正文", ExperimentalToolReplyDecoder.decodeMessage(message))
    }

    private fun request(experimental: Boolean): JsonObject = Json.parseToJsonElement(
        StreamingChatService(experimentalApiModeEnabled = { experimental }).buildRequestBody(
            messages, model(), true, maxTokens = 4096, promptCacheKey = "stable-cache"
        )
    ).jsonObject

    private fun tool(body: JsonObject, name: String): JsonObject = body.getValue("tools").jsonArray
        .map { it.jsonObject.getValue("function").jsonObject }
        .single { it.getValue("name").jsonPrimitive.content == name }

    private fun model() = ModelConfig(
        id = "test", displayName = "Test", baseUrl = "https://example.invalid/v1", apiKey = "",
        modelName = "test", createdAt = 0L, customParams = mapOf("temperature" to ParamValue.NumberValue(0.7))
    )

    private fun calls(arguments: String, name: String?, index: Int = 0): JsonArray = buildJsonArray {
        add(buildJsonObject {
            put("index", index)
            put("function", buildJsonObject {
                name?.let { put("name", it) }
                put("arguments", arguments)
            })
        })
    }
}
