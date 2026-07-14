package com.example.chatbar.ui.chat

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

class ImageGenerationTaskStateStore {
    private val _states = MutableStateFlow<List<ImageGenerationState>>(emptyList())
    val states: StateFlow<List<ImageGenerationState>> = _states.asStateFlow()

    fun get(taskId: String): ImageGenerationState? =
        _states.value.firstOrNull { it.taskId == taskId }

    fun put(state: ImageGenerationState) {
        _states.update { states ->
            val index = states.indexOfFirst { it.taskId == state.taskId }
            if (index < 0) {
                states + state
            } else {
                states.toMutableList().apply { this[index] = state }
            }
        }
    }

    fun update(taskId: String, transform: (ImageGenerationState) -> ImageGenerationState) {
        _states.update { states ->
            states.map { state -> if (state.taskId == taskId) transform(state) else state }
        }
    }

    fun remove(taskId: String) {
        _states.update { states -> states.filterNot { it.taskId == taskId } }
    }
}
