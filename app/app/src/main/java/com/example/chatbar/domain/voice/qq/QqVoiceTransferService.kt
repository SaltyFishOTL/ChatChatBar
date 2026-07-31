package com.example.chatbar.domain.voice.qq

import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.IBinder
import android.util.Log
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.example.chatbar.ChatBarApp
import java.io.File
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

class QqVoiceTransferService : Service() {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var coordinator: QqVoiceTransferCoordinator
    private lateinit var gestureGateway: QqVoiceGestureGateway
    private lateinit var preflight: QqVoiceTransferPreflight
    private lateinit var player: ExoPlayer
    private var currentRequest: QqVoiceTransferRequest? = null
    private var transferJob: Job? = null
    private var playerReady = false
    private var playerFailure: QqVoiceTransferFailure? = null
    private var terminal = false

    override fun onCreate() {
        super.onCreate()
        coordinator = ChatBarApp.instance.qqVoiceTransferCoordinator
        gestureGateway = ChatBarApp.instance.qqVoiceGestureGateway
        preflight = QqVoiceTransferPreflight(this, gestureGateway)
        QqVoiceTransferNotificationManager.init(this)
        player = ExoPlayer.Builder(this).build().apply {
            setAudioAttributes(
                AudioAttributes.Builder()
                    .setContentType(C.AUDIO_CONTENT_TYPE_SPEECH)
                    .setUsage(C.USAGE_MEDIA)
                    .build(),
                false
            )
            addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    if (playbackState == Player.STATE_READY && !playerReady) {
                        playerReady = true
                        currentRequest?.let(::showReady)
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    playerReady = false
                    playerFailure = QqVoiceTransferPolicy.failure(
                        QqVoiceTransferFailureCode.PLAYBACK_FAILED,
                        "本地语音播放失败"
                    )
                    if (coordinator.state.value is QqVoiceTransferState.Prepared) {
                        finishFailure(playerFailure!!)
                    }
                }
            })
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_PREPARE -> handlePrepare(intent)
            ACTION_BEGIN -> handleBegin()
            ACTION_CANCEL -> handleCancel()
            else -> stopSelf(startId)
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun handlePrepare(intent: Intent) {
        val request = intent.toRequest() ?: run {
            startForeground(
                QqVoiceTransferNotificationManager.NOTIFICATION_ID,
                QqVoiceTransferNotificationManager.preparing(this)
            )
            finishFailure(
                QqVoiceTransferPolicy.failure(
                    QqVoiceTransferFailureCode.UNKNOWN,
                    "QQ 语音发送参数无效"
                )
            )
            return
        }
        startForeground(
            QqVoiceTransferNotificationManager.NOTIFICATION_ID,
            QqVoiceTransferNotificationManager.preparing(this)
        )
        if (!coordinator.prepare(request)) {
            Toast.makeText(this, "已有 QQ 语音发送任务", Toast.LENGTH_SHORT).show()
            QqVoiceTransferNotificationManager.notify(
                this,
                QqVoiceTransferNotificationManager.ready(
                    this,
                    coordinator.state.value.request?.sourceDurationMs?.let {
                        it > QqVoiceTransferPolicy.MAX_CLIP_DURATION_MS
                    } == true
                )
            )
            return
        }
        terminal = false
        currentRequest = request
        playerReady = false
        playerFailure = null
        val failure = preflight.evaluate(request)
        if (failure != null) {
            finishFailure(failure)
            return
        }
        preparePlayer(request)
    }

    private fun preparePlayer(request: QqVoiceTransferRequest) {
        val mediaItem = MediaItem.Builder()
            .setMediaId(request.voiceId)
            .setUri(Uri.fromFile(File(request.audioPath)))
            .setClippingConfiguration(
                MediaItem.ClippingConfiguration.Builder()
                    .setStartPositionMs(0L)
                    .setEndPositionMs(request.clipDurationMs)
                    .build()
            )
            .build()
        player.stop()
        player.clearMediaItems()
        player.setMediaItem(mediaItem)
        player.prepare()
    }

    private fun showReady(request: QqVoiceTransferRequest) {
        if (terminal || coordinator.state.value !is QqVoiceTransferState.Prepared) return
        QqVoiceTransferNotificationManager.notify(
            this,
            QqVoiceTransferNotificationManager.ready(
                this,
                request.sourceDurationMs > request.clipDurationMs
            )
        )
    }

