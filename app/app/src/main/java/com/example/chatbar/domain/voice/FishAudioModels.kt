package com.example.chatbar.domain.voice

import com.example.chatbar.data.local.entity.FishAudioVoiceBinding
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

enum class FishAudioLibrary {
    COMMUNITY,
    MINE
}

enum class FishAudioModelSort(val apiValue: String) {
    RECOMMENDED("score"),
    POPULAR("task_count"),
    LATEST("created_at")
}

data class FishAudioModelQuery(
    val library: FishAudioLibrary = FishAudioLibrary.COMMUNITY,
    val pageSize: Int = 20,
    val pageNumber: Int = 1,
    val title: String = "",
    val tags: List<String> = emptyList(),
    val languages: List<String> = emptyList(),
    val sort: FishAudioModelSort = FishAudioModelSort.RECOMMENDED
)

@Serializable
data class FishAudioModelPage(
    val total: Int = 0,
    val items: List<FishAudioModel> = emptyList(),
    @SerialName("has_more")
    val hasMore: Boolean? = null
)

@Serializable
data class FishAudioModel(
    @SerialName("_id")
    val id: String,
    val title: String,
    val tags: List<String> = emptyList(),
    val author: FishAudioAuthor? = null,
    @SerialName("cover_image")
    val coverImage: String = "",
    val samples: List<FishAudioSample> = emptyList(),
    val languages: List<String> = emptyList(),
    val visibility: String? = null,
    val state: String? = null,
    val type: String? = null,
    @SerialName("task_count")
    val taskCount: Int = 0,
    @SerialName("created_at")
    val createdAt: String? = null
) {
    fun toBinding(): FishAudioVoiceBinding {
        val sample = samples.firstOrNull { it.previewUrl.isNotBlank() }
        return FishAudioVoiceBinding(
            referenceId = id,
            title = title,
            authorId = author?.id,
            authorName = author?.nickname,
            coverImage = coverImage.takeIf(String::isNotBlank),
            sampleAudio = sample?.previewUrl,
            sampleText = sample?.text?.takeIf(String::isNotBlank),
            visibility = visibility,
            languages = languages,
            tags = tags
        )
    }
}

@Serializable
data class FishAudioAuthor(
    @SerialName("_id")
    val id: String? = null,
    val nickname: String? = null,
    val avatar: String? = null
)

@Serializable
data class FishAudioSample(
    val title: String = "",
    val text: String = "",
    val audio: String = "",
    @SerialName("audio_url")
    val audioUrl: String = ""
) {
    val previewUrl: String
        get() = audio.takeIf(String::isNotBlank) ?: audioUrl
}

data class FishAudioStoredAudio(
    val path: String,
    val durationMs: Long,
    val byteLength: Long
)

sealed interface FishAudioDownloadProgress {
    data class Downloading(val bytesReceived: Long, val contentLength: Long?) : FishAudioDownloadProgress
    data class Complete(val audio: FishAudioStoredAudio) : FishAudioDownloadProgress
}

class FishAudioApiException(
    val statusCode: Int?,
    message: String,
    cause: Throwable? = null
) : IllegalStateException(message, cause)

object FishAudioTtsModels {
    const val S1 = "s1"
    const val S2_PRO = "s2-pro"
    const val S2_1_PRO = "s2.1-pro"
    const val S2_1_PRO_FREE = "s2.1-pro-free"

    val supported = listOf(S2_1_PRO_FREE, S2_1_PRO, S2_PRO, S1)
}
