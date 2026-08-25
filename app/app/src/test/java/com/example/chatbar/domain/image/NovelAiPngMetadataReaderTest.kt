package com.example.chatbar.domain.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

class NovelAiPngMetadataReaderTest {
    @Test
    fun `parses NovelAI v4 comment metadata`() {
        val comment = """
            {
              "prompt": "fallback",
              "uc": "lowres",
              "width": 832,
              "height": 1216,
              "model": "nai-diffusion-5-full",
              "steps": 31,
              "scale": 5.5,
              "cfg_rescale": 0.25,
              "sampler": "k_dpmpp_2m",
              "seed": 123456789,
              "n_samples": 3,
              "v4_prompt": {
                "caption": {
                  "base_caption": "1girl, outdoors",
                  "char_captions": [
                    {"char_caption": "alice", "centers": [{"x": 0.25, "y": 0.5}]}
                  ]
                }
              },
              "v4_negative_prompt": {
                "caption": {
                  "base_caption": "bad hands",
                  "char_captions": [
                    {"char_caption": "bad alice"}
                  ]
                }
              }
            }
        """.trimIndent()

        val metadata = NovelAiPngMetadataReader.parseComment(comment, "/tmp/old.png")

        assertNotNull(metadata)
        assertEquals("1girl, outdoors", metadata?.baseCaption)
        assertEquals("bad hands", metadata?.negativePrompt)
        assertEquals("alice", metadata?.characterPrompts?.single()?.prompt)
        assertEquals(832, metadata?.width)
        assertEquals(1216, metadata?.height)

        val studio = NovelAiPngMetadataReader.parseStudioComment(comment, "/tmp/old.png")
        assertNotNull(studio)
        assertEquals("bad alice", studio?.characters?.single()?.negativePrompt)
        assertEquals(NovelAiImageModel.V5_FULL, studio?.settings?.model)
        assertEquals(NovelAiSizeTier.NORMAL, studio?.settings?.sizeTier)
        assertEquals(NovelAiAspectRatio.PORTRAIT, studio?.settings?.aspectRatio)
        assertEquals(3, studio?.settings?.count)
        assertEquals(31, studio?.settings?.steps)
        assertEquals(5.5f, studio?.settings?.guidance)
        assertEquals(0.25f, studio?.settings?.cfgRescale)
        assertEquals(NovelAiSampler.DPM_PLUS_PLUS_2M, studio?.settings?.sampler)
        assertEquals(123456789L, studio?.seed)
    }

    @Test
    fun `rejects generic JSON comment without NovelAI structure`() {
        val comment = """{"prompt":"hello","width":1024,"height":1024}"""

        assertNull(NovelAiPngMetadataReader.parseStudioComment(comment, "/tmp/generic.png"))
    }

    @Test
    fun `parses image guidance and encoded vibe metadata`() {
        val comment = """
            {
              "prompt":"scene", "uc":"bad", "width":1024, "height":1024,
              "steps":28, "sampler":"k_euler_ancestral", "seed":7,
              "action":"infill", "image":"base", "mask":"mask", "strength":1.0,
              "director_reference_images":["precise"],
              "director_reference_descriptions":[{"caption":{"base_caption":"style","char_captions":[]}}],
              "director_reference_strength_values":[0.8],
              "director_reference_secondary_strength_values":[0.25],
              "reference_image_multiple":["encoded"],
              "reference_information_extracted_multiple":[0.9],
              "reference_strength_multiple":[0.6]
            }
        """.trimIndent()

        val guidance = NovelAiPngMetadataReader.parseStudioComment(comment, "/tmp/guided.png")?.imageGuidance

        assertEquals(NovelAiGenerationAction.INPAINT, guidance?.action)
        assertEquals("base", guidance?.baseImageBase64)
        assertEquals("mask", guidance?.maskBase64)
        assertEquals(NovelAiPreciseReferenceType.STYLE, guidance?.preciseType)
        assertEquals(0.75f, guidance?.preciseFidelity)
        assertEquals("encoded", guidance?.vibes?.single()?.encodedVibe)
    }
}
