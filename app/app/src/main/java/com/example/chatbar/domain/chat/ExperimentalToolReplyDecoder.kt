package com.example.chatbar.domain.chat

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull

/** Per-request decoder; tool argument fragments must never be rendered as raw JSON. */
internal class ExperimentalToolReplyDecoder {
    data class Completion(val remainingText: String, val toolReplyCompleted: Boolean)

    private class Call {
        val name = StringBuilder()
        val arguments = StringBuilder()
        val content = PartialContentString()
    }

    private val calls = sortedMapOf<Int, Call>()
    private val ordinaryText = StringBuilder()
    private val emitted = StringBuilder()
    private var completed = false

    val hasToolCalls: Boolean get() = calls.isNotEmpty()
    val ordinaryContent: String get() = ordinaryText.toString()

    @Synchronized
    fun append(text: String?, toolCalls: JsonArray?): String? {
        check(!completed) { "实验模式回复已经结束" }
        text?.let(ordinaryText::append)
        toolCalls?.forEachIndexed { position, element ->
            val item = element as? JsonObject ?: error("实验模式工具调用格式无效")
            val index = (item["index"] as? JsonPrimitive)?.intOrNull ?: position
            require(index >= 0) { "实验模式工具调用索引无效" }
            val call = calls.getOrPut(index) { Call() }
            val function = item["function"] as? JsonObject ?: return@forEachIndexed
            (function["name"] as? JsonPrimitive)?.contentOrNull?.let(call.name::append)
            (function["arguments"] as? JsonPrimitive)?.contentOrNull?.let(call.arguments::append)
        }
        // Other calls remain buffered so interleaved parallel calls retain index order.
        val first = calls[0] ?: return null
        if (first.name.toString() != ExperimentalToolChatPolicy.OUTPUT_TOOL) return null
        return first.content.read(first.arguments).takeIf(String::isNotEmpty)?.also(emitted::append)
    }

    @Synchronized
    fun finish(validate: Boolean = true): Completion {
        if (completed) return Completion("", false)
        completed = true
        if (calls.isEmpty()) {
            if (validate) require(ordinaryText.isNotBlank()) { "实验模式未收到正文或 game_content 工具参数" }
            return Completion(ordinaryText.toString(), false)
        }
        if (!validate) return Completion("", false)
        val fullText = calls.values.joinToString("\n") { call ->
            require(call.name.toString() == ExperimentalToolChatPolicy.OUTPUT_TOOL) {
                "实验模式收到非正文工具调用：${call.name}，预期 game_content"
            }
            decodeArguments(call.arguments.toString())
        }
        require(fullText.startsWith(emitted.toString())) { "实验模式工具参数与已接收正文不一致" }
        require(fullText.isNotBlank()) { "实验模式工具回复正文为空" }
        return Completion(fullText.substring(emitted.length), true)
    }

    companion object {
        fun decodeMessage(message: JsonObject): String? {
            val toolCalls = message["tool_calls"] as? JsonArray
                ?: (message["function_call"] as? JsonObject)?.let { function ->
                    JsonArray(listOf(JsonObject(mapOf("function" to function))))
                } ?: return null
            if (toolCalls.isEmpty()) return null
            val decoder = ExperimentalToolReplyDecoder()
            val prefix = decoder.append(null, toolCalls).orEmpty()
            return prefix + decoder.finish().remainingText
        }

        private fun decodeArguments(arguments: String): String {
            val value = try {
                Json.parseToJsonElement(arguments) as? JsonObject
            } catch (error: Exception) {
                throw IllegalArgumentException("实验模式工具参数不是完整 JSON：${error.message}", error)
            }
            val content = value?.get("content") as? JsonPrimitive
            require(content != null && content.isString) { "实验模式工具参数缺少字符串 content" }
            return content.content
        }
    }
}

/** Incrementally decodes the top-level content string, including split JSON escapes. */
private class PartialContentString {
    private val locator = ContentStringLocator()
    private var cursor = -1
    private var escaped = false
    private var unicodeRemaining = 0
    private var unicodeValue = 0
    private var pendingHighSurrogate: Char? = null
    private var finished = false

    fun read(arguments: CharSequence): String = buildString {
        if (finished) return@buildString
        if (cursor < 0) {
            cursor = locator.find(arguments) ?: return@buildString
        }
        fun decoded(character: Char) {
            val high = pendingHighSurrogate
            if (high != null) {
                append(high)
                pendingHighSurrogate = null
                if (character.isLowSurrogate()) {
                    append(character)
                    return
                }
            }
            if (character.isHighSurrogate()) pendingHighSurrogate = character else append(character)
        }
        while (cursor < arguments.length && !finished) {
            val character = arguments[cursor++]
            when {
                unicodeRemaining > 0 -> {
                    val digit = character.digitToIntOrNull(16)
                        ?: error("实验模式工具参数包含无效 Unicode 转义")
                    unicodeValue = unicodeValue * 16 + digit
                    unicodeRemaining--
                    if (unicodeRemaining == 0) decoded(unicodeValue.toChar())
                }
                escaped -> {
                    escaped = false
                    when (character) {
                        'u' -> { unicodeRemaining = 4; unicodeValue = 0 }
                        '"', '\\', '/' -> decoded(character)
                        'b' -> decoded('\b')
                        'f' -> decoded('\u000C')
                        'n' -> decoded('\n')
                        'r' -> decoded('\r')
                        't' -> decoded('\t')
                        else -> error("实验模式工具参数包含无效 JSON 转义")
                    }
                }
                character == '\\' -> escaped = true
                character == '"' -> {
                    pendingHighSurrogate?.let(::append)
                    pendingHighSurrogate = null
                    finished = true
                }
                else -> {
                    require(character.code >= 0x20) { "实验模式工具参数包含未转义控制字符" }
                    decoded(character)
                }
            }
        }
    }
}

/** Skips complete sibling values and nested strings without rescanning earlier fragments. */
private class ContentStringLocator {
    private var cursor = 0
    private var depth = 0
    private var inString = false
    private var escaped = false
    private var expectsKey = false
    private var keyStart = -1
    private var contentKey = false
    private var expectsValue = false

    fun find(arguments: CharSequence): Int? {
        while (cursor < arguments.length) {
            val position = cursor++
            val character = arguments[position]
            if (inString) {
                when {
                    escaped -> escaped = false
                    character == '\\' -> escaped = true
                    character == '"' -> {
                        inString = false
                        if (keyStart >= 0) {
                            val key = Json.parseToJsonElement(arguments.subSequence(keyStart, cursor).toString())
                            contentKey = (key as? JsonPrimitive)?.contentOrNull == "content"
                            keyStart = -1
                        }
                    }
                }
                continue
            }
            if (character.isWhitespace()) continue
            if (expectsValue) {
                expectsValue = false
                if (contentKey && character == '"') return cursor
                contentKey = false
            }
            when (character) {
                '{', '[' -> {
                    depth++
                    if (depth == 1 && character == '{') expectsKey = true
                }
                '}', ']' -> depth--
                ',' -> if (depth == 1) expectsKey = true
                ':' -> if (depth == 1) expectsValue = true
                '"' -> {
                    inString = true
                    if (depth == 1 && expectsKey) {
                        keyStart = position
                        expectsKey = false
                    }
                }
            }
        }
        return null
    }
}
