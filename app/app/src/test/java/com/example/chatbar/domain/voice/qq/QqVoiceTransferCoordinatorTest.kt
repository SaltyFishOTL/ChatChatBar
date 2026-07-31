package com.example.chatbar.domain.voice.qq

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class QqVoiceTransferCoordinatorTest {
    private val request = QqVoiceTransferRequest(
        voiceId = "voice-1",
        audioPath = "ignored-by-state-machine.mp3",
        sourceDurationMs = 49_000L,
        clipDurationMs = 49_000L
    )

    @Test
    fun `legal transfer reaches completed`() {
        val coordinator = QqVoiceTransferCoordinator()

        assertTrue(coordinator.prepare(request))
        assertTrue(coordinator.markWaitingForQq())
        assertTrue(coordinator.markCountdown(3))
        assertTrue(coordinator.markCountdown(2))
        assertTrue(coordinator.markCountdown(1))
        assertTrue(coordinator.markRecording())
        assertTrue(coordinator.complete())

        val state = coordinator.state.value as QqVoiceTransferState.Completed
        assertSame(request, state.request)
    }

    @Test
    fun `duplicate start is rejected without replacing request`() {
        val coordinator = QqVoiceTransferCoordinator()
        val other = request.copy(voiceId = "voice-2")

        assertTrue(coordinator.prepare(request))
        assertFalse(coordinator.prepare(other))
        assertSame(request, coordinator.state.value.request)
    }

    @Test
    fun `countdown cancellation cannot enter recording`() {
        val coordinator = coordinatorAtCountdown()

        assertTrue(coordinator.cancel())
        assertFalse(coordinator.markRecording())
        assertTrue(coordinator.state.value is QqVoiceTransferState.Cancelled)
    }

    @Test
    fun `node timeout fails before recording and has no fallback`() {
        assertFailureBeforeRecording(
            QqVoiceTransferFailureCode.QQ_TARGET_TIMEOUT,
            coordinatorAtWaiting()
        )
    }

    @Test
    fun `player failure fails before recording and has no fallback`() {
        assertFailureBeforeRecording(
            QqVoiceTransferFailureCode.PLAYBACK_FAILED,
            QqVoiceTransferCoordinator().also { it.prepare(request) }
        )
    }

    @Test
    fun `leaving QQ fails countdown and has no fallback`() {
        assertFailureBeforeRecording(
            QqVoiceTransferFailureCode.QQ_LEFT_FOREGROUND,
            coordinatorAtCountdown()
        )
    }

    @Test
    fun `gesture cancellation ends recording as failure`() {
        val coordinator = coordinatorAtCountdown()
        assertTrue(coordinator.markRecording())
        val failure = failure(QqVoiceTransferFailureCode.GESTURE_CANCELLED)

        assertTrue(coordinator.fail(failure))
        assertFalse(coordinator.complete())
        assertEquals(
            QqVoiceTransferFailureCode.GESTURE_CANCELLED,
            (coordinator.state.value as QqVoiceTransferState.Failed).failure.code
        )
    }

    private fun coordinatorAtWaiting() = QqVoiceTransferCoordinator().also {
        it.prepare(request)
        it.markWaitingForQq()
    }

    private fun coordinatorAtCountdown() = coordinatorAtWaiting().also {
        it.markCountdown(3)
    }

    private fun assertFailureBeforeRecording(
        code: QqVoiceTransferFailureCode,
        coordinator: QqVoiceTransferCoordinator
    ) {
        assertTrue(coordinator.fail(failure(code)))
        assertFalse(coordinator.markRecording())
        val state = coordinator.state.value as QqVoiceTransferState.Failed
        assertEquals(code, state.failure.code)
    }

    private fun failure(code: QqVoiceTransferFailureCode) =
        QqVoiceTransferPolicy.failure(code, "stable failure")
}
