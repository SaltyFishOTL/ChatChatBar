package com.example.chatbar.domain.model

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal object GeminiOpenAiEndpoint {
    const val BASE_URL = "https://generativelanguage.googleapis.com/v1beta/openai"

    fun resolve(baseUrl: String): String? {
        val url = baseUrl.trim().toHttpUrlOrNull() ?: return null
        if (!url.isHttps || url.host != "generativelanguage.googleapis.com" || url.port != 443 ||
            url.username.isNotEmpty() || url.password.isNotEmpty() ||
            url.query != null || url.fragment != null
        ) return null

        return when (url.encodedPath.trimEnd('/')) {
            "/v1beta", "/v1beta/openai" -> BASE_URL
            else -> null
        }
    }

    fun modelId(value: String): String = value.trim().removePrefix("models/")
}
