package com.example.chatbar.domain.image

import android.content.Context
import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bumptech.glide.gifdecoder.GifDecoder
import com.bumptech.glide.gifdecoder.GifHeaderParser
import com.bumptech.glide.gifencoder.AnimatedGifEncoder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

@RunWith(AndroidJUnit4::class)
class ImageProcessingServiceInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val service = ImageProcessingService(context)

    @Test
    fun staticImageApplyThenRestoreReturnsOriginalPixels() = runBlocking {
        val source = File(context.cacheDir, "image-processing-source.png")
        val sourceBitmap = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888).apply {
            eraseColor(0xff808080.toInt())
        }
        source.outputStream().use { output ->
            assertTrue(sourceBitmap.compress(Bitmap.CompressFormat.PNG, 100, output))
        }
        sourceBitmap.recycle()
        var patched: ProcessedImage? = null
        var restored: ProcessedImage? = null
        try {
            patched = service.process(source.absolutePath, FullImagePatchOperation.Apply)
            restored = service.process(patched.path, FullImagePatchOperation.Restore)

            val restoredBitmap = android.graphics.BitmapFactory.decodeFile(restored.path)
            assertEquals(0xff808080.toInt(), restoredBitmap.getPixel(6, 6))
            restoredBitmap.recycle()
        } finally {
            source.delete()
            patched?.path?.let(::File)?.delete()
            restored?.path?.let(::File)?.delete()
        }
    }

    @Test
    fun animatedGifPreservesFramesAndTiming() = runBlocking {
        val source = File(context.cacheDir, "image-processing-source.gif")
        val encoder = AnimatedGifEncoder().apply {
            setSize(12, 12)
            setRepeat(0)
        }
        source.outputStream().buffered().use { output ->
            assertTrue(encoder.start(output))
            listOf(40 to 0xff808080.toInt(), 90 to 0xff7090a0.toInt()).forEach { (delay, color) ->
                val frame = Bitmap.createBitmap(12, 12, Bitmap.Config.ARGB_8888).apply { eraseColor(color) }
                encoder.setDelay(delay)
                assertTrue(encoder.addFrame(frame))
                frame.recycle()
            }
            assertTrue(encoder.finish())
        }
        var result: ProcessedImage? = null
        try {
            result = service.process(source.absolutePath, FullImagePatchOperation.Apply)
            val header = GifHeaderParser().setData(File(result.path).readBytes()).parseHeader()

            assertEquals(GifDecoder.STATUS_OK, header.status)
            assertEquals(2, header.numFrames)
            assertEquals(2, result.frameCount)
        } finally {
            source.delete()
            result?.path?.let(::File)?.delete()
        }
    }
}
