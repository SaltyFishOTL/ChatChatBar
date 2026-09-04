package com.example.chatbar.domain.image

import org.junit.Assert.assertEquals
import org.junit.Test

class NovelAiImageModelResolutionTest {
    @Test
    fun `explicit override wins over character and global defaults`() {
        assertEquals(
            NovelAiImageModel.V4_5_FULL,
            NovelAiImageModelResolution.resolve(
                explicitOverride = NovelAiImageModel.V4_5_FULL,
                characterDefault = NovelAiImageModel.V5_FULL,
                globalDefault = NovelAiImageModel.V5_FULL
            )
        )
    }

    @Test
    fun `character default wins when explicit override is absent`() {
        assertEquals(
            NovelAiImageModel.V5_FULL,
            NovelAiImageModelResolution.resolve(
                explicitOverride = null,
                characterDefault = NovelAiImageModel.V5_FULL,
                globalDefault = NovelAiImageModel.V4_5_FULL
            )
        )
    }

    @Test
    fun `global default is final fallback`() {
        assertEquals(
            NovelAiImageModel.V4_5_FULL,
            NovelAiImageModelResolution.resolve(
                explicitOverride = null,
                characterDefault = null,
                globalDefault = NovelAiImageModel.V4_5_FULL
            )
        )
    }
}
