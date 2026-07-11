package com.example.chatbar.domain.image

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
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
              "v4_prompt": {
                "caption": {
                  "base_caption": "1girl, outdoors",
                  "char_captions": [
                    {"char_caption": "alice", "centers": [{"x": 0.25, "y": 0.5}]}
                  ]
                }
              },
              "v4_negative_prompt": {
                "caption": {"base_caption": "bad hands"}
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
    }
}
