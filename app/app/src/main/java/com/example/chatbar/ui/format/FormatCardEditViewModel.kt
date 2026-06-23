package com.example.chatbar.ui.format

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.entity.FormatCard
import com.example.chatbar.domain.card.NamePolicy
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * 格式卡片编辑器 ViewModel
 */
class FormatCardEditViewModel(private val formatCardId: String?) : ViewModel() {
    private val repository = ChatBarApp.instance.formatCardRepository

    private val _formatCard = MutableStateFlow<FormatCard?>(null)
    val formatCard: StateFlow<FormatCard?> = _formatCard.asStateFlow()

    var name by mutableStateOf("")
    var content by mutableStateOf("")
    var isDefault by mutableStateOf(false)
    var saveError by mutableStateOf<String?>(null)

    init {
        loadFormatCard()
    }

    private fun loadFormatCard() {
        viewModelScope.launch {
            if (formatCardId != null) {
                val card = repository.getById(formatCardId)
                _formatCard.value = card
                if (card != null) {
                    name = card.name
                    content = card.content
                    isDefault = card.isDefault
                }
            }
        }
    }

    /**
     * 保存格式卡片
     */
    fun saveFormatCard(onSuccess: () -> Unit) {
        if (name.isBlank() || content.isBlank()) return

        viewModelScope.launch {
            name = NamePolicy.normalize(name)
            val conflict = repository.getAll().firstOrNull { it.id != formatCardId && NamePolicy.isSame(it.name, name) }
            if (conflict != null) {
                saveError = "名称与“${conflict.name}”冲突"
                return@launch
            }
            saveError = null
            val card = _formatCard.value?.copy(
                name = name,
                content = content,
                isDefault = isDefault
            ) ?: FormatCard(
                id = formatCardId ?: java.util.UUID.randomUUID().toString(),
                name = name,
                content = content,
                isDefault = isDefault,
                createdAt = System.currentTimeMillis()
            )

            repository.save(card)
            onSuccess()
        }
    }
}
