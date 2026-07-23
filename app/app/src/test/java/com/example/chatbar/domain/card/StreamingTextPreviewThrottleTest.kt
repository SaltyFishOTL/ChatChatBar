package com.example.chatbar.domain.card

import org.junit.Assert.assertEquals
import org.junit.Test

class StreamingTextPreviewThrottleTest {
    @Test
    fun `rapid updates are coalesced and final text is always published`() {
        var now = 0L
        val snapshots = mutableListOf<String>()
        val throttle = StreamingTextPreviewThrottle(
            onSnapshot = snapshots::add,
            minIntervalNanos = 200L,
            nowNanos = { now }
        )
        val text = StringBuilder("a")

        throttle.publishIfDue(text)
        now = 50L
        text.append("b")
        throttle.publishIfDue(text)
        now = 199L
        text.append("c")
        throttle.publishIfDue(text)
        throttle.publishFinal(text.toString())

        assertEquals(listOf("a", "abc"), snapshots)
    }

    @Test
    fun `update is published after interval and duplicate final is skipped`() {
        var now = 0L
        val snapshots = mutableListOf<String>()
        val throttle = StreamingTextPreviewThrottle(
            onSnapshot = snapshots::add,
            minIntervalNanos = 200L,
            nowNanos = { now }
        )
        val text = StringBuilder("a")

        throttle.publishIfDue(text)
        now = 200L
        text.append("b")
        throttle.publishIfDue(text)
        throttle.publishFinal(text.toString())

        assertEquals(listOf("a", "ab"), snapshots)
    }
}
