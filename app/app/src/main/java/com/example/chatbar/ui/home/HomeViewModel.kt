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
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job

data class SessionCopyState(
    val busy: Boolean = false,
    val status: String = "",
    val sessionId: String? = null
)

/**
 * 首页 ViewModel - 加载最近会话和可用角色列表
 */
class HomeViewModel : ViewModel() {
    private val chatRepository = ChatBarApp.instance.chatRepository
    private val characterRepository = ChatBarApp.instance.characterRepository
    private val _sessionCopyState = MutableStateFlow(SessionCopyState())
    val sessionCopyState: StateFlow<SessionCopyState> = _sessionCopyState
    private var sessionCopyJob: Job? = null

    fun copySession(session: ChatSession) {
        if (sessionCopyJob?.isActive == true) return
        _sessionCopyState.value = SessionCopyState(busy = true, status = "正在准备复制…")
        sessionCopyJob = ChatBarApp.instance.applicationScope.launch {
            try {
                val copied = ChatBarApp.instance.sessionCopyService.copySession(session.id) { status ->
                    _sessionCopyState.value = SessionCopyState(busy = true, status = status)
                }
                _sessionCopyState.value = SessionCopyState(
                    status = "会话已复制，消息、当前记忆、会话设置和媒体已保留。",
                    sessionId = copied.id
                )
            } catch (error: CancellationException) {
                _sessionCopyState.value = SessionCopyState(status = "已取消复制。")
                throw error
            } catch (error: Exception) {
                _sessionCopyState.value = SessionCopyState(status = "复制失败：${error.message ?: error::class.simpleName}")
            }
        }.also { job ->
            job.invokeOnCompletion { error ->
                if (error is CancellationException && _sessionCopyState.value.busy) {
                    _sessionCopyState.value = SessionCopyState(status = "已取消复制。")
                }
            }
        }
    }

    fun cancelSessionCopy() { sessionCopyJob?.cancel() }

    fun dismissSessionCopy() {
        if (!_sessionCopyState.value.busy) _sessionCopyState.value = SessionCopyState()
    }

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
    private val _modelConfigurationWarnings = MutableStateFlow<List<String>>(emptyList())
    val modelConfigurationWarnings: StateFlow<List<String>> = _modelConfigurationWarnings
    private val _isModelConfigurationUsable = MutableStateFlow(false)
    val isModelConfigurationUsable: StateFlow<Boolean> = _isModelConfigurationUsable

    init {
        viewModelScope.launch {
            chatRepository.initialize()
            characterRepository.initialize()
            ChatBarApp.instance.effectiveModelResolver.observeConfiguration().collect { configuration ->
                val status = configuration.status()
                _modelConfigurationErrors.value = status.errors
                _modelConfigurationWarnings.value = status.warnings
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

    fun updateSessionDisplayTitle(sessionId: String, displayTitle: String?) {
        viewModelScope.launch {
            chatRepository.updateSessionDisplayTitle(sessionId, displayTitle)
        }
    }

    fun deleteSession(session: ChatSession) {
        ChatBarApp.instance.applicationScope.launch {
            ChatBarApp.instance.deletionCoordinator.deleteSession(session.id)
        }
    }
}
