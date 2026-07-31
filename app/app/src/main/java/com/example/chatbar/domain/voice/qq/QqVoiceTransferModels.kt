package com.example.chatbar.domain.voice.qq

import com.example.chatbar.data.local.entity.GeneratedVoiceMessage

data class QqVoiceTransferRequest(
    val voiceId: String,
    val audioPath: String,
    val sourceDurationMs: Long,
    val clipDurationMs: Long
) {
    companion object {
        fun from(voice: GeneratedVoiceMessage): QqVoiceTransferRequest = QqVoiceTransferRequest(
            voiceId = voice.id,
            audioPath = voice.audioPath,
            sourceDurationMs = voice.durationMs,
            clipDurationMs = QqVoiceTransferPolicy.clipDuration(voice.durationMs)
        )
    }
}

enum class QqVoiceTransferFailureCode {
    ALREADY_ACTIVE,
    AUDIO_FILE_UNAVAILABLE,
    AUDIO_DURATION_INVALID,
    QQ_NOT_INSTALLED,
    ACCESSIBILITY_DISABLED,
    NOTIFICATIONS_DISABLED,
    EXTERNAL_AUDIO_ROUTE,
    MEDIA_VOLUME_MUTED,
    PLAYER_NOT_READY,
    QQ_TARGET_TIMEOUT,
    QQ_LEFT_FOREGROUND,
    GESTURE_REJECTED,
    GESTURE_CANCELLED,
    PLAYBACK_FAILED,
    SERVICE_STOPPED,
    UNKNOWN
}

data class QqVoiceTransferFailure(
    val code: QqVoiceTransferFailureCode,
    val message: String
)

sealed interface QqVoiceTransferState {
    val request: QqVoiceTransferRequest?

    data object Idle : QqVoiceTransferState {
        override val request: QqVoiceTransferRequest? = null
    }

    data class Prepared(
        override val request: QqVoiceTransferRequest
    ) : QqVoiceTransferState

    data class WaitingForQq(
        override val request: QqVoiceTransferRequest
    ) : QqVoiceTransferState

    data class Countdown(
        override val request: QqVoiceTransferRequest,
        val secondsRemaining: Int
    ) : QqVoiceTransferState

    data class Recording(
        override val request: QqVoiceTransferRequest
    ) : QqVoiceTransferState

    data class Completed(
        override val request: QqVoiceTransferRequest
    ) : QqVoiceTransferState

    data class Failed(
        override val request: QqVoiceTransferRequest?,
        val failure: QqVoiceTransferFailure
    ) : QqVoiceTransferState

    data class Cancelled(
        override val request: QqVoiceTransferRequest?
    ) : QqVoiceTransferState
}

data class QqVoicePreflightSnapshot(
    val fileReady: Boolean,
    val durationValid: Boolean,
    val qqInstalled: Boolean,
    val accessibilityEnabled: Boolean,
    val notificationsEnabled: Boolean,
    val externalAudioRouteConnected: Boolean,
    val mediaVolumeAudible: Boolean
)

object QqVoiceTransferPolicy {
    const val QQ_PACKAGE = "com.tencent.mobileqq"
    const val QQ_PRESS_TO_SPEAK_VIEW_ID = "com.tencent.mobileqq:id/press_to_speak_iv"
    const val MAX_CLIP_DURATION_MS = 50_000L
    const val QQ_TARGET_TIMEOUT_MS = 10_000L
    const val COUNTDOWN_SECONDS = 3
    const val PRESS_LEAD_MS = 500L
    const val PRESS_TAIL_MS = 300L

    fun clipDuration(sourceDurationMs: Long): Long =
        sourceDurationMs.coerceAtLeast(0L).coerceAtMost(MAX_CLIP_DURATION_MS)

    fun evaluatePreflight(snapshot: QqVoicePreflightSnapshot): QqVoiceTransferFailure? = when {
        !snapshot.fileReady -> failure(
            QqVoiceTransferFailureCode.AUDIO_FILE_UNAVAILABLE,
            "语音文件不存在或为空"
        )
        !snapshot.durationValid -> failure(
            QqVoiceTransferFailureCode.AUDIO_DURATION_INVALID,
            "语音时长无效"
        )
        !snapshot.qqInstalled -> failure(
            QqVoiceTransferFailureCode.QQ_NOT_INSTALLED,
            "未安装或未启用标准手机 QQ"
        )
        !snapshot.accessibilityEnabled -> failure(
            QqVoiceTransferFailureCode.ACCESSIBILITY_DISABLED,
            "请先启用 ChatBar 的 QQ 语音发送无障碍服务"
        )
        !snapshot.notificationsEnabled -> failure(
            QqVoiceTransferFailureCode.NOTIFICATIONS_DISABLED,
            "请允许 ChatBar 显示通知，发送操作从通知栏开始"
        )
        snapshot.externalAudioRouteConnected -> failure(
            QqVoiceTransferFailureCode.EXTERNAL_AUDIO_ROUTE,
            "请断开耳机、蓝牙音频或 USB 音频设备，改用手机扬声器"
        )
        !snapshot.mediaVolumeAudible -> failure(
            QqVoiceTransferFailureCode.MEDIA_VOLUME_MUTED,
            "媒体音量为零，请先调高媒体音量"
        )
        else -> null
    }

    fun isActive(state: QqVoiceTransferState): Boolean = when (state) {
        is QqVoiceTransferState.Prepared,
        is QqVoiceTransferState.WaitingForQq,
        is QqVoiceTransferState.Countdown,
        is QqVoiceTransferState.Recording -> true
        else -> false
    }

    fun isTerminal(state: QqVoiceTransferState): Boolean = when (state) {
        is QqVoiceTransferState.Completed,
        is QqVoiceTransferState.Failed,
        is QqVoiceTransferState.Cancelled -> true
        else -> false
    }

    fun failure(code: QqVoiceTransferFailureCode, message: String): QqVoiceTransferFailure =
        QqVoiceTransferFailure(code = code, message = message)

    internal fun safeFailureLog(failure: QqVoiceTransferFailure): String =
        "QQ voice transfer failed: ${failure.code}"
}
