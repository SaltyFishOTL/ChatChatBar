package com.example.chatbar.ui.moments

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.entity.MomentPost
import com.example.chatbar.data.local.entity.MomentTaskStatus
import com.example.chatbar.domain.card.CharacterCardImageTarget
import com.example.chatbar.domain.card.CharacterCardImageUpdater
import com.example.chatbar.domain.moment.MomentGenerationProgressPhase
import com.example.chatbar.domain.moment.MomentGenerationResult
import com.example.chatbar.domain.service.AiBackgroundWorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class MomentRetryUiState(
    val isRunning: Boolean = false,
    val phaseLabel: String = "",
    val streamedText: String = "",
    val progress: Float = 0f,
    val errorMessage: String? = null
)

class MomentsViewModel : ViewModel() {
    private val repository = ChatBarApp.instance.momentRepository
    private val characterRepository = ChatBarApp.instance.characterRepository
    private val chatRepository = ChatBarApp.instance.chatRepository
    private val settingsRepository = ChatBarApp.instance.settingsRepository
    private val modelResolver = ChatBarApp.instance.effectiveModelResolver
    private val generationService = ChatBarApp.instance.momentGenerationService
    private val scheduler = ChatBarApp.instance.momentScheduler
    private val imageStorage = ChatBarApp.instance.novelAiImageStorage
    private val _retryStates = MutableStateFlow<Map<String, MomentRetryUiState>>(emptyMap())

    val posts: StateFlow<List<MomentPost>> = repository.posts
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())
    val retryStates: StateFlow<Map<String, MomentRetryUiState>> = _retryStates.asStateFlow()

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

    fun retryPlaceholder(id: String) {
        if (_retryStates.value[id]?.isRunning == true) return
        viewModelScope.launch {
            setRetryState(id, MomentRetryUiState(isRunning = true, phaseLabel = "准备重新生成"))
            val outcome = runCatching {
                repository.initialize()
                characterRepository.initialize()
                chatRepository.initialize()
                settingsRepository.initialize()
                val placeholder = repository.getPost(id) ?: error("占位朋友圈不存在")
                require(placeholder.isPlaceholder) { "当前朋友圈不是占位状态" }
                val card = characterRepository.getById(placeholder.characterCardId) ?: error("角色卡不存在")
                val session = chatRepository.getSession(placeholder.sessionId) ?: error("会话不存在")
                val settings = settingsRepository.getAppSettings()
                val model = modelResolver.defaultChatModel(settings) ?: error("未配置可用默认对话模型")
                require(model.apiKey.isNotBlank()) { "默认对话模型/API Key 未配置" }
                val imageModel = modelResolver.defaultImageModel(settings)
                val messages = chatRepository.getMessages(session.id)
                val latestPost = repository.latestPostForCard(card.id)
                val result = AiBackgroundWorkManager.run("moments_retry_$id") {
                    generationService.generateStreaming(
                        card = card,
                        session = session,
                        messages = messages,
                        latestPost = latestPost,
                        model = model,
                        imageModel = imageModel,
                        scheduledAt = placeholder.scheduledAt,
                        finalPromptRequirement = settings.imagePromptToolPreference,
                        resumeFrom = generationService.decodeCheckpoint(placeholder.generationCheckpoint),
                        onCheckpoint = { checkpoint ->
                            repository.getPost(id)?.takeIf { it.isPlaceholder }?.let { current ->
                                repository.updatePost(
                                    current.copy(
                                        generationCheckpoint = generationService.encodeCheckpoint(checkpoint)
                                    )
                                )
                            }
                        }
                    ) { progress ->
                        setRetryState(
                            id,
                            MomentRetryUiState(
                                isRunning = true,
                                phaseLabel = progress.message,
                                streamedText = progress.streamedText,
                                progress = when (progress.phase) {
                                    MomentGenerationProgressPhase.GENERATING_IMAGE,
                                    MomentGenerationProgressPhase.SAVING,
                                    MomentGenerationProgressPhase.DONE -> progress.progress
                                    else -> 0f
                                }
                            )
                        )
                    }
                }
                when (result) {
                    is MomentGenerationResult.Posted -> {
                        val replacement = result.post.copy(
                            id = placeholder.id,
                            createdAt = placeholder.createdAt,
                            isPlaceholder = false,
                            failureReason = null,
                            generationCheckpoint = ""
                        )
                        repository.updatePost(replacement)
                        repository.taskForPost(id)?.let { task ->
                            repository.updateTask(
                                task.copy(
                                    status = MomentTaskStatus.COMPLETED,
                                    postId = id,
                                    failureReason = null
                                )
                            )
                        }
                    }
                    is MomentGenerationResult.Skipped -> error(result.reason)
                }
            }
            val error = outcome.exceptionOrNull()
            if (error == null) {
                setRetryState(id, null)
            } else {
                if (error is CancellationException) throw error
                val message = error.message ?: error.javaClass.simpleName
                repository.getPost(id)?.takeIf { it.isPlaceholder }?.let { post ->
                    repository.updatePost(post.copy(failureReason = message, generationReason = message))
                }
                repository.taskForPost(id)?.let { task ->
                    repository.updateTask(task.copy(status = MomentTaskStatus.FAILED, failureReason = message))
                }
                setRetryState(
                    id,
                    MomentRetryUiState(
                        isRunning = false,
                        phaseLabel = "重新生成失败",
                        errorMessage = message
                    )
                )
            }
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

    private fun setRetryState(postId: String, state: MomentRetryUiState?) {
        val next = _retryStates.value.toMutableMap()
        if (state == null) {
            next.remove(postId)
        } else {
            next[postId] = state
        }
        _retryStates.value = next
    }

    private companion object {
        const val TAG = "MomentsViewModel"
    }
}
