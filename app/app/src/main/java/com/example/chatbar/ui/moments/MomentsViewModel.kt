package com.example.chatbar.ui.moments

import android.graphics.BitmapFactory
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatbar.ChatBarApp
import com.example.chatbar.data.local.entity.MomentPost
import com.example.chatbar.data.local.entity.MomentTaskStatus
import com.example.chatbar.domain.card.CharacterCardImageTarget
import com.example.chatbar.domain.card.CharacterCardImageUpdater
import com.example.chatbar.domain.image.GlobalImageGenerationConcurrencyGate
import com.example.chatbar.domain.image.NovelAiImageEvent
import com.example.chatbar.domain.image.NovelAiImageRegenerationDraft
import com.example.chatbar.domain.image.NovelAiImageSize
import com.example.chatbar.domain.image.NovelAiImageSizePreset
import com.example.chatbar.domain.image.NovelAiPngMetadataReader
import com.example.chatbar.domain.image.NovelAiPromptPlan
import com.example.chatbar.domain.image.toGeneratedImageMetadata
import com.example.chatbar.domain.image.toRegenerationDraft
import com.example.chatbar.domain.moment.MomentGenerationProgressPhase
import com.example.chatbar.domain.moment.MomentGenerationResult
import com.example.chatbar.domain.model.hasConfiguredAuthentication
import com.example.chatbar.domain.service.AiBackgroundWorkManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

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
    private val imageService = ChatBarApp.instance.novelAiImageService
    private val imageStorage = ChatBarApp.instance.novelAiImageStorage
    private val novelAiCredentials = ChatBarApp.instance.novelAiCredentialStore
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

    suspend fun loadNovelAiImageRegenerationDraft(
        postId: String,
        imagePath: String
    ): NovelAiImageRegenerationDraft {
        repository.initialize()
        val post = repository.getPost(postId) ?: error("原朋友圈不存在")
        require(!post.isPlaceholder && post.imagePath == imagePath) { "原朋友圈图片已发生变化" }
        val metadata = post.generatedImageMetadata
            ?.takeIf { it.imagePath == imagePath }
            ?: withContext(Dispatchers.IO) {
                runCatching { NovelAiPngMetadataReader.read(imagePath) }
                    .onFailure { error -> Log.w(TAG, "Failed to read moment PNG metadata: $imagePath", error) }
                    .getOrNull()
            }
        return metadata?.toRegenerationDraft()
            ?: legacyRegenerationDraft(post, imagePath)
    }

    fun regenerateMomentImage(
        postId: String,
        imagePath: String,
        draft: NovelAiImageRegenerationDraft,
        onResult: (String?) -> Unit
    ) {
        if (draft.baseCaption.isBlank()) {
            onResult("主提示词不能为空")
            return
        }
        viewModelScope.launch {
            val outcome = runCatching {
                repository.initialize()
                val source = repository.getPost(postId) ?: error("原朋友圈不存在")
                require(!source.isPlaceholder && source.imagePath == imagePath) { "原朋友圈图片已发生变化" }
                val token = novelAiCredentials.load() ?: error("NovelAI Token 未配置")
                val prompt = draft.toPromptPlan()
                val imageSize = NovelAiImageSize(draft.width, draft.height, "复用原图尺寸")
                val imageBytes = AiBackgroundWorkManager.run("moments_image_regenerate_$postId") {
                    GlobalImageGenerationConcurrencyGate.instance.run {
                        var finalImage: ByteArray? = null
                        var errorMessage: String? = null
                        imageService.generate(
                            token = token,
                            prompt = prompt,
                            seed = imageService.newSeed(),
                            imageSize = imageSize
                        ).collect { event ->
                            when (event) {
                                is NovelAiImageEvent.Final -> finalImage = event.image
                                is NovelAiImageEvent.Error -> errorMessage = event.message
                                is NovelAiImageEvent.Intermediate -> Unit
                            }
                        }
                        errorMessage?.let { error(it) }
                        finalImage ?: error("NovelAI 未返回最终图片")
                    }
                }
                val newImagePath = withContext(Dispatchers.IO) {
                    imageStorage.save("moments_${source.characterCardId}", imageBytes)
                }
                try {
                    val current = repository.getPost(postId) ?: error("原朋友圈不存在")
                    require(!current.isPlaceholder && current.imagePath == imagePath) {
                        "原朋友圈图片已发生变化"
                    }
                    repository.updatePost(
                        current.copy(
                            imagePath = newImagePath,
                            imagePrompt = draft.baseCaption,
                            generatedImageMetadata = prompt.toGeneratedImageMetadata(newImagePath, imageSize)
                        )
                    )
                } catch (error: Throwable) {
                    withContext(Dispatchers.IO) {
                        runCatching { imageStorage.deleteIfOwned(newImagePath) }
                            .onFailure { cleanupError ->
                                Log.w(TAG, "Failed to delete unused regenerated image: $newImagePath", cleanupError)
                            }
                    }
                    throw error
                }
                withContext(Dispatchers.IO) {
                    runCatching { imageStorage.deleteIfOwned(imagePath) }
                        .onFailure { error ->
                            Log.w(TAG, "Failed to delete replaced moment image: $imagePath", error)
                        }
                }
            }
            val error = outcome.exceptionOrNull()
            if (error is CancellationException) throw error
            onResult(error?.message ?: error?.javaClass?.simpleName)
        }
    }

    private suspend fun legacyRegenerationDraft(
        post: MomentPost,
        imagePath: String
    ): NovelAiImageRegenerationDraft {
        val baseCaption = post.imagePrompt.trim()
        require(baseCaption.isNotBlank()) { "该图片不含可复用的 NovelAI 元数据" }
        val (width, height) = withContext(Dispatchers.IO) {
            val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            BitmapFactory.decodeFile(imagePath, options)
            require(options.outWidth > 0 && options.outHeight > 0) { "无法读取原图尺寸" }
            options.outWidth to options.outHeight
        }
        val sizePreset = when {
            width == height -> NovelAiImageSizePreset.SQUARE
            width > height -> NovelAiImageSizePreset.HORIZONTAL
            else -> NovelAiImageSizePreset.PORTRAIT
        }
        val prompt = NovelAiPromptPlan(
            baseCaption = baseCaption,
            characterCaptions = emptyList(),
            sizePreset = sizePreset
        )
        return NovelAiImageRegenerationDraft(
            baseCaption = baseCaption,
            characterPrompts = emptyList(),
            negativePrompt = prompt.effectiveNegativePrompt,
            sizePreset = sizePreset.name,
            width = width,
            height = height
        )
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
                require(model.hasConfiguredAuthentication(settings)) { "默认对话模型/API Key 未配置" }
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
                        allowCleartextModelApi = settings.allowCleartextModelApi,
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
