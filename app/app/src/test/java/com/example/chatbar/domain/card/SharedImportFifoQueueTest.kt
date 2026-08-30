package com.example.chatbar.domain.card

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SharedImportFifoQueueTest {
    @Test
    fun preservesArrivalOrder() {
        val queue = SharedImportFifoQueue<String>()
        queue.add("first")
        queue.add("second")
        queue.add("third")

        assertEquals(listOf("first", "second", "third"), queue.snapshot())
        assertTrue(queue.removeFirst("first"))
        assertEquals("second", queue.firstOrNull())
    }

    @Test
    fun staleCompletionCannotRemoveNewHead() {
        val first = Any()
        val second = Any()
        val queue = SharedImportFifoQueue<Any>()
        queue.add(first)
        queue.add(second)

        assertTrue(queue.removeFirst(first))
        assertFalse(queue.removeFirst(first))
        assertEquals(second, queue.firstOrNull())
    }
}
