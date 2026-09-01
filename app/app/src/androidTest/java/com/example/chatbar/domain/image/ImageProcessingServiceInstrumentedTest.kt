package com.example.chatbar.domain.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bumptech.glide.gifdecoder.GifDecoder
import com.bumptech.glide.gifdecoder.GifHeaderParser
import com.bumptech.glide.gifdecoder.StandardGifDecoder
import com.bumptech.glide.gifencoder.AnimatedGifEncoder
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.util.zip.CRC32
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ImageProcessingServiceInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val service = ImageProcessingService(context)

    @Test
    fun staticDisguiseThenRestorePreservesRgbaPixelsAndKeepsLogoOutOfAnimation() = runBlocking {
        val source = File(context.cacheDir, "apng-static-source.png")
        val sourceBitmap = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888).apply {
            eraseColor(Color.TRANSPARENT)
            setPixel(3, 4, 0x804080c0.toInt())
            setPixel(8, 9, 0xffff8030.toInt())
        }
        source.outputStream().use { output -> assertTrue(sourceBitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) }
        val expected = sourceBitmap.readPixels()
        sourceBitmap.recycle()
        var disguise: ProcessedImage? = null
        var restored: ProcessedImage? = null
        try {
            disguise = service.createApngDisguise(source.absolutePath)
            assertEquals("png", File(disguise.path).extension)
            val inspection = requireNotNull(ApngDisguiseCodec.inspectDisguise(File(disguise.path)))
            assertEquals(1, inspection.metadata.version)
            assertEquals(ApngDisguiseContentKind.STATIC, inspection.metadata.contentKind)
            assertEquals(1, inspection.metadata.contentFrameCount)
            assertEquals(1, inspection.animationFrameCount)
            assertEquals(0, inspection.playCount)

            val defaultImage = requireNotNull(BitmapFactory.decodeFile(disguise.path))
            try {
                assertEquals(Color.rgb(0x2f, 0x8e, 0x7b), defaultImage.getPixel(0, 0))
                assertNotEquals(expected[4 * 12 + 3], defaultImage.getPixel(3, 4))
            } finally {
                defaultImage.recycle()
            }

            restored = service.restoreApngDisguise(disguise.path)
            assertEquals("png", File(restored.path).extension)
            assertFalse(ApngDisguiseCodec.containsAnimationControl(File(restored.path)))
            assertEquals(ProcessedImageOperation.APNG_RESTORE, restored.operation)
            assertFalse(restored.isAnimated)
            val restoredBitmap = requireNotNull(BitmapFactory.decodeFile(restored.path))
            try {
                assertArrayEquals(expected, restoredBitmap.readPixels())
            } finally {
                restoredBitmap.recycle()
            }
        } finally {
            source.delete()
            disguise?.path?.let(::File)?.delete()
            restored?.path?.let(::File)?.delete()
        }
    }

    @Test
    fun gifDisguiseThenRestorePreservesFramesTimingTransparencyAndLoopIntent() = runBlocking {
        val source = File(context.cacheDir, "apng-animated-source.gif")
        val encoder = AnimatedGifEncoder().apply {
            setSize(12, 12)
            setRepeat(0)
            setTransparent(Color.TRANSPARENT)
        }
        source.outputStream().buffered().use { output ->
            assertTrue(encoder.start(output))
            listOf(40 to 0xffff0000.toInt(), 90 to 0xff0080ff.toInt()).forEachIndexed { index, (delay, color) ->
                val frame = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888).apply {
                    eraseColor(color)
                    setPixel(index, index, Color.TRANSPARENT)
                }
                encoder.setDelay(delay)
                assertTrue(encoder.addFrame(frame))
                frame.recycle()
            }
            assertTrue(encoder.finish())
        }
        val expectedFrames = decodeGifFrames(source)
        var disguise: ProcessedImage? = null
        var restored: ProcessedImage? = null
        try {
            disguise = service.createApngDisguise(source.absolutePath)
            assertEquals("png", File(disguise.path).extension)
            val disguisedInspection = requireNotNull(ApngDisguiseCodec.inspectDisguise(File(disguise.path)))
            assertEquals(ApngDisguiseContentKind.ANIMATED, disguisedInspection.metadata.contentKind)
            assertEquals(2, disguisedInspection.animationFrameCount)
            assertEquals(0, disguisedInspection.playCount)

            restored = service.restoreApngDisguise(disguise.path)
            assertEquals("png", File(restored.path).extension)
            assertTrue(restored.isAnimated)
            assertTrue(ApngDisguiseCodec.containsAnimationControl(File(restored.path)))
            assertEquals(null, ApngDisguiseCodec.inspectDisguise(File(restored.path)))
            val restoredAnimation = decodeFullFrameApng(File(restored.path))
            assertEquals(0, restoredAnimation.playCount)
            assertEquals(listOf(40, 90), restoredAnimation.delaysMillis)
            assertEquals(expectedFrames.size, restoredAnimation.frames.size)
            expectedFrames.zip(restoredAnimation.frames).forEach { (expectedFrame, actualFrame) ->
                assertArrayEquals(expectedFrame, actualFrame)
            }
        } finally {
            source.delete()
            disguise?.path?.let(::File)?.delete()
            restored?.path?.let(::File)?.delete()
        }
    }

    @Test
    fun cancellationAndInputLimitDoNotDeleteUnrelatedWorkFiles() = runBlocking {
        val workDirectory = File(context.filesDir, "images/image-processing").apply { mkdirs() }
        val unrelated = File(workDirectory, "keep-existing.apng").apply { writeText("keep") }
        val before = workDirectory.listFiles().orEmpty().map(File::getName).toSet()
        val bitmap = Bitmap.createBitmap(512, 512, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xff123456.toInt())
        }
        try {
            var cancelled = false
            try {
                service.createApngDisguise(bitmap) { progress ->
                    if (progress >= 0.3f) throw CancellationException("test cancellation")
                }
            } catch (_: CancellationException) {
                cancelled = true
            }
            assertTrue(cancelled)
            assertTrue(unrelated.isFile)
            assertEquals(before, workDirectory.listFiles().orEmpty().map(File::getName).toSet())
        } finally {
            bitmap.recycle()
            unrelated.delete()
        }

        val oversized = File(context.cacheDir, "apng-oversized-input.png")
        RandomAccessFile(oversized, "rw").use { it.setLength(100L * 1024 * 1024 + 1) }
        try {
            var rejected = false
            try {
                service.inspectFile(oversized.absolutePath)
            } catch (_: IllegalArgumentException) {
                rejected = true
            }
            assertTrue(rejected)
            assertTrue(oversized.isFile)
        } finally {
            oversized.delete()
        }
    }

    private fun decodeGifFrames(file: File): List<IntArray> {
        val bytes = file.readBytes()
        val header = GifHeaderParser().setData(bytes).parseHeader()
        assertEquals(GifDecoder.STATUS_OK, header.status)
        val decoder = StandardGifDecoder(TestBitmapProvider, header, ByteBuffer.wrap(bytes))
        return try {
            List(decoder.frameCount) {
                decoder.advance()
                val frame = requireNotNull(decoder.nextFrame)
                try {
                    frame.readPixels()
                } finally {
                    frame.recycle()
                }
            }
        } finally {
            decoder.clear()
        }
    }

    private fun decodeFullFrameApng(file: File): DecodedApng {
        val chunks = readChunks(file.readBytes())
        val ihdr = chunks.first { it.first == "IHDR" }.second
        val playCount = chunks.first { it.first == "acTL" }.second.readInt(4)
        val frames = mutableListOf<MutableList<ByteArray>>()
        val delays = mutableListOf<Int>()
        var current: MutableList<ByteArray>? = null
        chunks.forEach { (type, data) ->
            when (type) {
                "fcTL" -> {
                    current = mutableListOf<ByteArray>().also(frames::add)
                    val numerator = data.readUnsignedShort(20)
                    val denominator = data.readUnsignedShort(22).takeIf { it != 0 } ?: 100
                    delays += numerator * 1000 / denominator
                }
                "IDAT" -> current?.add(data)
                "fdAT" -> current?.add(data.copyOfRange(4, data.size))
            }
        }
        val decoded = frames.map { compressedChunks ->
            val png = ByteArrayOutputStream().apply {
                write(PNG_SIGNATURE)
                writeChunk("IHDR", ihdr)
                compressedChunks.forEach { writeChunk("IDAT", it) }
                writeChunk("IEND", ByteArray(0))
            }.toByteArray()
            val bitmap = requireNotNull(BitmapFactory.decodeByteArray(png, 0, png.size))
            try {
                bitmap.readPixels()
            } finally {
                bitmap.recycle()
            }
        }
        return DecodedApng(playCount, delays, decoded)
    }

    private fun readChunks(bytes: ByteArray): List<Pair<String, ByteArray>> {
        var offset = PNG_SIGNATURE.size
        val result = mutableListOf<Pair<String, ByteArray>>()
        while (offset + 12 <= bytes.size) {
            val length = bytes.readInt(offset)
            val type = bytes.copyOfRange(offset + 4, offset + 8).toString(Charsets.US_ASCII)
            result += type to bytes.copyOfRange(offset + 8, offset + 8 + length)
            offset += 12 + length
        }
        return result
    }

    private fun ByteArrayOutputStream.writeChunk(type: String, data: ByteArray) {
        writeInt(data.size)
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        write(typeBytes)
        write(data)
        val crc = CRC32().apply {
            update(typeBytes)
            update(data)
        }
        writeInt(crc.value.toInt())
    }

    private fun ByteArrayOutputStream.writeInt(value: Int) {
        write((value ushr 24) and 0xff)
        write((value ushr 16) and 0xff)
        write((value ushr 8) and 0xff)
        write(value and 0xff)
    }

    private fun ByteArray.readInt(offset: Int): Int =
        ((this[offset].toInt() and 0xff) shl 24) or
            ((this[offset + 1].toInt() and 0xff) shl 16) or
            ((this[offset + 2].toInt() and 0xff) shl 8) or
            (this[offset + 3].toInt() and 0xff)

    private fun ByteArray.readUnsignedShort(offset: Int): Int =
        ((this[offset].toInt() and 0xff) shl 8) or (this[offset + 1].toInt() and 0xff)

    private fun Bitmap.readPixels(): IntArray = IntArray(width * height).also { pixels ->
        getPixels(pixels, 0, width, 0, 0, width, height)
    }

    private data class DecodedApng(
        val playCount: Int,
        val delaysMillis: List<Int>,
        val frames: List<IntArray>
    )

    private object TestBitmapProvider : GifDecoder.BitmapProvider {
        override fun obtain(width: Int, height: Int, config: Bitmap.Config): Bitmap =
            Bitmap.createBitmap(width, height, config)
        override fun release(bitmap: Bitmap) = bitmap.recycle()
        override fun obtainByteArray(size: Int): ByteArray = ByteArray(size)
        override fun release(bytes: ByteArray) = Unit
        override fun obtainIntArray(size: Int): IntArray = IntArray(size)
        override fun release(array: IntArray) = Unit
    }

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a)
    }
}
