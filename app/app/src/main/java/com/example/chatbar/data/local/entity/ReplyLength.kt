package com.example.chatbar.data.local.entity

import kotlinx.serialization.KSerializer
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.PrimitiveSerialDescriptor
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlinx.serialization.json.JsonDecoder
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonPrimitive

const val DEFAULT_REPLY_LENGTH_CHARS = 300
const val MIN_REPLY_LENGTH_CHARS = 1
const val MAX_REPLY_LENGTH_CHARS = 40_000

private val LEGACY_REPLY_LENGTH_NUMBER = Regex("""(?<![-\d])\d+""")

fun parseLegacyReplyLength(raw: String?): Int {
    if (raw.isNullOrBlank()) return DEFAULT_REPLY_LENGTH_CHARS
    val parsed = LEGACY_REPLY_LENGTH_NUMBER.findAll(raw)
        .mapNotNull { match -> match.value.toLongOrNull() }
        .firstOrNull { value -> value > 0L }
        ?: return DEFAULT_REPLY_LENGTH_CHARS
    return parsed.coerceAtMost(MAX_REPLY_LENGTH_CHARS.toLong()).toInt()
}

object ReplyLengthSerializer : KSerializer<Int> {
    override val descriptor: SerialDescriptor =
        PrimitiveSerialDescriptor("ReplyLengthChars", PrimitiveKind.INT)

    override fun serialize(encoder: Encoder, value: Int) {
        encoder.encodeInt(value.coerceIn(MIN_REPLY_LENGTH_CHARS, MAX_REPLY_LENGTH_CHARS))
    }

    override fun deserialize(decoder: Decoder): Int {
        val jsonDecoder = decoder as? JsonDecoder
        if (jsonDecoder == null) {
            return decoder.decodeInt().coerceIn(MIN_REPLY_LENGTH_CHARS, MAX_REPLY_LENGTH_CHARS)
        }
        return when (val element = jsonDecoder.decodeJsonElement()) {
            JsonNull -> DEFAULT_REPLY_LENGTH_CHARS
            is JsonPrimitive -> parseLegacyReplyLength(element.content)
            else -> DEFAULT_REPLY_LENGTH_CHARS
        }
    }
}
