package com.example.chatbar.data.local.entity

import kotlinx.serialization.Serializable

@Serializable
enum class ChatNarrativeMode(val label: String, val shortLabel: String) {
    FOCUS("聚焦模式", "聚焦"),
    ADVANCE("推进模式", "推进")
}
