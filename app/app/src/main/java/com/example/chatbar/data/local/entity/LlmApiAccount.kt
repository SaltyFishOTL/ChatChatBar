package com.example.chatbar.data.local.entity

import kotlinx.serialization.Serializable

@Serializable
data class LlmApiAccount(val id: String, val displayName: String, val baseUrl: String)

/** A request-time credential. Keep it out of persisted model configurations and UI state. */
class LlmApiConnection(val account: LlmApiAccount, val apiKey: String)
