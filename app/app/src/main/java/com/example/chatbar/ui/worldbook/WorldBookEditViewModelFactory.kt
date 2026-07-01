package com.example.chatbar.ui.worldbook

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

class WorldBookEditViewModelFactory(private val worldBookId: String?) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        return WorldBookEditViewModel(worldBookId) as T
    }
}
