package com.example.chatbar.ui.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.data.local.entity.ChatSession
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * 首页 ViewModel - 加载最近会话和可用角色列表
 */
class HomeViewModel : ViewModel() {
    private val chatRepository = ChatBarApp.instance.chatRepository
    private val characterRepository = ChatBarApp.instance.characterRepository

    // 会话列表，按置顶+更新时间降序排列
    val sessions: StateFlow<List<ChatSession>> = chatRepository.sessions
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // 所有角色卡，用于开启新对话时的选择
    val characters: StateFlow<List<CharacterCard>> = characterRepository.characters
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    private val _modelConfigurationErrors = MutableStateFlow<List<String>>(emptyList())
    val modelConfigurationErrors: StateFlow<List<String>> = _modelConfigurationErrors
    private val _isModelConfigurationUsable = MutableStateFlow(false)
    val isModelConfigurationUsable: StateFlow<Boolean> = _isModelConfigurationUsable

    init {
        viewModelScope.launch {
            chatRepository.initialize()
            characterRepository.initialize()
            ChatBarApp.instance.settingsRepository.initialize()
            ChatBarApp.instance.settingsRepository.appSettings.collect { settings ->
                val status = ChatBarApp.instance.effectiveModelResolver.status(settings)
                _modelConfigurationErrors.value = status.errors
                _isModelConfigurationUsable.value = status.isUsable
            }
        }
    }

    /**
     * 创建一个新会话，并在完成后通过回调返回会话 ID
     */
    fun createSession(characterCard: CharacterCard, onSessionCreated: (String) -> Unit) {
        viewModelScope.launch {
            if (ChatBarApp.instance.effectiveModelResolver.status().isUsable) {
                onSessionCreated(ChatBarApp.instance.characterSessionService.createSessionForCharacter(characterCard.id))
            }
        }
    }

    fun togglePinSession(session: ChatSession) {
        viewModelScope.launch {
            if (session.isPinned) {
                chatRepository.unpinSession(session.id)
            } else {
                chatRepository.pinSession(session.id)
            }
        }
    }

    fun deleteSession(session: ChatSession) {
        ChatBarApp.instance.applicationScope.launch {
            ChatBarApp.instance.deletionCoordinator.deleteSession(session.id)
        }
    }
}
