package com.example.chatbar.domain.model

object PresetModelPolicy {
    fun isConfigured(modelName: String): Boolean =
        modelName.isNotBlank() && !modelName.startsWith("TODO_")
}
