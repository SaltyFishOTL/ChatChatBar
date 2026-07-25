package com.example.chatbar.domain.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.chatbar.data.local.entity.GeneratedVoiceMessage
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

data class VoicePlaybackState(
    val currentVoiceId: String? = null,
    val isPlaying: Boolean = false,
    val queueVoiceIds: List<String> = emptyList()
)

class VoicePlaybackController(context: Context) {
    private val playerHandler = Handler(Looper.getMainLooper())
    private val player: ExoPlayer = ExoPlayer.Builder(context).build().apply {
        setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                .setUsage(C.USAGE_MEDIA)
                .build(),
            true
        )
    }
    private val _state = MutableStateFlow(VoicePlaybackState())
    val state: StateFlow<VoicePlaybackState> = _state.asStateFlow()

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) = publish(isPlaying)
            override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = publish(player.isPlaying)
            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    _state.value = VoicePlaybackState()
                } else {
                    publish(player.isPlaying)
                }
            }
        })
    }

    fun playSingle(voice: GeneratedVoiceMessage) {
        runOnPlayerThread {
            playSequenceOnPlayerThread(listOf(voice))
        }
    }

    fun playSequence(voices: List<GeneratedVoiceMessage>) {
        runOnPlayerThread {
            playSequenceOnPlayerThread(voices)
        }
    }

    private fun playSequenceOnPlayerThread(voices: List<GeneratedVoiceMessage>) {
        val playable = voices.filter { File(it.audioPath).isFile }
        if (playable.isEmpty()) {
            stopOnPlayerThread()
            return
        }
        player.stop()
        player.clearMediaItems()
        playable.forEach { voice ->
            player.addMediaItem(
                MediaItem.Builder()
                    .setMediaId(voice.id)
                    .setUri(File(voice.audioPath).toURI().toString())
                    .build()
            )
        }
        _state.value = VoicePlaybackState(
            currentVoiceId = playable.first().id,
            isPlaying = false,
            queueVoiceIds = playable.map(GeneratedVoiceMessage::id)
        )
        player.prepare()
        player.play()
    }

    fun enqueueSequence(voices: List<GeneratedVoiceMessage>) {
        runOnPlayerThread {
            enqueueSequenceOnPlayerThread(voices)
        }
    }

    private fun enqueueSequenceOnPlayerThread(voices: List<GeneratedVoiceMessage>) {
        val playable = voices.filter { File(it.audioPath).isFile }
        if (playable.isEmpty()) return
        if (player.mediaItemCount == 0 || player.playbackState == Player.STATE_ENDED) {
            playSequenceOnPlayerThread(playable)
            return
        }
        playable.forEach { voice ->
            player.addMediaItem(
                MediaItem.Builder()
                    .setMediaId(voice.id)
                    .setUri(File(voice.audioPath).toURI().toString())
                    .build()
            )
        }
        publish(player.isPlaying)
    }

    fun playPreview(path: String) {
        runOnPlayerThread {
            playPreviewOnPlayerThread(path)
        }
    }

    private fun playPreviewOnPlayerThread(path: String) {
        val file = File(path)
        if (!file.isFile) return
        player.stop()
        player.clearMediaItems()
        player.setMediaItem(
            MediaItem.Builder()
                .setMediaId(PREVIEW_MEDIA_ID)
                .setUri(file.toURI().toString())
                .build()
        )
        _state.value = VoicePlaybackState(currentVoiceId = PREVIEW_MEDIA_ID)
        player.prepare()
        player.play()
    }

    fun stop() {
        runOnPlayerThread(::stopOnPlayerThread)
    }

    private fun stopOnPlayerThread() {
        player.stop()
        player.clearMediaItems()
        _state.value = VoicePlaybackState()
    }

    private fun runOnPlayerThread(action: () -> Unit) {
        if (Looper.myLooper() == playerHandler.looper) {
            action()
        } else {
            playerHandler.post(action)
        }
    }

    private fun publish(isPlaying: Boolean) {
        val ids = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }
        _state.value = VoicePlaybackState(
            currentVoiceId = player.currentMediaItem?.mediaId,
            isPlaying = isPlaying,
            queueVoiceIds = ids
        )
    }

    private companion object {
        const val PREVIEW_MEDIA_ID = "__fish_preview__"
    }
}
