package com.example.chatbar.domain.voice.qq

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

class QqVoiceTransferCoordinator {
    private val _state = MutableStateFlow<QqVoiceTransferState>(QqVoiceTransferState.Idle)
    val state: StateFlow<QqVoiceTransferState> = _state.asStateFlow()

    @Synchronized
    fun prepare(request: QqVoiceTransferRequest): Boolean {
        val current = _state.value
        if (QqVoiceTransferPolicy.isActive(current)) return false
        _state.value = QqVoiceTransferState.Prepared(request)
        return true
    }

    @Synchronized
    fun markWaitingForQq(): Boolean = transition<QqVoiceTransferState.Prepared> { current ->
        QqVoiceTransferState.WaitingForQq(current.request)
    }

    @Synchronized
    fun markCountdown(secondsRemaining: Int): Boolean {
        val current = _state.value
        val request = when (current) {
            is QqVoiceTransferState.WaitingForQq -> current.request
            is QqVoiceTransferState.Countdown -> current.request
            else -> return false
        }
        _state.value = QqVoiceTransferState.Countdown(request, secondsRemaining.coerceAtLeast(1))
        return true
    }

    @Synchronized
    fun markRecording(): Boolean = transition<QqVoiceTransferState.Countdown> { current ->
        QqVoiceTransferState.Recording(current.request)
    }

    @Synchronized
    fun complete(): Boolean = transition<QqVoiceTransferState.Recording> { current ->
        QqVoiceTransferState.Completed(current.request)
    }

    @Synchronized
    fun fail(failure: QqVoiceTransferFailure): Boolean {
        val current = _state.value
        if (!QqVoiceTransferPolicy.isActive(current)) return false
        _state.value = QqVoiceTransferState.Failed(current.request, failure)
        return true
    }

    @Synchronized
    fun cancel(): Boolean {
        val current = _state.value
        if (!QqVoiceTransferPolicy.isActive(current)) return false
        _state.value = QqVoiceTransferState.Cancelled(current.request)
        return true
    }

    @Synchronized
    fun reset() {
        _state.value = QqVoiceTransferState.Idle
    }

    private inline fun <reified T : QqVoiceTransferState> transition(
        transform: (T) -> QqVoiceTransferState
    ): Boolean {
        val current = _state.value
        if (current !is T) return false
        _state.value = transform(current)
        return true
    }
}
