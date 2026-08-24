package com.example.chatbar.domain.memory

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MemoryMaintenanceMailboxTest {
    @Test
    fun demandArrivingDuringPassRequiresAnotherPass() {
        val mailbox = MemoryMaintenanceMailbox()

        assertTrue(mailbox.request("session"))
        val runningVersion = mailbox.versionToProcess("session")
        assertFalse(mailbox.request("session"))

        assertTrue(mailbox.completePass("session", runningVersion))
        val followUpVersion = mailbox.versionToProcess("session")
        assertFalse(mailbox.completePass("session", followUpVersion))
    }

    @Test
    fun stopBoundaryCannotLoseNextDemand() {
        val mailbox = MemoryMaintenanceMailbox()

        assertTrue(mailbox.request("session"))
        assertFalse(mailbox.completePass("session", mailbox.versionToProcess("session")))
        assertTrue(mailbox.request("session"))
    }
}
