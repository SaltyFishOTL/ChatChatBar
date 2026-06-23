package com.example.chatbar

import androidx.navigation3.runtime.NavKey
import kotlinx.serialization.Serializable

@Serializable data object HomeRoute : NavKey
@Serializable data object ManageRoute : NavKey
@Serializable data class ChatRoute(val sessionId: String) : NavKey
@Serializable data class CharacterEditRoute(val characterId: String? = null) : NavKey
@Serializable data class FormatCardEditRoute(val formatCardId: String? = null) : NavKey
@Serializable data class ModelEditRoute(val modelId: String? = null) : NavKey
@Serializable data class TutorialRoute(val firstLaunch: Boolean = false) : NavKey
