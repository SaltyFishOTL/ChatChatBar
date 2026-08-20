package com.example.chatbar.domain.chat

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put
import java.net.URI

internal object CleartextHttpChatTemplatePolicy {
    fun adaptMessages(
        messages: List<ChatApiMessage>,
        allowCleartextHttp: Boolean,
        baseUrl: String
    ): List<ChatApiMessage> {
        if (!allowCleartextHttp || !baseUrl.isCleartextHttpUrl()) return messages

        var firstSystemSeen = false
        val adapted = messages.map { message ->
            if (message.role != "system") {
                message
            } else if (!firstSystemSeen) {
                firstSystemSeen = true
                message
            } else {
                message.copy(role = "assistant")
            }
        }

        val sourceTail = messages.lastOrNull()
        if (sourceTail?.role != "system" || adapted.lastOrNull()?.role != "assistant") {
            return adapted
        }

        val userTail = adapted.last().copy(role = "user")
        val previous = adapted.getOrNull(adapted.lastIndex - 1)
        val mergedUser = previous
            ?.takeIf { it.role == "user" }
            ?.appendTextContent(userTail.content as? JsonPrimitive)

        return if (mergedUser != null) {
            adapted.dropLast(2) + mergedUser
        } else {
            adapted.dropLast(1) + userTail
        }
    }

    private fun ChatApiMessage.appendTextContent(suffix: JsonPrimitive?): ChatApiMessage? {
        val suffixText = suffix?.contentOrNull?.takeIf(String::isNotBlank) ?: return null
        val mergedContent = when (val original = content) {
            is JsonPrimitive -> JsonPrimitive(
                listOf(original.contentOrNull.orEmpty(), suffixText)
                    .filter(String::isNotBlank)
                    .joinToString("\n\n")
            )
            is JsonArray -> buildJsonArray {
                original.forEach { add(it) }
                add(buildJsonObject {
                    put("type", "text")
                    put("text", suffixText)
                })
            }
            else -> return null
        }
        return copy(content = mergedContent)
    }

    private fun String.isCleartextHttpUrl(): Boolean = runCatching {
        URI(trim()).scheme.equals("http", ignoreCase = true)
    }.getOrDefault(false)
}