    private fun handleBegin() {
        val request = currentRequest ?: run {
            finishFailure(
                QqVoiceTransferPolicy.failure(
                    QqVoiceTransferFailureCode.SERVICE_STOPPED,
                    "QQ 语音准备任务已失效，请从 ChatBar 重新发起"
                )
            )
            return
        }
        if (transferJob?.isActive == true) return
        val failure = preflight.evaluate(request)
        if (failure != null) {
            finishFailure(failure)
            return
        }
        if (!playerReady || player.playbackState != Player.STATE_READY) {
            finishFailure(
                QqVoiceTransferPolicy.failure(
                    QqVoiceTransferFailureCode.PLAYER_NOT_READY,
                    "播放器尚未准备完成，请重新发起 QQ 语音发送"
                )
            )
            return
        }
        if (!coordinator.markWaitingForQq()) return
        QqVoiceTransferNotificationManager.notify(
            this,
            QqVoiceTransferNotificationManager.waiting(this)
        )
        transferJob = scope.launch { runTransfer(request) }
    }

    private suspend fun runTransfer(request: QqVoiceTransferRequest) {
        val initialTarget = withTimeoutOrNull(QqVoiceTransferPolicy.QQ_TARGET_TIMEOUT_MS) {
            var target: QqVoiceGestureTarget? = null
            while (isActive && target == null) {
                target = gestureGateway.locateTarget()
                if (target == null) delay(TARGET_POLL_MS)
            }
            target
        }
        if (initialTarget == null) {
            finishFailure(
                QqVoiceTransferPolicy.failure(
                    QqVoiceTransferFailureCode.QQ_TARGET_TIMEOUT,
                    "未检测到 QQ 的“按住说话”录音键；请确认 QQ 版本和当前界面"
                )
            )
            return
        }

        Toast.makeText(this, "3 秒后自动发送，请保持 QQ 在前台", Toast.LENGTH_LONG).show()
        for (seconds in QqVoiceTransferPolicy.COUNTDOWN_SECONDS downTo 1) {
            if (!gestureGateway.isQqForeground() || gestureGateway.locateTarget() == null) {
                finishFailure(
                    QqVoiceTransferPolicy.failure(
                        QqVoiceTransferFailureCode.QQ_LEFT_FOREGROUND,
                        "倒计时期间离开了 QQ 录音界面"
                    )
                )
                return
            }
            if (!coordinator.markCountdown(seconds)) {
                finishInvalidTransition("倒计时状态已失效")
                return
            }
            QqVoiceTransferNotificationManager.notify(
                this,
                QqVoiceTransferNotificationManager.countdown(this, seconds)
            )
            delay(1_000L)
        }

        val finalPreflight = preflight.evaluate(request)
        if (finalPreflight != null) {
            finishFailure(finalPreflight)
            return
        }
        val target = gestureGateway.locateTarget()
        if (target == null) {
            finishFailure(
                QqVoiceTransferPolicy.failure(
                    QqVoiceTransferFailureCode.QQ_LEFT_FOREGROUND,
                    "开始发送前离开了 QQ 录音界面"
                )
            )
            return
        }

        player.seekTo(0L)
        val gestureCompletion = CompletableDeferred<QqVoiceGestureCompletion>()
        val holdDuration = request.clipDurationMs +
            QqVoiceTransferPolicy.PRESS_LEAD_MS +
            QqVoiceTransferPolicy.PRESS_TAIL_MS
        val accepted = gestureGateway.pressAndHold(target, holdDuration) { completion ->
            gestureCompletion.complete(completion)
        }
        if (!accepted) {
            finishFailure(
                QqVoiceTransferPolicy.failure(
                    QqVoiceTransferFailureCode.GESTURE_REJECTED,
                    "系统拒绝了 QQ 录音长按手势"
                )
            )
            return
        }
        if (!coordinator.markRecording()) {
            gestureGateway.cancelActiveGesture()
            finishInvalidTransition("录音状态已失效")
            return
        }
        QqVoiceTransferNotificationManager.notify(
            this,
            QqVoiceTransferNotificationManager.recording(this, request.clipDurationMs)
        )

        delay(QqVoiceTransferPolicy.PRESS_LEAD_MS)
        player.play()
        var elapsed = 0L
        var runtimeFailure: QqVoiceTransferFailure? = null
        while (elapsed < request.clipDurationMs && runtimeFailure == null) {
            val step = minOf(PLAYBACK_POLL_MS, request.clipDurationMs - elapsed)
            delay(step)
            elapsed += step
            runtimeFailure = when {
                !gestureGateway.isQqForeground() -> QqVoiceTransferPolicy.failure(
                    QqVoiceTransferFailureCode.QQ_LEFT_FOREGROUND,
                    "发送期间离开了 QQ，录音可能未完成"
                )
                playerFailure != null -> playerFailure
                else -> null
            }
        }
        player.pause()
        delay(QqVoiceTransferPolicy.PRESS_TAIL_MS)
        val completion = withTimeoutOrNull(GESTURE_COMPLETION_GRACE_MS) {
            gestureCompletion.await()
        }
        if (runtimeFailure != null) {
            finishFailure(runtimeFailure)
            return
        }
        if (completion != QqVoiceGestureCompletion.COMPLETED) {
            finishFailure(
                QqVoiceTransferPolicy.failure(
                    QqVoiceTransferFailureCode.GESTURE_CANCELLED,
                    "QQ 录音长按手势被系统取消"
                )
            )
            return
        }
        finishCompleted()
    }

