package com.example.chatbar.data.local.entity

import kotlinx.serialization.Serializable

@Serializable
data class TimedEffectState(
    val entryId: String,
    val stickyUntil: Int = 0,
    val cooldownUntil: Int = 0
)
