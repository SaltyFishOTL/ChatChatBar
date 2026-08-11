package com.example.chatbar.ui.imageprompt

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.example.chatbar.ChatBarApp
import com.example.chatbar.domain.image.FullImagePatchOperation
import com.example.chatbar.domain.image.ImageProcessingService
import com.example.chatbar.domain.image.ImportedProcessImage
import com.example.chatbar.domain.image.ProcessedImage
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ImageProcessingPhase {
    IDLE,
    IMPORTING,
    READY,
    PROCESSING,
    FINISHED,
    FAILED,
    CANCELLED
}

data class ImageProcessingUiState(
    val source: ImportedProcessImage? = null,
    val result: ProcessedImage? = null,
    val lastOperation: FullImagePatchOperation? = null,
    val phase: ImageProcessingPhase = ImageProcessingPhase.IDLE,
    val progress: Float = 0f,
    val error: String? = null
) {
    val isBusy: Boolean
        get() = phase == ImageProcessingPhase.IMPORTING || phase == ImageProcessingPhase.PROCESSING
}

class ImageProcessingViewModel : ViewModel() {
    private val service = ImageProcessingService(ChatBarApp.instance)
    private val _uiState = MutableStateFlow(ImageProcessingUiState())
    val uiState: StateFlow<ImageProcessingUiState> = _uiState.asStateFlow()
    private var activeJob: Job? = null

    fun selectImage(uri: Uri) {
        if (_uiState.value.isBusy) return
        activeJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    phase = ImageProcessingPhase.IMPORTING,
                    progress = 0f,
                    error = null
                )
            }
            try {
                val imported = withContext(Dispatchers.IO) { service.importImage(uri) }
                _uiState.update {
                    it.copy(
                        source = imported,
                        result = null,
                        lastOperation = null,
                        phase = ImageProcessingPhase.READY,
                        progress = 0f,
                        error = null
                    )
                }
            } catch (error: CancellationException) {
                _uiState.update { it.copy(phase = ImageProcessingPhase.CANCELLED, error = null) }
                throw error
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        phase = ImageProcessingPhase.FAILED,
                        error = "读取图片失败：${error.message ?: "未知错误"}"
                    )
                }
            }
        }
    }

    fun process(operation: FullImagePatchOperation) {
        val source = _uiState.value.source ?: return
        if (_uiState.value.isBusy) return
        activeJob = viewModelScope.launch {
            _uiState.update {
                it.copy(
                    result = null,
                    lastOperation = operation,
                    phase = ImageProcessingPhase.PROCESSING,
                    progress = 0f,
                    error = null
                )
            }
            try {
                val result = withContext(Dispatchers.IO) {
                    service.process(source.path, operation) { progress ->
                        _uiState.update { it.copy(progress = progress) }
                    }
                }
                _uiState.update {
                    it.copy(
                        result = result,
                        phase = ImageProcessingPhase.FINISHED,
                        progress = 1f,
                        error = null
                    )
                }
            } catch (error: CancellationException) {
                _uiState.update { it.copy(phase = ImageProcessingPhase.CANCELLED, error = null) }
                throw error
            } catch (error: Throwable) {
                _uiState.update {
                    it.copy(
                        phase = ImageProcessingPhase.FAILED,
                        error = "图片处理失败：${error.message ?: "未知错误"}"
                    )
                }
            }
        }
    }

    fun cancelActiveTask() {
        if (!_uiState.value.isBusy) return
        activeJob?.cancel(CancellationException("用户停止图像处理"))
        _uiState.update { it.copy(phase = ImageProcessingPhase.CANCELLED, error = null) }
    }

    fun dismissError() {
        _uiState.update {
            it.copy(
                phase = if (it.source == null) ImageProcessingPhase.IDLE else ImageProcessingPhase.READY,
                error = null
            )
        }
    }

    override fun onCleared() {
        activeJob?.cancel()
        super.onCleared()
    }
}
