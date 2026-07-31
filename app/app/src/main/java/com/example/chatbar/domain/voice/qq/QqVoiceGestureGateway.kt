package com.example.chatbar.domain.voice.qq

data class QqVoiceGestureTarget(
    val centerX: Float,
    val centerY: Float
)

enum class QqVoiceGestureCompletion {
    COMPLETED,
    CANCELLED
}

interface QqVoiceGestureGateway {
    val connected: Boolean

    fun isQqForeground(): Boolean

    fun locateTarget(): QqVoiceGestureTarget?

    fun pressAndHold(
        target: QqVoiceGestureTarget,
        durationMs: Long,
        onCompletion: (QqVoiceGestureCompletion) -> Unit
    ): Boolean

    fun cancelActiveGesture(): Boolean
}

internal interface QqVoiceGestureDelegate : QqVoiceGestureGateway

class QqVoiceGestureGatewayRegistry : QqVoiceGestureGateway {
    @Volatile
    private var delegate: QqVoiceGestureDelegate? = null

    override val connected: Boolean
        get() = delegate?.connected == true

    override fun isQqForeground(): Boolean = delegate?.isQqForeground() == true

    override fun locateTarget(): QqVoiceGestureTarget? = delegate?.locateTarget()

    override fun pressAndHold(
        target: QqVoiceGestureTarget,
        durationMs: Long,
        onCompletion: (QqVoiceGestureCompletion) -> Unit
    ): Boolean = delegate?.pressAndHold(target, durationMs, onCompletion) == true

    override fun cancelActiveGesture(): Boolean = delegate?.cancelActiveGesture() == true

    @Synchronized
    internal fun attach(service: QqVoiceGestureDelegate) {
        delegate = service
    }

    @Synchronized
    internal fun detach(service: QqVoiceGestureDelegate) {
        if (delegate === service) delegate = null
    }
}
