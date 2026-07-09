package com.example.chatbar.ui.character

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * 带有 characterId 参数的 CharacterEditViewModel 工厂类
 */
class CharacterEditViewModelFactory(
    private val characterId: String?,
    private val draftId: String
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(CharacterEditViewModel::class.java)) {
            return CharacterEditViewModel(characterId, draftId) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
