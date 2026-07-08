package com.example.chatbar.ui.moments

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.entity.MomentPost
import com.example.chatbar.domain.card.CharacterCardImageTarget
import com.example.chatbar.domain.card.CharacterCardImageUpdater
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MomentsViewModel : ViewModel() {
    private val repository = ChatBarApp.instance.momentRepository
    private val characterRepository = ChatBarApp.instance.characterRepository
    private val scheduler = ChatBarApp.instance.momentScheduler
    private val imageStorage = ChatBarApp.instance.novelAiImageStorage

    val posts: StateFlow<List<MomentPost>> = repository.posts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            repository.initialize()
            scheduler.kick("moments-screen")
        }
    }

    fun toggleLike(id: String) {
        viewModelScope.launch {
            repository.toggleLike(id)
        }
    }

    fun deletePost(id: String) {
        viewModelScope.launch {
            val deleted = repository.deletePost(id) ?: return@launch
            val imagePath = deleted.imagePath?.takeIf(String::isNotBlank) ?: return@launch
            runCatching { imageStorage.deleteIfOwned(imagePath) }
                .onFailure { error -> Log.w(TAG, "Failed to delete moment image: $imagePath", error) }
        }
    }

    fun replaceCharacterCardAvatarFromImage(
        cardId: String,
        imagePath: String,
        onResult: (Boolean, String) -> Unit
    ) {
        replaceCharacterCardImage(cardId, imagePath, CharacterCardImageTarget.AVATAR, onResult)
    }

    fun replaceCharacterCardBackgroundFromImage(
        cardId: String,
        imagePath: String,
        onResult: (Boolean, String) -> Unit
    ) {
        replaceCharacterCardImage(cardId, imagePath, CharacterCardImageTarget.BACKGROUND, onResult)
    }

    private fun replaceCharacterCardImage(
        cardId: String,
        imagePath: String,
        target: CharacterCardImageTarget,
        onResult: (Boolean, String) -> Unit
    ) {
        viewModelScope.launch {
            runCatching {
                CharacterCardImageUpdater.replace(
                    context = ChatBarApp.instance,
                    characterRepository = characterRepository,
                    cardId = cardId,
                    sourcePath = imagePath,
                    target = target
                )
            }.fold(
                onSuccess = {
                    onResult(
                        true,
                        when (target) {
                            CharacterCardImageTarget.AVATAR -> "已替换角色卡头像"
                            CharacterCardImageTarget.BACKGROUND -> "已替换角色卡背景"
                        }
                    )
                },
                onFailure = { error ->
                    onResult(false, error.message ?: "替换图片失败")
                }
            )
        }
    }

    private companion object {
        const val TAG = "MomentsViewModel"
    }
}
