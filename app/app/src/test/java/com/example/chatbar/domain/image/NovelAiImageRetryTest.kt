package com.example.chatbar.domain.image

import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Protocol
import okhttp3.Request
import okhttp3.Response
import okhttp3.ResponseBody
import okhttp3.ResponseBody.Companion.toResponseBody
import okio.Buffer
import okio.Source
import okio.Timeout
import okio.buffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.msgpack.core.MessagePack

class NovelAiImageRetryTest {
    @Test fun transparentPngBytesSurviveTheProductionStreamUnchanged() = runTest {
        val png = java.util.Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAIAAAABCAYAAAD0In+KAAAAD0lEQVR4nGP4zwAE/xkaAAt+An+rqPSSAAAAAElFTkSuQmCC")
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            response(chain.request(), 200, finalFrame(png))
        }.build()
        try {
            val settings = NovelAiGenerationSettings(model = NovelAiImageModel.V5_FULL, transparentBackground = true)
            val events = NovelAiImageService(client).generate("synthetic-token",
                NovelAiPromptPlan("synthetic scene", emptyList()), settings.imageSize(), settings).toList()
            assertArrayEquals(png, (events.single() as NovelAiImageEvent.Final).image)
        } finally { client.closeTestResources() }
    }

    @Test
    fun `ten rate limit retries allow success on eleventh request`() = runTest {
        verifyBoundedRateLimitRetries(succeedOnLastAttempt = true)
    }

    @Test
    fun `ten rate limit retries stop after eleventh 429`() = runTest {
        verifyBoundedRateLimitRetries(succeedOnLastAttempt = false)
    }

    private suspend fun verifyBoundedRateLimitRetries(succeedOnLastAttempt: Boolean) {
        val attempts = AtomicInteger()
        val retries = java.util.Collections.synchronizedList(mutableListOf<Int>())
        val expectedImage = byteArrayOf(1, 2, 3)
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val attempt = attempts.incrementAndGet()
            if (succeedOnLastAttempt && attempt == 11) {
                response(chain.request(), 200, finalFrame(expectedImage))
            } else {
                response(chain.request(), 429, byteArrayOf(), retryAfter = "0")
            }
        }.build()
        try {
            val plan = NovelAiPromptPlan("scene", emptyList())
            val events = NovelAiImageService(client).generate(
                "token", plan, plan.sizePreset.imageSize,
                NovelAiGenerationSettings.legacy(42, 1),
                maxRateLimitRetries = 10,
                onRateLimitRetry = { attempt, _ -> retries += attempt }
            ).toList()
            assertEquals(11, attempts.get())
            assertEquals((1..10).toList(), retries)
            if (succeedOnLastAttempt) {
                assertArrayEquals(expectedImage, (events.single() as NovelAiImageEvent.Final).image)
            } else {
                assertTrue((events.single() as NovelAiImageEvent.Error).message.contains("HTTP 429"))
            }
        } finally {
            client.closeTestResources()
        }
    }

    @Test
    fun `stream retry control frame is ignored until final image`() = runTest {
        val expectedImage = byteArrayOf(2, 4, 6, 8)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                response(chain.request(), 200, retryFrame() + finalFrame(expectedImage))
            }
            .build()

        val events = try {
            NovelAiImageService(client)
                .generate("token", NovelAiPromptPlan("scene", emptyList()), seed = 42)
                .toList()
        } finally {
            client.closeTestResources()
        }

        val final = events.single() as NovelAiImageEvent.Final
        assertArrayEquals(expectedImage, final.image)
    }

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

    @Test
    fun `automatic mode continues beyond three rate limits without counting failures as images`() = runTest {
        val attempts = AtomicInteger()
        val retries = mutableListOf<Int>()
        val expectedImage = byteArrayOf(9, 8, 7)
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                if (attempts.incrementAndGet() < 5) {
                    response(chain.request(), 429, byteArrayOf(), retryAfter = "0")
                } else {
                    response(chain.request(), 200, finalFrame(expectedImage))
                }
            }
            .build()
        val events = try {
            val plan = NovelAiPromptPlan("scene", emptyList())
            NovelAiImageService(client).generate(
                "token", plan, plan.sizePreset.imageSize,
                NovelAiGenerationSettings.legacy(42, 1),
                retryRateLimitsUntilCancelled = true,
                onRateLimitRetry = { attempt, delayMs ->
                    retries += attempt
                    assertTrue(delayMs >= 1_000L)
                }
            ).toList()
        } finally {
            client.closeTestResources()
        }
        assertEquals(5, attempts.get())
        assertEquals(listOf(1, 2, 3, 4), retries)
        assertArrayEquals(expectedImage, (events.single() as NovelAiImageEvent.Final).image)
    }

    @Test
    fun `automatic mode does not retry non rate limit errors`() = runTest {
        val attempts = AtomicInteger()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                attempts.incrementAndGet()
                response(chain.request(), 402, byteArrayOf())
            }
            .build()
        val events = try {
            val plan = NovelAiPromptPlan("scene", emptyList())
            NovelAiImageService(client).generate(
                "token", plan, plan.sizePreset.imageSize,
                NovelAiGenerationSettings.legacy(42, 1),
                retryRateLimitsUntilCancelled = true
            ).toList()
        } finally {
            client.closeTestResources()
        }
        assertEquals(1, attempts.get())
        assertTrue((events.single() as NovelAiImageEvent.Error).message.contains("HTTP 402"))
    }

    @Test
    fun `automatic retry wait can be cancelled before sending another request`() = runTest {
        val attempts = AtomicInteger()
        val retryStarted = CompletableDeferred<Unit>()
        val client = OkHttpClient.Builder()
            .addInterceptor { chain ->
                attempts.incrementAndGet()
                response(chain.request(), 429, byteArrayOf(), retryAfter = "30")
            }
            .build()
        try {
            val plan = NovelAiPromptPlan("scene", emptyList())
            val collection = async {
                NovelAiImageService(client).generate(
                    "token", plan, plan.sizePreset.imageSize,
                    NovelAiGenerationSettings.legacy(42, 1),
                    retryRateLimitsUntilCancelled = true,
                    onRateLimitRetry = { _, _ -> retryStarted.complete(Unit) }
                ).toList()
            }
            retryStarted.await()
            collection.cancelAndJoin()
            assertEquals(1, attempts.get())
        } finally {
            client.closeTestResources()
        }
    }

    @Test
    fun `later generation batches keep retrying rate limits and report replacement requests`() = runTest {
        val attempts = AtomicInteger()
        val statuses = java.util.Collections.synchronizedList(mutableListOf<String>())
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val attempt = attempts.incrementAndGet()
            if (attempt in 2..5) response(chain.request(), 429, byteArrayOf(), retryAfter = "0")
            else response(chain.request(), 200, finalFrame(byteArrayOf(attempt.toByte())))
        }.build()
        try {
            val service = NovelAiImageService(client)
            val plan = NovelAiPromptPlan("scene", emptyList())
            repeat(3) {
                val events = service.generate(
                    "token", plan, plan.sizePreset.imageSize,
                    NovelAiGenerationSettings.legacy(42, 1),
                    retryRateLimitsUntilCancelled = true,
                    onRequestStatus = { status -> statuses += status }
                ).toList()
                assertTrue(events.single() is NovelAiImageEvent.Final)
            }
            assertEquals(7, attempts.get())
            // One request-start notification per attempt plus one response-open per successful batch.
            assertEquals(10, statuses.size)
        } finally {
            client.closeTestResources()
        }
    }

    @Test
    fun `server error frame finishes without reading until peer closes stream`() = runTest {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packMapHeader(2)
        packer.packString("event_type")
        packer.packString("error")
        packer.packString("message")
        packer.packString("generation failed")
        packer.close()
        val payload = packer.toByteArray()
        val bytes = ByteBuffer.allocate(4 + payload.size).putInt(payload.size).put(payload).array()
        val readsAfterError = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val source = object : Source {
                val data = Buffer().write(bytes)
                override fun read(sink: Buffer, byteCount: Long): Long {
                    if (data.size > 0) return data.read(sink, byteCount)
                    readsAfterError.incrementAndGet()
                    throw java.io.IOException("stream was reset: INTERNAL_ERROR")
                }
                override fun timeout(): Timeout = Timeout.NONE
                override fun close() = Unit
            }.buffer()
            response(chain.request(), 200, byteArrayOf()).newBuilder().body(object : ResponseBody() {
                override fun contentType() = "application/octet-stream".toMediaType()
                override fun contentLength() = -1L
                override fun source() = source
            }).build()
        }.build()
        try {
            val events = NovelAiImageService(client)
                .generate("token", NovelAiPromptPlan("scene", emptyList()), seed = 42).toList()
            assertTrue((events.single() as NovelAiImageEvent.Error).message.contains("generation failed"))
            assertEquals(0, readsAfterError.get())
        } finally {
            client.closeTestResources()
        }
    }

    @Test
    fun `successive complete batches finish without transport EOF so callers can save history`() = runTest {
        val attempts = AtomicInteger()
        val readsPastFinal = AtomicInteger()
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            val batch = attempts.incrementAndGet()
            val frames = finalFrame(byteArrayOf(batch.toByte(), 1)) + finalFrame(byteArrayOf(batch.toByte(), 2))
            if (batch == 1) {
                response(chain.request(), 200, frames)
            } else {
                val source = object : Source {
                    val data = Buffer().write(frames)
                    override fun read(sink: Buffer, byteCount: Long): Long {
                        if (data.size > 0) return data.read(sink, byteCount)
                        readsPastFinal.incrementAndGet()
                        throw java.io.IOException("stream was reset: INTERNAL_ERROR")
                    }
                    override fun timeout(): Timeout = Timeout.NONE
                    override fun close() = Unit
                }.buffer()
                response(chain.request(), 200, byteArrayOf()).newBuilder().body(object : ResponseBody() {
                    override fun contentType() = "application/octet-stream".toMediaType()
                    override fun contentLength() = -1L
                    override fun source() = source
                }).build()
            }
        }.build()
        try {
            val service = NovelAiImageService(client)
            val completedBatches = mutableListOf<List<ByteArray>>()
            repeat(3) {
                val events = service.generate(
                    "token", NovelAiPromptPlan("scene", emptyList()), seed = 42, batchSize = 2
                ).toList()
                assertEquals(2, events.size)
                assertTrue(events.all { event -> event is NovelAiImageEvent.Final })
                completedBatches += events.map { event -> (event as NovelAiImageEvent.Final).image }
            }
            assertEquals(3, completedBatches.size)
            assertEquals(6, completedBatches.flatten().size)
            assertEquals(0, readsPastFinal.get())
        } finally {
            client.closeTestResources()
        }
    }

    @Test
    fun `extra final frames in received chunk still fail the batch`() = runTest {
        val client = OkHttpClient.Builder().addInterceptor { chain ->
            response(chain.request(), 200, finalFrame(byteArrayOf(1)) + finalFrame(byteArrayOf(2)))
        }.build()
        try {
            val events = NovelAiImageService(client)
                .generate("token", NovelAiPromptPlan("scene", emptyList()), seed = 42).toList()
            assertTrue(events.last() is NovelAiImageEvent.Error)
        } finally {
            client.closeTestResources()
        }
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

    private fun retryFrame(): ByteArray {
        val packer = MessagePack.newDefaultBufferPacker()
        packer.packMapHeader(1)
        packer.packString("event_type")
        packer.packString("retry")
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
