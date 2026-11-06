package com.example.chatbar.domain.chat

import com.example.chatbar.data.local.entity.ModelConfig
import java.net.URI
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** DS3.2's ordinary chat endpoint is not an assistant-prefix completion endpoint. */
internal object SiliconFlowChatTemplatePolicy {
    fun adaptRequest(request: JsonObject, model: ModelConfig): JsonObject {
        if (!requiresCompatibility(model)) return request
        val original = request["messages"] as? JsonArray ?: return request
        val messages = original.toList()
        val tail = messages.lastOrNull() as? JsonObject
        val adapted = if (tail.role() == "assistant") {
            preserveTailInsideLastUser(messages)
        } else {
            messages
        }
        val serialized = JsonArray(adapted.map { element ->
            val message = element as? JsonObject ?: return@map element
            val content = message["content"] ?: return@map message
            JsonObject(message.toMutableMap().apply { put("content", flattenText(content)) })
        })
        if (serialized == original) return request
        return JsonObject(request.toMutableMap().apply { put("messages", serialized) })
    }

    private fun requiresCompatibility(model: ModelConfig): Boolean {
        val host = runCatching { URI(model.baseUrl.trim()).host?.lowercase() }.getOrNull()
        if (host !in setOf("api.siliconflow.cn", "api.siliconflow.com")) return false
        val name = model.modelName.substringAfterLast('/').lowercase()
        return name == "deepseek-v3.2" || name.startsWith("deepseek-v3.2-")
    }

    private fun preserveTailInsideLastUser(messages: List<JsonElement>): List<JsonElement> {
        val userIndex = messages.indexOfLast { (it as? JsonObject).role() == "user" }
        if (userIndex < 0) {
            val tailIndex = messages.indexOfLast { (it as? JsonObject).role() != "assistant" } + 1
            return messages.take(tailIndex) + userMessage(JsonArray(messages.drop(tailIndex)).toString())
        }
        val user = messages[userIndex] as JsonObject
        // Preserve every trailing role and its exact content, including post-history systems.
        // This is quoted context, not a claim that this endpoint supports native prefill.
        val quotedTail = JsonArray(messages.drop(userIndex + 1)).toString()
        val content = when (val input = user["content"]) {
            is JsonArray -> JsonArray(input + buildJsonObject {
                put("type", "text")
                put("text", quotedTail)
            })
            is JsonPrimitive -> {
                if (!input.isString) return messages
                JsonPrimitive(input.content + "\n\n" + quotedTail)
            }
            else -> return messages
        }
        return messages.take(userIndex) + JsonObject(user.toMutableMap().apply { put("content", content) })
    }

    private fun flattenText(content: JsonElement): JsonElement {
        if (content !is JsonArray || content.isEmpty()) return content
        val texts = content.map { part ->
            val item = part as? JsonObject ?: return content
            if ((item["type"] as? JsonPrimitive)?.contentOrNull != "text") return content
            val text = item["text"] as? JsonPrimitive ?: return content
            if (!text.isString) return content
            text.content
        }
        return JsonPrimitive(texts.joinToString("\n\n"))
    }

    private fun JsonObject?.role(): String? = (this?.get("role") as? JsonPrimitive)?.contentOrNull

    private fun userMessage(content: String): JsonObject = buildJsonObject {
        put("role", "user")
        put("content", content)
    }
}
