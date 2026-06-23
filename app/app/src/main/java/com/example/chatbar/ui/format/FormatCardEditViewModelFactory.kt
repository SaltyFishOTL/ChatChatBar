package com.example.chatbar.ui.format

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider

/**
 * 带有 formatCardId 参数的 FormatCardEditViewModel 工厂类
 */
class FormatCardEditViewModelFactory(private val formatCardId: String?) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(FormatCardEditViewModel::class.java)) {
            return FormatCardEditViewModel(formatCardId) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
