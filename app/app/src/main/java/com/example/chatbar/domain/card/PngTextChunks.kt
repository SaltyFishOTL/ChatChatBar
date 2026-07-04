package com.example.chatbar.domain.card

import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

object PngTextChunks {
    const val CHATBAR_CHARACTER_KEYWORD = "ChatBarCharacter"

    private val signature = byteArrayOf(
        0x89.toByte(),
        'P'.code.toByte(),
        'N'.code.toByte(),
        'G'.code.toByte(),
        0x0D,
        0x0A,
        0x1A,
        0x0A
    )

    fun isPng(bytes: ByteArray): Boolean =
        bytes.size >= signature.size && signature.indices.all { bytes[it] == signature[it] }

    fun extractTextChunk(pngBytes: ByteArray, keyword: String, ignoreCase: Boolean = false): String? {
        if (!isPng(pngBytes)) return null
        var pos = signature.size
        while (pos + 12 <= pngBytes.size) {
            val length = readInt(pngBytes, pos)
            if (length < 0) return null
            val typeStart = pos + 4
            val dataStart = pos + 8
            val dataEnd = dataStart + length
            val next = dataEnd + 4
            if (dataEnd > pngBytes.size || next > pngBytes.size) return null
            val type = String(pngBytes, typeStart, 4, Charsets.US_ASCII)

            if (type == "tEXt") {
                val nullIndex = pngBytes.indexOfZero(dataStart, dataEnd)
                if (nullIndex > dataStart) {
                    val candidate = String(pngBytes, dataStart, nullIndex - dataStart, Charsets.US_ASCII)
                    if (candidate.equals(keyword, ignoreCase = ignoreCase)) {
                        return String(pngBytes, nullIndex + 1, dataEnd - nullIndex - 1, Charsets.UTF_8)
                    }
                }
            }
            if (type == "IEND") return null
            pos = next
        }
        return null
    }

    fun insertTextChunk(pngBytes: ByteArray, keyword: String, text: String): ByteArray {
        require(isPng(pngBytes)) { "不是有效 PNG 文件" }
        val keywordBytes = keyword.toByteArray(Charsets.US_ASCII)
        require(keywordBytes.isNotEmpty() && keywordBytes.size <= 79) { "PNG tEXt keyword 长度必须在 1..79" }
        require(keywordBytes.all { byte ->
            val code = byte.toInt() and 0xFF
            code in 32..126
        }) { "PNG tEXt keyword 只能使用可见 ASCII 字符" }

        val textBytes = text.toByteArray(Charsets.UTF_8)
        val data = ByteArray(keywordBytes.size + 1 + textBytes.size)
        keywordBytes.copyInto(data)
        textBytes.copyInto(data, destinationOffset = keywordBytes.size + 1)

        var pos = signature.size
        while (pos + 12 <= pngBytes.size) {
            val length = readInt(pngBytes, pos)
            if (length < 0) break
            val dataEnd = pos + 8 + length
            val next = dataEnd + 4
            if (dataEnd > pngBytes.size || next > pngBytes.size) break
            val type = String(pngBytes, pos + 4, 4, Charsets.US_ASCII)
            if (type == "IEND") {
                val output = ByteArrayOutputStream(pngBytes.size + data.size + 12)
                output.write(pngBytes, 0, pos)
                writeChunk(output, "tEXt", data)
                output.write(pngBytes, pos, pngBytes.size - pos)
                return output.toByteArray()
            }
            pos = next
        }
        error("PNG 缺少 IEND chunk")
    }

    private fun writeChunk(output: ByteArrayOutputStream, type: String, data: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        writeInt(output, data.size)
        output.write(typeBytes)
        output.write(data)
        val crc = CRC32()
        crc.update(typeBytes)
        crc.update(data)
        writeInt(output, crc.value.toInt())
    }

    private fun readInt(bytes: ByteArray, offset: Int): Int =
        ((bytes[offset].toInt() and 0xFF) shl 24) or
            ((bytes[offset + 1].toInt() and 0xFF) shl 16) or
            ((bytes[offset + 2].toInt() and 0xFF) shl 8) or
            (bytes[offset + 3].toInt() and 0xFF)

    private fun writeInt(output: ByteArrayOutputStream, value: Int) {
        output.write((value ushr 24) and 0xFF)
        output.write((value ushr 16) and 0xFF)
        output.write((value ushr 8) and 0xFF)
        output.write(value and 0xFF)
    }

    private fun ByteArray.indexOfZero(start: Int, end: Int): Int {
        for (index in start until end) {
            if (this[index] == 0.toByte()) return index
        }
        return -1
    }
}
