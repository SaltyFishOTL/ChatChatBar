package com.example.chatbar.domain.model

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
    isMultimodal && modelName == other.modelName && apiKey.trim() == other.apiKey.trim() &&
        visionApiBase(baseUrl)?.let { it == visionApiBase(other.baseUrl) } == true
