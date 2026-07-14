package com.example.chatbar.ui.chat

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ImageGenerationTaskStateStoreTest {
    @Test
    fun `task updates and removal do not affect other tasks`() {
        val store = ImageGenerationTaskStateStore()
        val first = state("first", ImageGenerationPhase.GENERATING)
        val second = state("second", ImageGenerationPhase.QUEUED)
        store.put(first)
        store.put(second)

        store.update(first.taskId) {
            it.copy(phase = ImageGenerationPhase.FAILED, error = "boom")
        }

        assertEquals(ImageGenerationPhase.FAILED, store.get(first.taskId)?.phase)
        assertEquals("boom", store.get(first.taskId)?.error)
        assertEquals(second, store.get(second.taskId))

        store.remove(first.taskId)

        assertNull(store.get(first.taskId))
        assertEquals(second, store.get(second.taskId))
    }

    private fun state(taskId: String, phase: ImageGenerationPhase) = ImageGenerationState(
        taskId = taskId,
        anchorMessageId = "anchor-$taskId",
        phase = phase
    )
}
