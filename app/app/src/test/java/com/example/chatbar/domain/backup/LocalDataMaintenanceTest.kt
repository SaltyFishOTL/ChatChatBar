package com.example.chatbar.domain.backup

import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import org.junit.Assert.*
import org.junit.Test

class LocalDataMaintenanceTest {
    @Test fun waitsForShortWriteAndRejectsNewMutationsWhileExclusive() = runTest {
        val write = LocalDataMaintenance.enter()
        val lease = async { LocalDataMaintenance.acquire() }
        delay(40)
        assertFalse(lease.isCompleted)
        write.close()
        lease.await().use {
            assertTrue(LocalDataMaintenance.blocked.value)
            assertThrows(IllegalStateException::class.java) { LocalDataMaintenance.enter() }
        }
        assertFalse(LocalDataMaintenance.blocked.value)
        LocalDataMaintenance.enter().close()
    }

    @Test fun cancellationAndExceptionsReleaseAdmission() = runTest {
        assertThrows(IllegalArgumentException::class.java) {
            LocalDataMaintenance.access { throw IllegalArgumentException("synthetic failure") }
        }
        LocalDataMaintenance.acquire().close()
        val write = LocalDataMaintenance.enter()
        val waiting = async { LocalDataMaintenance.acquire() }
        delay(40)
        waiting.cancel()
        waiting.join()
        write.close()
        LocalDataMaintenance.acquire().close()
        assertFalse(LocalDataMaintenance.blocked.value)
    }

    @Test fun activeMemoryTaskBlocksSnapshotWithoutBeingCancelledAndMaintenanceRejectsNewTask() = runTest {
        val registry = com.example.chatbar.domain.memory.SessionScopedJobRegistry(this)
        val job = requireNotNull(registry.launch("fixture") { awaitCancellation() })
        val failure = runCatching { LocalDataMaintenance.acquire() }.exceptionOrNull()
        assertTrue(failure is IllegalStateException)
        assertTrue(job.isActive)
        job.cancel()
        job.join()
        LocalDataMaintenance.acquire().use {
            assertNull(registry.launch("new") {})
        }
        assertFalse(LocalDataMaintenance.blocked.value)
    }
}
