package com.example.chatbar.domain.backup

import java.io.Reader
import java.io.Writer
import kotlinx.serialization.builtins.serializer

/**
 * Strict, bounded-memory JSON transducer. Large text/Base64 values pass through unchanged.
 * Only registered resource fields and legacy resource-map keys are interpreted as paths.
 */
internal class BackupJsonStream(
    private val input: Reader,
    private var output: Writer,
    private val validationSkeleton: Boolean = false,
    private val validateRecord: (String, String) -> Unit = { _, _ -> },
    private val observeId: (String) -> Unit = {},
    private val checkActive: () -> Unit = {},
    private val mapPath: (String, List<String>) -> String
) {
    private var look = input.read()
    private val ancestors = mutableListOf<String>()
    private var consumed = 0

    fun copy() {
        value(null, 0)
        whitespace()
        require(look == -1) { "JSON 包含多余内容" }
        output.flush()
    }

    private fun take(): Int {
        if (++consumed % 8192 == 0) checkActive()
        return look.also { look = input.read() }
    }
    private fun writeTake() { output.write(take()) }
    private fun whitespace() {
        while (look == 32 || look == 9 || look == 10 || look == 13) writeTake()
    }
    private fun expect(c: Char) {
        require(look == c.code) { "JSON 结构损坏" }
        writeTake()
    }

    private fun value(field: String?, depth: Int) {
        require(depth <= 128) { "JSON 嵌套过深" }
        whitespace()
        when (look) {
            '{'.code -> {
                expect('{')
                whitespace()
                val keys = hashSetOf<String>()
                val destination = output
                val suppress = validationSkeleton && depth == 1 &&
                    (field == "imageResources" || field == "audioResources")
                if (suppress) output = discardWriter
                if (look != '}'.code) while (true) {
                    whitespace()
                    val key = string(capture = true)
                    require(keys.add(key)) { "JSON 字段重复" }
                    val resourceKey = field == "imageResources" || field == "audioResources"
                    writeString(if (resourceKey) mapPath(key, ancestors + field!!) else key)
                    whitespace()
                    expect(':')
                    if (field != null) ancestors.add(field)
                    val resourceRecord = if (resourceKey && validationSkeleton) java.io.StringWriter() else null
                    val currentOutput = output
                    if (resourceRecord != null) output = resourceRecord
                    value(key, depth + 1)
                    if (resourceRecord != null) {
                        validateRecord(field!!, resourceRecord.toString())
                        output = currentOutput
                    }
                    if (field != null) ancestors.removeAt(ancestors.lastIndex)
                    whitespace()
                    if (look != ','.code) break
                    expect(',')
                }
                output = destination
                expect('}')
            }
            '['.code -> {
                expect('[')
                whitespace()
                val destination = output
                val suppress = validationSkeleton && depth == 1 &&
                    field in setOf("messages", "vectorChunks", "voiceMessages", "turns")
                if (look != ']'.code) while (true) {
                    val record = if (suppress) java.io.StringWriter() else null
                    if (record != null) output = record
                    value(field, depth + 1)
                    if (record != null) {
                        validateRecord(field!!, record.toString())
                        output = discardWriter
                    }
                    whitespace()
                    if (look != ','.code) break
                    expect(',')
                }
                output = destination
                expect(']')
            }
            '"'.code -> {
                val resource = field != null && BackupDataRegistry.isResourceField(field, ancestors)
                val identity = field == "id" && depth == 1
                val strip = validationSkeleton && (field == "encodedVibe" ||
                    (field == "data" && ancestors.any { it == "imageResources" || it == "audioResources" }))
                val destination = output
                val bounded = if (validationSkeleton && !strip && !resource && !identity) BoundedStringWriter() else null
                if (strip) output = discardWriter else if (bounded != null) output = bounded
                val text = string(capture = resource || identity, observePrefix = field == "encodedVibe",
                    base64 = validationSkeleton && field == "data" &&
                        ancestors.any { it == "imageResources" || it == "audioResources" })
                output = destination
                if (identity) observeId(text)
                if (text.startsWith("chatbar-vibe-file:")) mapPath(text, ancestors + field!!)
                if (strip) writeString("validation-payload")
                else if (resource) writeString(mapPath(text, ancestors + field!!))
                else if (identity) writeString(text)
                else if (bounded != null) {
                    if (bounded.exceeded) writeString("validation-long-text") else output.write(bounded.text.toString())
                }
            }
            't'.code -> literal("true")
            'f'.code -> literal("false")
            'n'.code -> literal("null")
            else -> number()
        }
    }

    private fun string(capture: Boolean, observePrefix: Boolean = false, base64: Boolean = false): String {
        require(look == '"'.code) { "JSON 字符串缺失" }
        take()
        if (!capture) output.write('"'.code)
        val result = if (capture || observePrefix) StringBuilder() else null
        var symbols = 0L
        var padding = 0
        fun observe(code: Int) {
            if (!base64 || code in intArrayOf(32, 9, 10, 13)) return
            if (code == 61) {
                padding++
                require(padding <= 2) { "内联媒体 Base64 损坏" }
            } else {
                require(padding == 0 && (code in 65..90 || code in 97..122 || code in 48..57 || code == 43 || code == 47)) {
                    "内联媒体 Base64 损坏"
                }
            }
            symbols++
        }
        while (look != '"'.code) {
            require(look >= 32) { "JSON 字符串损坏或未结束" }
            val c = take()
            if (c == '\\'.code) {
                val escaped = take()
                require(escaped in intArrayOf(34, 92, 47, 98, 102, 110, 114, 116, 117)) {
                    "JSON 转义无效"
                }
                if (!capture) { output.write(c); output.write(escaped) }
                if (escaped == 'u'.code) {
                    var code = 0
                    repeat(4) {
                        val digit = take()
                        val n = digit.toChar().digitToIntOrNull(16)
                        require(digit >= 0 && n != null) { "JSON Unicode 转义无效" }
                        code = code * 16 + n
                        if (!capture) output.write(digit)
                    }
                    if (capture || (result?.length ?: 160) < 160) result?.append(code.toChar())
                    observe(code)
                } else {
                    val decoded = when (escaped.toChar()) {
                        'b' -> '\b'; 'f' -> '\u000c'; 'n' -> '\n'; 'r' -> '\r'; 't' -> '\t'
                        else -> escaped.toChar()
                    }
                    if (capture || (result?.length ?: 160) < 160) result?.append(decoded)
                    observe(decoded.code)
                }
            } else {
                if (capture || (result?.length ?: 160) < 160) result?.append(c.toChar())
                if (!capture) output.write(c)
                observe(c)
            }
            require(!capture || result!!.length <= 32768) { "JSON 字段名或资源路径过长" }
        }
        take()
        require(!base64 || (symbols > 0 && symbols % 4 != 1L &&
            (padding == 0 || symbols % 4 == 0L))) { "内联媒体 Base64 不完整" }
        if (!capture) output.write('"'.code)
        return result?.toString().orEmpty()
    }

    private fun writeString(value: String) {
        output.write(backupJson.encodeToString(String.serializer(), value))
    }
    private class BoundedStringWriter : Writer() {
        val text = StringBuilder()
        var exceeded = false
            private set
        override fun write(chars: CharArray, offset: Int, length: Int) {
            if (exceeded) return
            if (length > 32768 - text.length) { exceeded = true; text.setLength(0) }
            else text.append(chars, offset, length)
        }
        override fun flush() {}
        override fun close() {}
    }
    private fun literal(text: String) { text.forEach(::expect) }
    private fun number() {
        if (look == '-'.code) writeTake()
        if (look == '0'.code) writeTake() else {
            require(look in '1'.code..'9'.code) { "JSON 数值无效" }
            while (look in '0'.code..'9'.code) writeTake()
        }
        if (look == '.'.code) {
            writeTake()
            require(look in '0'.code..'9'.code) { "JSON 数值无效" }
            while (look in '0'.code..'9'.code) writeTake()
        }
        if (look == 'e'.code || look == 'E'.code) {
            writeTake()
            if (look == '+'.code || look == '-'.code) writeTake()
            require(look in '0'.code..'9'.code) { "JSON 数值无效" }
            while (look in '0'.code..'9'.code) writeTake()
        }
    }

    private companion object {
        val discardWriter = object : Writer() {
            override fun write(chars: CharArray, offset: Int, length: Int) {}
            override fun flush() {}
            override fun close() {}
        }
    }
}
