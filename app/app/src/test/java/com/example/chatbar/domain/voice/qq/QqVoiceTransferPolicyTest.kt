package com.example.chatbar.domain.voice.qq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test

class QqVoiceTransferPolicyTest {
    private val ready = QqVoicePreflightSnapshot(
        fileReady = true,
        durationValid = true,
        qqInstalled = true,
        accessibilityEnabled = true,
        notificationsEnabled = true,
        externalAudioRouteConnected = false,
        mediaVolumeAudible = true
    )

    @Test
    fun `clip keeps 49 seconds and 50 second boundary`() {
        assertEquals(49_000L, QqVoiceTransferPolicy.clipDuration(49_000L))
        assertEquals(50_000L, QqVoiceTransferPolicy.clipDuration(50_000L))
    }

    @Test
    fun `clip discards everything after first 50 seconds`() {
        assertEquals(50_000L, QqVoiceTransferPolicy.clipDuration(50_001L))
        assertEquals(50_000L, QqVoiceTransferPolicy.clipDuration(90_000L))
    }

    @Test
    fun `every preflight error maps to stable code`() {
        val cases = listOf(
            ready.copy(fileReady = false) to QqVoiceTransferFailureCode.AUDIO_FILE_UNAVAILABLE,
            ready.copy(durationValid = false) to QqVoiceTransferFailureCode.AUDIO_DURATION_INVALID,
            ready.copy(qqInstalled = false) to QqVoiceTransferFailureCode.QQ_NOT_INSTALLED,
            ready.copy(accessibilityEnabled = false) to QqVoiceTransferFailureCode.ACCESSIBILITY_DISABLED,
            ready.copy(notificationsEnabled = false) to QqVoiceTransferFailureCode.NOTIFICATIONS_DISABLED,
            ready.copy(externalAudioRouteConnected = true) to QqVoiceTransferFailureCode.EXTERNAL_AUDIO_ROUTE,
            ready.copy(mediaVolumeAudible = false) to QqVoiceTransferFailureCode.MEDIA_VOLUME_MUTED
        )

        cases.forEach { (snapshot, expected) ->
            assertEquals(expected, QqVoiceTransferPolicy.evaluatePreflight(snapshot)?.code)
        }
        assertNull(QqVoiceTransferPolicy.evaluatePreflight(ready))
    }

    @Test
    fun `failure log excludes message paths and private text`() {
        val secret = "D:/private/audio.mp3 联系人小明 聊天文字"
        val log = QqVoiceTransferPolicy.safeFailureLog(
            QqVoiceTransferFailure(QqVoiceTransferFailureCode.PLAYBACK_FAILED, secret)
        )

        assertEquals("QQ voice transfer failed: PLAYBACK_FAILED", log)
        assertFalse(log.contains(secret))
        assertFalse(log.contains("audio.mp3"))
        assertFalse(log.contains("小明"))
    }
}
