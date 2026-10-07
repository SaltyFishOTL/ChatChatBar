package com.example.chatbar.domain.image

import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.io.PushbackReader
import java.io.Writer
import java.security.MessageDigest
import kotlinx.serialization.json.Json
import kotlinx.serialization.encodeToString

/** Large Vibe payloads live once on disk, not in every draft, undo and history object. */
class NovelAiInlinePayloadStore(filesDir: File) {
    private val root = File(filesDir, "novelai-vibe-payloads").also { it.mkdirs() }

    fun resolve(value: String): String {
        if (!value.startsWith(PREFIX)) return value
        val hash = value.removePrefix(PREFIX)
        require(hash.matches(Regex("[a-f0-9]{64}"))) { "氛围编码引用无效" }
        val file = File(root, hash)
        require(file.isFile) { "氛围编码文件缺失，请重新导入参考图" }
        return file.readText(Charsets.UTF_8)
    }

    fun retain(value: String): String {
        if (value.startsWith(PREFIX) || value.length < INLINE_LIMIT) return value
        val temporary = File.createTempFile("payload-", ".tmp", root)
        return try {
            temporary.writeText(value, Charsets.UTF_8)
            commit(temporary)
        } finally { temporary.delete() }
    }

    private fun commit(temporary: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        temporary.inputStream().buffered().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        val hash = digest.digest().joinToString("") { "%02x".format(it) }
        val target = File(root, hash)
        if (!target.isFile) check(temporary.renameTo(target) || target.isFile) { "氛围编码保存失败" }
        return PREFIX + hash
    }

    /** Copies JSON lexically with bounded buffers; the source remains untouched on any error. */
    fun openCompactJson(file: File): InputStream {
        val compact = File.createTempFile("json-", ".tmp", root)
        try {
            file.reader(Charsets.UTF_8).buffered().use { raw ->
                val input = PushbackReader(raw, 1)
                compact.writer(Charsets.UTF_8).buffered().use { output ->
                    var payloadKey = false
                    while (true) {
                        val c = input.read()
                        if (c < 0) break
                        if (c != '"'.code) {
                            output.write(c)
                            if (!c.toChar().isWhitespace() && c != ':'.code) payloadKey = false
                            continue
                        }
                        if (payloadKey) {
                            val payload = File.createTempFile("payload-", ".tmp", root)
                            try {
                                payload.writer(Charsets.UTF_8).buffered().use { sink -> copyString(input, sink, decode = true) }
                                val value = if (payload.length() < INLINE_LIMIT) payload.readText(Charsets.UTF_8) else commit(payload)
                                output.write(Json.encodeToString(value))
                            } finally { payload.delete() }
                            payloadKey = false
                        } else {
                            output.write('"'.code)
                            val token = copyString(input, output, decode = false)
                            output.write('"'.code)
                            payloadKey = token == "encodedVibe"
                        }
                    }
                }
            }
            return object : FilterInputStream(compact.inputStream().buffered()) {
                override fun close() { try { super.close() } finally { compact.delete() } }
            }
        } catch (error: Throwable) { compact.delete(); throw error }
    }

    private fun copyString(input: PushbackReader, output: Writer, decode: Boolean): String {
        val token = StringBuilder()
        while (true) {
            val c = input.read()
            require(c >= 0) { "JSON 字符串未结束" }
            if (c == '"'.code) return token.toString()
            if (c == '\\'.code) {
                val escaped = input.read()
                require(escaped >= 0) { "JSON 转义未结束" }
                if (!decode) { output.write(c); output.write(escaped) }
                else when (escaped.toChar()) {
                    '"', '\\', '/' -> output.write(escaped)
                    'b' -> output.write(8)
                    'f' -> output.write(12)
                    'n' -> output.write(10)
                    'r' -> output.write(13)
                    't' -> output.write(9)
                    'u' -> {
                        val digits = CharArray(4) { input.read().also { require(it >= 0) }.toChar() }
                        output.write(String(digits).toInt(16))
                    }
                    else -> throw IllegalArgumentException("JSON 转义无效")
                }
                if (token.length < 32) token.append('!')
            } else {
                require(c >= 32) { "JSON 字符串包含控制字符" }
                output.write(c)
                if (token.length < 32) token.append(c.toChar())
            }
        }
    }

    companion object {
        const val PREFIX = "chatbar-vibe-file:"
        private const val INLINE_LIMIT = 4096
    }
}
