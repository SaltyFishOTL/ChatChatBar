package com.example.chatbar.domain.model

import com.example.chatbar.data.local.entity.AppSettings
import com.example.chatbar.data.local.entity.ModelConfig
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal fun visionApiBase(value: String): HttpUrl? =
    value.trim().trimEnd('/').toHttpUrlOrNull()?.takeIf {
        it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null
    }

internal fun officialVisionModelSuggestion(baseUrl: String): String? =
    visionApiBase(baseUrl)?.takeIf {
        it.isHttps && it.host == "api.deepseek.com" && it.port == 443 && it.encodedPath in setOf("/", "/v1")
    }?.let { "deepseek-flash" }

internal fun visionProviderLabel(baseUrl: String): String {
    val base = visionApiBase(baseUrl) ?: return "接口未配置"
    return when (base.host) {
        "api.deepseek.com" -> "DeepSeek · ${base.host}"
        "api.siliconflow.cn", "api.siliconflow.com" -> "硅基流动 · ${base.host}"
        else -> if (base.port == if (base.isHttps) 443 else 80) base.host else "${base.host}:${base.port}"
    }
}

internal fun ModelConfig.matchesVisionConnection(other: ModelConfig): Boolean =
    isMultimodal && apiKey.trim() == other.apiKey.trim() &&
        visionApiBase(baseUrl)?.let { it == visionApiBase(other.baseUrl) } == true &&
        copy(id = other.id, baseUrl = other.baseUrl, apiKey = other.apiKey, createdAt = other.createdAt) == other

internal fun ModelConfig.withBoundVisionApiKey(settings: AppSettings): ModelConfig? {
    if (!isMultimodal) return null
    val endpoint = visionApiBase(baseUrl) ?: return null
    if (!endpoint.isHttps && !settings.allowCleartextModelApi) return null
    val scopedKey = apiKey.trim().ifEmpty {
        if (endpoint.isHttps && endpoint.port == 443 && endpoint.encodedPath in setOf("/", "/v1") &&
            endpoint.host in setOf("api.siliconflow.cn", "api.siliconflow.com")
        ) settings.siliconFlowApiKey.trim() else ""
    }
    if (scopedKey.isEmpty() && endpoint.isHttps) return null
    return copy(apiKey = scopedKey)
}
