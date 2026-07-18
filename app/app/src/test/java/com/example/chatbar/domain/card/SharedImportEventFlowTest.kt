package com.example.chatbar.domain.card

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedImportEventFlowTest {
    @Test
    fun claimedEventCannotBeClaimedAgain() {
        val events = SharedImportEventFlow<String>()
        events.publish("card.png")
        val event = requireNotNull(events.events.value)

        assertTrue(events.claim(event.id))
        assertFalse(events.claim(event.id))
        assertTrue(events.events.value?.claimed == true)

        assertTrue(events.complete(event.id))
        assertNull(events.events.value)
    }

    @Test
    fun staleCompletionDoesNotClearNewShareOfSameUri() {
        val events = SharedImportEventFlow<String>()
        events.publish("card.png")
        val firstId = requireNotNull(events.events.value).id
        assertTrue(events.claim(firstId))

        events.publish("card.png")
        val second = requireNotNull(events.events.value)

        assertFalse(events.complete(firstId))
        assertEquals("card.png", events.events.value?.value)
        assertEquals(second.id, events.events.value?.id)
        assertTrue(events.claim(second.id))
    }
}
