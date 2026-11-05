package com.example.chatbar.domain.chat

import com.example.chatbar.domain.prompt.PromptTemplates
import java.security.MessageDigest
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.put

/** Request-only transport. Logical messages and persisted prompts remain unchanged. */
internal object ExperimentalToolChatPolicy {
    const val OUTPUT_TOOL = "game_content"
    const val CONTEXT_TOOL = "context"

    fun wrap(request: JsonObject, messages: List<ChatApiMessage>): JsonObject {
        val firstSystem = messages.firstOrNull()?.takeIf { it.role == "system" }
        val media = mutableListOf<JsonElement>()
        val context = buildJsonArray {
            messages.drop(if (firstSystem == null) 0 else 1).forEach { message ->
                add(buildJsonObject {
                    put("role", message.role)
                    put("content", contextContent(message.content, media))
                })
            }
        }
        val contextDescription = context.toString().let { serialized ->
            val last = messages.lastOrNull()
            if (last?.role == "assistant" && (last.content as? JsonPrimitive)?.isString == true) {
                // Leave the inner assistant string open for the protocol user tail.
                // The outer request is still serialized as complete, valid JSON.
                serialized.removeSuffix("\"}]")
            } else {
                serialized
            }
        }
        val tail = if (media.isEmpty()) {
            JsonPrimitive(PromptTemplates.EXPERIMENTAL_TOOL_USER_TAIL)
        } else {
            buildJsonArray {
                media.forEach(::add)
                add(buildJsonObject {
                    put("type", "text")
                    put("text", PromptTemplates.EXPERIMENTAL_TOOL_USER_TAIL)
                })
            }
        }
        val transportMessages = buildJsonArray {
            add(buildJsonObject {
                put("role", "system")
                put("content", firstSystem?.content ?: JsonPrimitive(""))
            })
            add(buildJsonObject {
                put("role", "user")
                put("content", tail)
            })
        }
        val tools = buildJsonArray {
            add(buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject {
                    put("name", OUTPUT_TOOL)
                    put("description", PromptTemplates.EXPERIMENTAL_GAME_CONTENT_TOOL_DESCRIPTION)
                    put("parameters", buildJsonObject {
                        put("type", "object")
                        put("properties", buildJsonObject {
                            put("content", buildJsonObject { put("type", "string") })
                        })
                        put("required", buildJsonArray { add(JsonPrimitive("content")) })
                    })
                })
            })
            add(buildJsonObject {
                put("type", "function")
                put("function", buildJsonObject {
                    put("name", CONTEXT_TOOL)
                    put("description", contextDescription)
                    put("parameters", buildJsonObject {
                        put("type", "object")
                        put("properties", JsonObject(emptyMap()))
                    })
                })
            })
        }
        return JsonObject(request.toMutableMap().apply {
            put("messages", transportMessages)
            put("tools", tools)
            // Keep the same cache behavior while separating the two wire formats.
            (request["prompt_cache_key"] as? JsonPrimitive)?.contentOrNull?.let { key ->
                val digest = MessageDigest.getInstance("SHA-256")
                    .digest("experimental-tool-v1:$key".toByteArray(Charsets.UTF_8))
                    .joinToString("") { "%02x".format(it) }
                put("prompt_cache_key", JsonPrimitive(digest))
            }
        })
    }

    private fun contextContent(content: JsonElement, media: MutableList<JsonElement>): JsonElement {
        if (content !is JsonArray) return content
        return JsonArray(content.map { part ->
            val type = ((part as? JsonObject)?.get("type") as? JsonPrimitive)?.contentOrNull
            if (type == null || type == "text") {
                part
            } else {
                val index = media.size
                media.add(part)
                buildJsonObject {
                    put("type", type)
                    put("media_index", index)
                }
            }
        })
    }
}
