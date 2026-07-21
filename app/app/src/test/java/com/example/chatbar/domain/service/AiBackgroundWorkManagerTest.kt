package com.example.chatbar.domain.service

import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Test

class AiBackgroundWorkManagerTest {
    @Test
    fun releaseBeforeForegroundReadyDefersServiceStop() {
        val ready = CompletableDeferred<Unit>()
        var stopCount = 0
        var failedStartCleanupCount = 0

        releaseForegroundServiceWhenReady(
            ready = ready,
            stopStartedService = { stopCount += 1 },
            clearFailedStartNotification = { failedStartCleanupCount += 1 }
        )

        assertEquals(0, stopCount)
        ready.complete(Unit)

        assertEquals(1, stopCount)
        assertEquals(0, failedStartCleanupCount)
    }

    @Test
    fun releaseAfterForegroundReadyStopsServiceImmediately() {
        val ready = CompletableDeferred(Unit)
        var stopCount = 0

        releaseForegroundServiceWhenReady(
            ready = ready,
            stopStartedService = { stopCount += 1 },
            clearFailedStartNotification = { error("Unexpected failed-start cleanup") }
        )

        assertEquals(1, stopCount)
    }

    @Test
    fun failedForegroundStartOnlyClearsNotification() {
        val ready = CompletableDeferred<Unit>()
        var stopCount = 0
        var failedStartCleanupCount = 0

        releaseForegroundServiceWhenReady(
            ready = ready,
            stopStartedService = { stopCount += 1 },
            clearFailedStartNotification = { failedStartCleanupCount += 1 }
        )
        ready.completeExceptionally(IllegalStateException("start failed"))

        assertEquals(0, stopCount)
        assertEquals(1, failedStartCleanupCount)
    }
}
