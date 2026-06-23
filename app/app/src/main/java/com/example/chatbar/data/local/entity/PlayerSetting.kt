package com.example.chatbar.data.local.entity

import kotlinx.serialization.Serializable

/**
 * 全局玩家角色设定
 */
@Serializable
data class PlayerSetting(
    val id: String = "default",
    val playerName: String = "", // 玩家名称
    val globalPersona: String = "", // 全局玩家角色设定
    val updatedAt: Long = System.currentTimeMillis()
)
