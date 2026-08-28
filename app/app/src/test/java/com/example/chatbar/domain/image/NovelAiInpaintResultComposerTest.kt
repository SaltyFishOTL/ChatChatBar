package com.example.chatbar.domain.image

import org.junit.Assert.assertEquals
import org.junit.Test

class NovelAiInpaintResultComposerTest {
    @Test
    fun transparentGeneratedPixelLeavesOfficialDestinationOutHole() {
        val base = 0xff123456.toInt()
        val transparentGarbage = 0x0000ff00

        assertEquals(0, NovelAiInpaintResultComposer.composeOfficialPixel(base, transparentGarbage, 255))
    }

    @Test
    fun opaqueGeneratedPixelReplacesBaseInsideMask() {
        val base = 0xff123456.toInt()
        val generated = 0xffabcdef.toInt()

        assertEquals(generated, NovelAiInpaintResultComposer.composeOfficialPixel(base, generated, 255))
    }

    @Test
    fun generatedAlphaIsPreservedAfterDestinationOut() {
        val base = 0xff000000.toInt()
        val halfTransparentWhite = 0x80ffffff.toInt()

        assertEquals(
            0x80ffffff.toInt(),
            NovelAiInpaintResultComposer.composeOfficialPixel(base, halfTransparentWhite, 255)
        )
    }
}
