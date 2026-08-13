package com.example.chatbar.domain.memory

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionScopedJobRegistryTest {
    @Test
    fun `session deletion cancels and joins active work then rejects new work`() = runTest {
        val registry = SessionScopedJobRegistry(this)
        val started = CompletableDeferred<Unit>()
        val finished = CompletableDeferred<Unit>()

        val job = registry.launch("deleted-session") {
            try {
                started.complete(Unit)
                awaitCancellation()
            } finally {
                finished.complete(Unit)
            }
        }
        runCurrent()
        started.await()

        registry.cancelAndJoin("deleted-session")

        assertTrue(job?.isCancelled == true)
        assertTrue(finished.isCompleted)
        assertNull(registry.launch("deleted-session") {})
    }

    @Test
    fun `session deletion leaves other session work running`() = runTest {
        val registry = SessionScopedJobRegistry(this)
        val otherStarted = CompletableDeferred<Unit>()
        val otherJob = registry.launch("other-session") {
            otherStarted.complete(Unit)
            awaitCancellation()
        }
        runCurrent()
        otherStarted.await()

        registry.cancelAndJoin("deleted-session")

        assertTrue(otherJob?.isActive == true)
        otherJob?.cancel()
        otherJob?.join()
    }
}
