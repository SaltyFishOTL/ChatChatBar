package com.example.chatbar.ui.chat

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * 带有 sessionId 参数的 ChatViewModel 工厂类
 */
class ChatViewModelFactory(private val sessionId: String) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ChatViewModel::class.java)) {
            return ChatViewModel(sessionId) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
