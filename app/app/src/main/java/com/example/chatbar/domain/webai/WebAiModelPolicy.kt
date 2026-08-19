package com.example.chatbar.domain.webai

import com.example.chatbar.data.local.entity.ChatSession
import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.local.entity.ModelTransport
import com.example.chatbar.data.local.entity.WebAiSite

object WebAiModelPolicy {
    private const val MODEL_ID_PREFIX = "web-ai:"

    fun modelId(sessionId: String): String = MODEL_ID_PREFIX + sessionId

    fun sessionId(modelId: String?): String? = modelId
        ?.takeIf { it.startsWith(MODEL_ID_PREFIX) }
        ?.removePrefix(MODEL_ID_PREFIX)
        ?.takeIf(String::isNotBlank)

    fun isWebModelId(modelId: String?): Boolean = sessionId(modelId) != null

    fun modelFor(session: ChatSession): ModelConfig? {
        val binding = session.webAiBinding ?: return null
        return ModelConfig(
            id = modelId(session.id),
            displayName = "网页版 · ${binding.site.displayName}",
            baseUrl = binding.site.entryUrl,
            apiKey = "",
            modelName = binding.site.name,
            transport = ModelTransport.WEB_VIEW,
            selectableForChat = true,
            isMultimodal = false,
            createdAt = binding.boundAt
        )
    }
}

val WebAiSite.displayName: String
    get() = when (this) {
        WebAiSite.DEEPSEEK -> "DeepSeek"
        WebAiSite.KIMI -> "Kimi"
        WebAiSite.DOUBAO -> "豆包"
    }

val WebAiSite.entryUrl: String
    get() = when (this) {
        WebAiSite.DEEPSEEK -> "https://chat.deepseek.com/"
        WebAiSite.KIMI -> "https://www.kimi.com/"
        WebAiSite.DOUBAO -> "https://www.doubao.com/chat/"
    }

fun WebAiSite.allowsAutomationAt(url: String): Boolean {
    val uri = runCatching { android.net.Uri.parse(url) }.getOrNull() ?: return false
    if (!uri.scheme.equals("https", ignoreCase = true)) return false
    val host = uri.host?.lowercase() ?: return false
    return when (this) {
        WebAiSite.DEEPSEEK -> host == "chat.deepseek.com"
        WebAiSite.KIMI -> host == "kimi.com" || host.endsWith(".kimi.com")
        WebAiSite.DOUBAO -> host == "doubao.com" || host.endsWith(".doubao.com")
    }
}
