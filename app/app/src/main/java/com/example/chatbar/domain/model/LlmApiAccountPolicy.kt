package com.example.chatbar.domain.model

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.data.repository.LlmApiAccountRepository
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

internal const val SILICON_FLOW_LLM_ENDPOINT = "https://api.siliconflow.cn/v1"

internal fun canonicalLlmApiEndpoint(value: String): String? =
    value.trim().toHttpUrlOrNull()?.takeIf {
        it.username.isEmpty() && it.password.isEmpty() && it.query == null && it.fragment == null
    }?.toString()?.trimEnd('/')

internal fun isSiliconFlowLlmEndpoint(value: String): Boolean {
    val endpoint = canonicalLlmApiEndpoint(value)?.toHttpUrlOrNull() ?: return false
    return endpoint.isHttps && endpoint.port == 443 &&
        endpoint.host in setOf("api.siliconflow.cn", "api.siliconflow.com") &&
        endpoint.encodedPath in setOf("/", "/v1")
}

internal fun LlmApiAccountRepository.requireValidModelBinding(model: ModelConfig): ModelConfig {
    val id = model.llmAccountId ?: return model
    val account = connection(id)?.account ?: throw IllegalArgumentException("所选对话 API 账户不存在或不可用，请重新选择")
    return model.copy(baseUrl = account.baseUrl, apiKey = "")
}
