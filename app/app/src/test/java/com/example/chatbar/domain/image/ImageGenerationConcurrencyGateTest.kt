package com.example.chatbar.domain.image

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ImageGenerationConcurrencyGateTest {
    @Test
    fun `third task waits until one of two running tasks finishes`() = runTest {
        val gate = ImageGenerationConcurrencyGate(maxParallel = 2)
        val started = Channel<Int>(Channel.UNLIMITED)
        val releases = List(3) { CompletableDeferred<Unit>() }
        val jobs = List(3) { index ->
            async {
                gate.run {
                    started.send(index)
                    releases[index].await()
                }
            }
        }

        runCurrent()
        assertEquals(listOf(0, 1), listOf(started.receive(), started.receive()))
        assertTrue(started.tryReceive().isFailure)

        releases[0].complete(Unit)
        runCurrent()
        assertEquals(2, started.receive())

        releases[1].complete(Unit)
        releases[2].complete(Unit)
        jobs.forEach { it.await() }
    }

    @Test
    fun `cancelled queued task never starts`() = runTest {
        val gate = ImageGenerationConcurrencyGate(maxParallel = 2)
        val started = Channel<Int>(Channel.UNLIMITED)
        val release = CompletableDeferred<Unit>()
        val first = async {
            gate.run {
                started.send(0)
                release.await()
            }
        }
        val second = async {
            gate.run {
                started.send(1)
                release.await()
            }
        }
        val queued = async { gate.run { started.send(2) } }

        runCurrent()
        assertEquals(setOf(0, 1), setOf(started.receive(), started.receive()))
        queued.cancel()
        release.complete(Unit)
        runCurrent()

        assertTrue(started.tryReceive().isFailure)
        first.await()
        second.await()
    }
}
