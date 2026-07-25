package com.example.chatbar.domain.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FishAudioPreviewSessionCacheTest {
    @Test
    fun `first generated preview is reused for each voice until session clears`() {
        val existingPaths = mutableSetOf("first.mp3", "second.mp3")
        val cache = FishAudioPreviewSessionCache(existingPaths::contains)

        assertEquals("first.mp3", cache.remember("voice-1", "first.mp3"))
        assertEquals("first.mp3", cache.remember("voice-1", "second.mp3"))
        assertEquals("first.mp3", cache.get("voice-1"))

        cache.clear()

        assertNull(cache.get("voice-1"))
    }

    @Test
    fun `missing generated preview is removed from session cache`() {
        val cache = FishAudioPreviewSessionCache { false }
        cache.remember("voice-1", "missing.mp3")

        assertNull(cache.get("voice-1"))
    }
}
