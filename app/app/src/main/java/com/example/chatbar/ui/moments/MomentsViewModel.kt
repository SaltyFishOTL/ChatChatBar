package com.example.chatbar.ui.moments

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.entity.MomentPost
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class MomentsViewModel : ViewModel() {
    private val repository = ChatBarApp.instance.momentRepository
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

    private companion object {
        const val TAG = "MomentsViewModel"
    }
}
