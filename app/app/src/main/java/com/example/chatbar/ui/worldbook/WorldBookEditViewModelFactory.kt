package com.example.chatbar.ui.worldbook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

class WorldBookEditViewModelFactory(
    private val worldBookId: String?,
    private val draftId: String
) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return WorldBookEditViewModel(worldBookId, draftId) as T
    }
}
