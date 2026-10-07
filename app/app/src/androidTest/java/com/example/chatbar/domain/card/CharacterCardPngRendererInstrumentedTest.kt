package com.example.chatbar.domain.card

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.graphics.Color
import java.io.File
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
    fun selectedExportCoverDoesNotChangeCardBackgroundOrMetadata() {
        val background = File.createTempFile("original", ".png", context.cacheDir)
        val selected = File.createTempFile("selected", ".png", context.cacheDir)
        fun fill(file: File, color: Int) {
            val image = Bitmap.createBitmap(32, 32, Bitmap.Config.ARGB_8888)
            try { image.eraseColor(color); file.outputStream().use { image.compress(Bitmap.CompressFormat.PNG, 100, it) } }
            finally { image.recycle() }
        }
        try {
            fill(background, Color.RED)
            fill(selected, Color.BLUE)
            val card = CharacterCard.create("synthetic").copy(chatBackground = background.absolutePath)
            for ((path, color) in listOf(selected.absolutePath to Color.BLUE, null to Color.RED)) {
                val png = CharacterCardPngRenderer.render(context, card, CharacterCardPngExportOptions(coverImagePath = path, sizePx = 1024))
                val image = BitmapFactory.decodeByteArray(png, 0, png.size)
                try { assertEquals(color, image.getPixel(512, 100)) } finally { image.recycle() }
                val packed = PngTextChunks.insertTextChunk(png, PngTextChunks.CHATBAR_CHARACTER_KEYWORD, "original-card-payload")
                assertEquals("original-card-payload", PngTextChunks.extractTextChunk(packed, PngTextChunks.CHATBAR_CHARACTER_KEYWORD))
            }
            assertEquals(background.absolutePath, card.chatBackground)
        } finally { background.delete(); selected.delete() }
    }

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
