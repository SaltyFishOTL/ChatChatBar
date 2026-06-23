package com.example.chatbar.ui.model

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * 带有 modelId 参数的 ModelEditViewModel 工厂类
 */
class ModelEditViewModelFactory(private val modelId: String?) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(ModelEditViewModel::class.java)) {
            return ModelEditViewModel(modelId) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
