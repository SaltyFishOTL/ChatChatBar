package com.example.chatbar.domain.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class NovelAiHistoryDeletionPolicyTest {
    @Test
    fun removesSelectedImagesAcrossBatchesAndDropsEmptyBatch() {
        val first = history("first", "a.png", "b.png")
        val second = history("second", "c.png")

        val result = NovelAiHistoryDeletionPolicy.apply(
            entries = listOf(first, second),
            selections = listOf(
                NovelAiHistoryImageSelection("first", "a.png"),
                NovelAiHistoryImageSelection("second", "c.png")
            )
        )

        assertEquals(listOf("b.png"), result.getValue("first")?.images?.map { it.path })
        assertNull(result.getValue("second"))
    }

    private fun history(id: String, vararg paths: String) = NovelAiGenerationHistoryEntry(
        id = id,
        images = paths.map { NovelAiGenerationHistoryImage(path = it) }
    )
}
