package com.example.chatbar.domain.webai

import com.example.chatbar.data.local.entity.ModelConfig
import com.example.chatbar.domain.chat.ChatApiMessage
import com.example.chatbar.domain.chat.StreamEvent
import kotlinx.coroutines.flow.Flow

interface WebAiGateway {
    fun stream(
        messages: List<ChatApiMessage>,
        modelConfig: ModelConfig
    ): Flow<StreamEvent>
}
