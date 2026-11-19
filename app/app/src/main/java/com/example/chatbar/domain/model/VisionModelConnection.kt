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
    val endpoint = base.toString().trimEnd('/')
    return when (base.host) {
        "api.deepseek.com" -> "DeepSeek · $endpoint"
        "api.siliconflow.cn", "api.siliconflow.com" -> "硅基流动 · $endpoint"
        else -> endpoint
    }
}

internal fun sameVisionApiBase(first: String, second: String): Boolean =
    visionApiBase(first)?.let { it == visionApiBase(second) } == true

internal fun ModelConfig.matchesVisionConnection(other: ModelConfig): Boolean =
    isMultimodal && apiKey.trim() == other.apiKey.trim() &&
        sameVisionApiBase(baseUrl, other.baseUrl) &&
        copy(id = other.id, baseUrl = other.baseUrl, apiKey = other.apiKey, createdAt = other.createdAt) == other

internal fun isSiliconFlowVisionApiBase(baseUrl: String): Boolean =
    visionApiBase(baseUrl)?.let {
        it.isHttps && it.port == 443 && it.encodedPath in setOf("/", "/v1") &&
            it.host in setOf("api.siliconflow.cn", "api.siliconflow.com")
    } == true

internal fun ModelConfig.withBoundVisionApiKey(settings: AppSettings): ModelConfig? {
    if (!isMultimodal) return null
    val endpoint = visionApiBase(baseUrl) ?: return null
    if (!endpoint.isHttps && !settings.allowCleartextModelApi) return null
    val scopedKey = apiKey.trim().ifEmpty {
        if (!requiresExplicitApiKey && isSiliconFlowVisionApiBase(baseUrl)) settings.siliconFlowApiKey.trim() else ""
    }
    if (scopedKey.isEmpty() && (endpoint.isHttps || requiresExplicitApiKey)) return null
    return copy(apiKey = scopedKey)
}
