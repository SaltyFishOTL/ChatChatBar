package com.example.chatbar.ui.components

import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ImageActionsTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun apngAndGifShareAsFilesWhileStaticPngSharesAsImage() {
        assertTrue(write("animation.apng", byteArrayOf()).let(::shouldShareAsFile))
        assertTrue(write("animation.gif", "GIF89a".toByteArray()).let(::shouldShareAsFile))
        assertTrue(write("animation.png", pngWithAnimationControl()).let(::shouldShareAsFile))
        assertFalse(write("static.png", pngWithoutAnimationControl()).let(::shouldShareAsFile))
    }

    private fun write(name: String, bytes: ByteArray): File =
        temporaryFolder.newFile(name).apply { writeBytes(bytes) }

    private fun pngWithAnimationControl(): ByteArray = pngChunkStream("acTL", ByteArray(8))

    private fun pngWithoutAnimationControl(): ByteArray = pngChunkStream("IEND", ByteArray(0))

    private fun pngChunkStream(type: String, data: ByteArray): ByteArray =
        ByteArrayOutputStream().use { bytes ->
            DataOutputStream(bytes).use { output ->
                output.write(PNG_SIGNATURE)
                output.writeInt(data.size)
                output.write(type.toByteArray(Charsets.US_ASCII))
                output.write(data)
                output.writeInt(0)
            }
            bytes.toByteArray()
        }

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
        )
    }
}
