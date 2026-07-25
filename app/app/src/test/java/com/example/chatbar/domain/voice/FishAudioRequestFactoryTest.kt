package com.example.chatbar.domain.voice

import kotlinx.serialization.json.Json
import okio.Buffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class FishAudioRequestFactoryTest {
    @Test
    fun `model request includes auth self filters sort and paging`() {
        val request = FishAudioRequestFactory.listModels(
            baseUrl = "https://api.fish.audio/",
            apiKey = " fish-key ",
            query = FishAudioModelQuery(
                library = FishAudioLibrary.MINE,
                pageSize = 30,
                pageNumber = 3,
                title = "少女",
                tags = listOf("anime", "soft"),
                languages = listOf("zh", "ja"),
                sort = FishAudioModelSort.POPULAR
            )
        )

        assertEquals("Bearer fish-key", request.header("Authorization"))
        assertEquals("/model", request.url.encodedPath)
        assertEquals("true", request.url.queryParameter("self"))
        assertEquals("30", request.url.queryParameter("page_size"))
        assertEquals("3", request.url.queryParameter("page_number"))
        assertEquals("task_count", request.url.queryParameter("sort_by"))
        assertEquals("少女", request.url.queryParameter("title"))
        assertEquals(listOf("anime", "soft"), request.url.queryParameterValues("tag"))
        assertEquals(listOf("zh", "ja"), request.url.queryParameterValues("language"))
    }

    @Test
    fun `tts request uses model header reference and 64 kbps mp3`() {
        val request = FishAudioRequestFactory.tts(
            baseUrl = "https://api.fish.audio",
            apiKey = "fish-key",
            modelId = FishAudioTtsModels.S2_1_PRO_FREE,
            referenceId = "voice-reference",
            text = "[happy]你好"
        )
        val buffer = Buffer()
        request.body!!.writeTo(buffer)
        val payload = Json.decodeFromString(
            FishAudioTtsRequest.serializer(),
            buffer.readUtf8()
        )

        assertEquals("/v1/tts", request.url.encodedPath)
        assertEquals("Bearer fish-key", request.header("Authorization"))
        assertEquals(FishAudioTtsModels.S2_1_PRO_FREE, request.header("model"))
        assertEquals("audio/mpeg", request.header("Accept"))
        assertEquals("voice-reference", payload.referenceId)
        assertEquals("[happy]你好", payload.text)
        assertEquals("mp3", payload.format)
        assertEquals(44_100, payload.sampleRate)
        assertEquals(64, payload.mp3Bitrate)
        assertEquals("normal", payload.latency)
    }

    @Test
    fun `blank API key is rejected before network request`() {
        assertThrows(IllegalArgumentException::class.java) {
            FishAudioRequestFactory.listModels(
                "https://api.fish.audio",
                " ",
                FishAudioModelQuery()
            )
        }
    }
}
