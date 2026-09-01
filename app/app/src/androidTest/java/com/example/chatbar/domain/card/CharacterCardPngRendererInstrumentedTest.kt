package com.example.chatbar.domain.card

import android.content.Context
import android.graphics.BitmapFactory
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.example.chatbar.data.local.entity.CharacterCard
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CharacterCardPngRendererInstrumentedTest {
    private val context: Context = ApplicationProvider.getApplicationContext()

    @Test
    fun render_plainPngKeepsMetadataWritable() {
        val card = CharacterCard.create("普通角色")
        val pngBytes = CharacterCardPngRenderer.render(
            context,
            card,
            CharacterCardPngExportOptions(sizePx = 1024)
        )
        val bitmap = requireNotNull(BitmapFactory.decodeByteArray(pngBytes, 0, pngBytes.size))

        try {
            assertEquals(1024, bitmap.width)
            assertEquals(1024, bitmap.height)

            val withMetadata = PngTextChunks.insertTextChunk(
                pngBytes,
                PngTextChunks.CHATBAR_CHARACTER_KEYWORD,
                "payload"
            )
            assertEquals(
                "payload",
                PngTextChunks.extractTextChunk(withMetadata, PngTextChunks.CHATBAR_CHARACTER_KEYWORD)
            )
        } finally {
            bitmap.recycle()
        }
    }
}
