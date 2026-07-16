package com.example.chatbar.domain.image

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.msgpack.core.MessagePack

class NovelAiImageRetryTest {
    @Test
    fun `429 retries until third attempt succeeds`() = runTest {
        val attempts = AtomicInteger()
        val expectedImage = byteArrayOf(1, 3, 5, 7)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                if (attempts.incrementAndGet() < 3) {
                    response(chain.request(), 429, "rate limited".encodeToByteArray(), retryAfter = "0")
                } else {
                    response(chain.request(), 200, finalFrame(expectedImage))
                }
            }
            .build()

        val events = try {
            NovelAiImageService(client)
                .generate("token", NovelAiPromptPlan("scene", emptyList()), seed = 42)
                .toList()
        } finally {
            client.closeTestResources()
        }

        assertEquals(3, attempts.get())
        val final = events.single() as NovelAiImageEvent.Final
        assertArrayEquals(expectedImage, final.image)
    }

    @Test
    fun `third 429 emits one final failure`() = runTest {
        val attempts = AtomicInteger()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                attempts.incrementAndGet()
                response(chain.request(), 429, "rate limited".encodeToByteArray(), retryAfter = "0")
            }
            .build()

        val events = try {
            NovelAiImageService(client)
                .generate("token", NovelAiPromptPlan("scene", emptyList()), seed = 42)
                .toList()
        } finally {
            client.closeTestResources()
        }

        assertEquals(3, attempts.get())
        val error = events.single() as NovelAiImageEvent.Error
        assertTrue(error.message.contains("HTTP 429"))
        assertTrue(error.message.contains("已尝试 3 次仍失败"))
    }

    private fun response(
        request: Request,
        code: Int,
        body: ByteArray,
        retryAfter: String? = null
    ): Response = Response.Builder()
        .request(request)
        .protocol(Protocol.HTTP_1_1)
        .code(code)
        .message(if (code == 200) "OK" else "Too Many Requests")
        .apply { if (retryAfter != null) header("Retry-After", retryAfter) }
        .body(body.toResponseBody("application/octet-stream".toMediaType()))
        .build()

    private fun finalFrame(image: ByteArray): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packMapHeader(2)
        packer.packString("event_type")
        packer.packString("final")
        packer.packString("image")
        packer.packBinaryHeader(image.size)
        packer.writePayload(image)
        packer.close()
        val payload = packer.toByteArray()
        return ByteBuffer.allocate(4 + payload.size)
            .putInt(payload.size)
            .put(payload)
            .array()
    }

    private fun OkHttpClient.closeTestResources() {
        dispatcher.executorService.shutdown()
        connectionPool.evictAll()
    }
}
