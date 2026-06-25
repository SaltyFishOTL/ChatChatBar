package com.example.chatbar.domain.card

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

object SillyTavernCardParser {

    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    fun parseUri(context: Context, uri: Uri): SillyTavernCard {
        val rawJson: String?
        val pngBytes: ByteArray?
        if (isPngContent(context, uri)) {
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            pngBytes = bytes
            rawJson = bytes?.let { extractCharaChunk(it) }
        } else {
            pngBytes = null
            rawJson = try {
                context.contentResolver.openInputStream(uri)?.bufferedReader()?.use { it.readText() }
            } catch (_: Exception) { null }
        }

        if (rawJson == null) {
            // Last resort: try PNG extraction even if MIME didn't indicate PNG
            val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() }
            val chunk = bytes?.let { extractCharaChunk(it) }
                ?: throw IllegalArgumentException("未提取出角色卡数据")
            return parseJson(chunk).copy(pngBytes = bytes)
        }

        return parseJson(rawJson).copy(pngBytes = pngBytes)
    }

    private fun parseJson(raw: String): SillyTavernCard {
        val doc = json.parseToJsonElement(raw).jsonObject
        return if (doc.containsKey("spec")) {
            parseV2(doc)
        } else {
            parseV1(doc)
        }
    }

    private fun parseV1(doc: JsonObject): SillyTavernCard = SillyTavernCard(
        name = doc.string("name"),
        description = doc.string("description"),
        personality = doc.string("personality"),
        scenario = doc.string("scenario"),
        firstMes = doc.string("first_mes"),
        mesExample = doc.string("mes_example")
    )

    private fun parseV2(doc: JsonObject): SillyTavernCard {
        val data = doc["data"]?.jsonObject ?: throw IllegalArgumentException("V2 角色卡缺少 data 字段")
        return SillyTavernCard(
            name = data.string("name"),
            description = data.string("description"),
            personality = data.string("personality"),
            scenario = data.string("scenario"),
            firstMes = data.string("first_mes"),
            mesExample = data.string("mes_example"),
            systemPrompt = data.string("system_prompt"),
            postHistoryInstructions = data.string("post_history_instructions"),
            alternateGreetings = data.jsonArray("alternate_greetings"),
            creatorNotes = data.string("creator_notes"),
            tags = data.jsonArray("tags"),
            creator = data.string("creator"),
            characterVersion = data.string("character_version"),
            extensions = data["extensions"]?.toString() ?: "",
            characterBook = data["character_book"]?.toString()
        )
    }

    private fun isPngContent(context: Context, uri: Uri): Boolean {
        // Check MIME type first
        val mime = context.contentResolver.getType(uri)
        if (mime == "image/png") return true

        // Check file extension from URI path or display name
        val displayName = getDisplayName(context, uri)
        if (displayName != null && displayName.lowercase().endsWith(".png")) return true

        // Check if URI itself has .png extension (file:// URIs)
        if (uri.path?.lowercase()?.endsWith(".png") == true) return true

        // Fallback: read first bytes to check PNG magic header
        return isPngMagicHeader(context, uri)
    }

    private fun getDisplayName(context: Context, uri: Uri): String? {
        var name: String? = null
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                name = cursor.getString(cursor.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            }
        }
        return name
    }

    private fun isPngMagicHeader(context: Context, uri: Uri): Boolean {
        try {
            context.contentResolver.openInputStream(uri)?.use { stream ->
                val header = ByteArray(8)
                if (stream.read(header) == 8) {
                    return header[0] == 0x89.toByte() &&
                        header[1] == 'P'.code.toByte() &&
                        header[2] == 'N'.code.toByte() &&
                        header[3] == 'G'.code.toByte()
                }
            }
        } catch (_: Exception) {}
        return false
    }

    private fun extractCharaChunkFromPng(context: Context, uri: Uri): String? {
        val bytes = context.contentResolver.openInputStream(uri)?.use { it.readBytes() } ?: return null
        return extractCharaChunk(bytes)
    }

    fun extractCharaChunk(pngBytes: ByteArray): String? {
        if (pngBytes.size < 8 || pngBytes[0] != 0x89.toByte()) return null

        var pos = 8
        while (pos + 8 < pngBytes.size) {
            val length = ((pngBytes[pos].toInt() and 0xFF) shl 24) or
                ((pngBytes[pos + 1].toInt() and 0xFF) shl 16) or
                ((pngBytes[pos + 2].toInt() and 0xFF) shl 8) or
                (pngBytes[pos + 3].toInt() and 0xFF)
            val typeBytes = pngBytes.sliceArray(pos + 4 until pos + 8)
            val type = String(typeBytes, Charsets.US_ASCII)

            if (type == "tEXt" && pos + 8 + length <= pngBytes.size) {
                val data = pngBytes.sliceArray(pos + 8 until pos + 8 + length)
                val nullIdx = data.indexOf(0.toByte())
                if (nullIdx > 0) {
                    val keyword = String(data.sliceArray(0 until nullIdx), Charsets.US_ASCII)
                    if (keyword.equals("Chara", ignoreCase = true)) {
                        val b64 = String(data.sliceArray(nullIdx + 1 until data.size), Charsets.UTF_8)
                        return String(Base64.decode(b64, Base64.DEFAULT), Charsets.UTF_8)
                    }
                }
            }

            pos += 12 + length
        }
        return null
    }

    private fun JsonObject.string(key: String): String =
        this[key]?.jsonPrimitive?.content ?: ""

    private fun JsonObject.jsonArray(key: String): List<String> {
        val element = this[key] ?: return emptyList()
        return try {
            when (element) {
                is kotlinx.serialization.json.JsonArray -> element.mapNotNull { it.jsonPrimitive?.content }
                else -> {
                    val raw = element.toString().trim()
                    if (raw.startsWith("[") && raw.endsWith("]")) {
                        val inner = raw.substring(1, raw.length - 1).trim()
                        if (inner.isEmpty()) emptyList()
                        else inner.split(",").map { it.trim().removeSurrounding("\"") }
                    } else emptyList()
                }
            }
        } catch (_: Exception) {
            emptyList()
        }
    }
}

data class SillyTavernCard(
    val name: String,
    val description: String = "",
    val personality: String = "",
    val scenario: String = "",
    val firstMes: String = "",
    val mesExample: String = "",
    val systemPrompt: String = "",
    val postHistoryInstructions: String = "",
    val alternateGreetings: List<String> = emptyList(),
    val creatorNotes: String = "",
    val tags: List<String> = emptyList(),
    val creator: String = "",
    val characterVersion: String = "",
    val extensions: String = "",
    val characterBook: String? = null,
    val pngBytes: ByteArray? = null
)
