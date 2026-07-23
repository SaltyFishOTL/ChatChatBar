package com.example.chatbar.domain.card

internal class StreamingTextPreviewThrottle(
    private val onSnapshot: (String) -> Unit,
    private val minIntervalNanos: Long = DEFAULT_MIN_INTERVAL_NANOS,
    private val nowNanos: () -> Long = System::nanoTime
) {
    private var lastPublishedAtNanos: Long? = null
    private var lastPublishedLength: Int = 0

    init {
        require(minIntervalNanos >= 0L)
    }

    fun publishIfDue(text: StringBuilder) {
        if (text.isEmpty()) return
        val now = nowNanos()
        val lastPublishedAt = lastPublishedAtNanos
        if (lastPublishedAt != null && now - lastPublishedAt < minIntervalNanos) return
        publish(text.toString(), now)
    }

    fun publishFinal(text: String) {
        if (text.isEmpty() || text.length == lastPublishedLength) return
        publish(text, nowNanos())
    }

    private fun publish(text: String, now: Long) {
        onSnapshot(text)
        lastPublishedAtNanos = now
        lastPublishedLength = text.length
    }

    private companion object {
        const val DEFAULT_MIN_INTERVAL_NANOS = 200_000_000L
    }
}