    private fun handleCancel() {
        gestureGateway.cancelActiveGesture()
        transferJob?.cancel()
        transferJob = null
        player.pause()
        coordinator.cancel()
        terminal = true
        QqVoiceTransferNotificationManager.cancel(this)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun finishCompleted() {
        if (!coordinator.complete()) {
            finishInvalidTransition("完成状态已失效")
            return
        }
        terminal = true
        val notification = QqVoiceTransferNotificationManager.terminal(
            this,
            "QQ 语音操作已完成",
            "已松开录音键，请在 QQ 中确认消息是否发送成功"
        )
        Toast.makeText(
            this,
            "已松开录音键，请在 QQ 中确认",
            Toast.LENGTH_LONG
        ).show()
        finishWithTerminalNotification(notification)
    }

    private fun finishInvalidTransition(message: String) {
        val existingFailure = (coordinator.state.value as? QqVoiceTransferState.Failed)?.failure
        finishFailure(
            existingFailure ?: QqVoiceTransferPolicy.failure(
                QqVoiceTransferFailureCode.UNKNOWN,
                message
            )
        )
    }

    private fun finishFailure(failure: QqVoiceTransferFailure) {
        gestureGateway.cancelActiveGesture()
        coordinator.fail(failure)
        terminal = true
        Log.w(TAG, QqVoiceTransferPolicy.safeFailureLog(failure))
        player.pause()
        Toast.makeText(this, failure.message, Toast.LENGTH_LONG).show()
        val notification = QqVoiceTransferNotificationManager.terminal(
            this,
            "QQ 语音发送失败",
            failure.message
        )
        finishWithTerminalNotification(notification)
    }

    private fun finishWithTerminalNotification(notification: android.app.Notification) {
        transferJob?.cancel()
        transferJob = null
        QqVoiceTransferNotificationManager.notify(this, notification)
        stopForeground(STOP_FOREGROUND_DETACH)
        stopSelf()
    }

    override fun onDestroy() {
        transferJob?.cancel()
        player.stop()
        player.clearMediaItems()
        player.release()
        if (!terminal && QqVoiceTransferPolicy.isActive(coordinator.state.value)) {
            coordinator.fail(
                QqVoiceTransferPolicy.failure(
                    QqVoiceTransferFailureCode.SERVICE_STOPPED,
                    "QQ 语音发送服务意外停止"
                )
            )
        }
        scope.cancel()
        super.onDestroy()
    }

    private fun Intent.toRequest(): QqVoiceTransferRequest? {
        val voiceId = getStringExtra(EXTRA_VOICE_ID)?.takeIf(String::isNotBlank) ?: return null
        val audioPath = getStringExtra(EXTRA_AUDIO_PATH)?.takeIf(String::isNotBlank) ?: return null
        val sourceDurationMs = getLongExtra(EXTRA_SOURCE_DURATION_MS, -1L)
        val clipDurationMs = getLongExtra(EXTRA_CLIP_DURATION_MS, -1L)
        if (sourceDurationMs <= 0L || clipDurationMs <= 0L) return null
        return QqVoiceTransferRequest(
            voiceId = voiceId,
            audioPath = audioPath,
            sourceDurationMs = sourceDurationMs,
            clipDurationMs = clipDurationMs
        )
    }

    companion object {
        const val ACTION_PREPARE = "com.example.chatbar.action.PREPARE_QQ_VOICE"
        const val ACTION_BEGIN = "com.example.chatbar.action.BEGIN_QQ_VOICE"
        const val ACTION_CANCEL = "com.example.chatbar.action.CANCEL_QQ_VOICE"
        private const val EXTRA_VOICE_ID = "voiceId"
        private const val EXTRA_AUDIO_PATH = "audioPath"
        private const val EXTRA_SOURCE_DURATION_MS = "sourceDurationMs"
        private const val EXTRA_CLIP_DURATION_MS = "clipDurationMs"
        private const val TARGET_POLL_MS = 200L
        private const val PLAYBACK_POLL_MS = 100L
        private const val GESTURE_COMPLETION_GRACE_MS = 2_000L
        private const val TAG = "QqVoiceTransfer"

        fun start(context: Context, request: QqVoiceTransferRequest) {
            val intent = Intent(context, QqVoiceTransferService::class.java).apply {
                action = ACTION_PREPARE
                putExtra(EXTRA_VOICE_ID, request.voiceId)
                putExtra(EXTRA_AUDIO_PATH, request.audioPath)
                putExtra(EXTRA_SOURCE_DURATION_MS, request.sourceDurationMs)
                putExtra(EXTRA_CLIP_DURATION_MS, request.clipDurationMs)
            }
            ContextCompat.startForegroundService(context, intent)
        }
    }
}
