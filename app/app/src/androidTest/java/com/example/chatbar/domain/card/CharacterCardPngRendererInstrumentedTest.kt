package com.example.chatbar.domain.card

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.chatbar.data.local.entity.CharacterCard
import com.example.chatbar.domain.image.FullImagePatchOperation
import com.example.chatbar.domain.image.transformFullImageAdversarialPatch
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CharacterCardPngRendererInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun render_patchOptionTransformsFinalPixelsAndKeepsPngMetadataWritable() {
        val card = CharacterCard.create("贴片角色")
        val plain = decode(CharacterCardPngRenderer.render(context, card, CharacterCardPngExportOptions(sizePx = 1024)))
        val patchedBytes = CharacterCardPngRenderer.render(
            context,
            card,
            CharacterCardPngExportOptions(sizePx = 1024, applyFullImagePatch = true)
        )
        val patched = decode(patchedBytes)
        val expected = plain.copy(Bitmap.Config.ARGB_8888, true)

        try {
            transformFullImageAdversarialPatch(expected, FullImagePatchOperation.Apply)
            assertEquals(expected.width, patched.width)
            assertEquals(expected.height, patched.height)
            assertArrayEquals(expected.readPixels(), patched.readPixels())

            val withMetadata = PngTextChunks.insertTextChunk(
                patchedBytes,
                PngTextChunks.CHATBAR_CHARACTER_KEYWORD,
                "payload"
            )
            assertEquals(
                "payload",
                PngTextChunks.extractTextChunk(withMetadata, PngTextChunks.CHATBAR_CHARACTER_KEYWORD)
            )
        } finally {
            plain.recycle()
            patched.recycle()
            expected.recycle()
        }
    }

    private fun decode(bytes: ByteArray): Bitmap =
        requireNotNull(BitmapFactory.decodeByteArray(bytes, 0, bytes.size))

    private fun Bitmap.readPixels(): IntArray = IntArray(width * height).also { pixels ->
        getPixels(pixels, 0, width, 0, 0, width, height)
    }
}
