package com.example.chatbar.domain.image

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32

class ImageMetadataStripperTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun pngRemovesMetadataAndKeepsImageChunksByteExact() {
        val imageData = byteArrayOf(1, 2, 3, 4, 5)
        val gamma = byteArrayOf(0, 0, 0xb1.toByte(), 0x8f.toByte())
        val sourceBytes = png(
            "IHDR" to ByteArray(13),
            "tEXt" to "Comment\u0000private prompt".toByteArray(),
            "gAMA" to gamma,
            "IDAT" to imageData,
            "IEND" to byteArrayOf()
        )
        val source = temporaryFolder.newFile("source.png").apply { writeBytes(sourceBytes) }

        val result = ImageMetadataStripper.stripToCopy(source, temporaryFolder.newFolder("output"))

        val chunks = readPngChunks(result.readBytes())
        assertFalse(chunks.any { it.first == "tEXt" })
        assertArrayEquals(gamma, chunks.single { it.first == "gAMA" }.second)
        assertArrayEquals(imageData, chunks.single { it.first == "IDAT" }.second)
        assertArrayEquals(sourceBytes, source.readBytes())
    }

    @Test
    fun jpegRemovesPrivacySegmentsAndKeepsCompressedScanByteExact() {
        val scan = byteArrayOf(0x11, 0xff.toByte(), 0x00, 0x22, 0xff.toByte(), 0xd0.toByte(), 0x33)
        val sourceBytes = jpeg(
            0xe0 to "JFIF\u0000".toByteArray(),
            0xe1 to "private exif".toByteArray(),
            0xe2 to "ICC_PROFILE\u0000color".toByteArray(),
            0xfe to "private comment".toByteArray(),
            0xda to byteArrayOf(0, 0, 0, 0, 0, 0),
            scan = scan
        )
        val source = temporaryFolder.newFile("source.jpg").apply { writeBytes(sourceBytes) }

        val result = ImageMetadataStripper.stripToCopy(source, temporaryFolder.newFolder("output"))
        val output = result.readBytes()

        assertFalse(output.containsBytes("private exif".toByteArray()))
        assertFalse(output.containsBytes("private comment".toByteArray()))
        assertTrue(output.containsBytes("ICC_PROFILE\u0000color".toByteArray()))
        assertArrayEquals(scan + byteArrayOf(0xff.toByte(), 0xd9.toByte()), output.takeLast(scan.size + 2).toByteArray())
    }

    @Test
    fun webpRemovesExifAndXmpWithoutChangingImagePayload() {
        val imagePayload = byteArrayOf(9, 8, 7, 6)
        val sourceBytes = webP(
            "VP8X" to byteArrayOf(0x0c, 0, 0, 0, 0, 0, 0, 0, 0, 0),
            "EXIF" to "private exif".toByteArray(),
            "XMP " to "private xmp".toByteArray(),
            "VP8 " to imagePayload
        )
        val source = temporaryFolder.newFile("source.webp").apply { writeBytes(sourceBytes) }

        val result = ImageMetadataStripper.stripToCopy(source, temporaryFolder.newFolder("output"))
        val chunks = readWebPChunks(result.readBytes())

        assertEquals(0, chunks.single { it.first == "VP8X" }.second.first().toInt())
        assertFalse(chunks.any { it.first == "EXIF" })
        assertFalse(chunks.any { it.first == "XMP " })
        assertArrayEquals(imagePayload, chunks.single { it.first == "VP8 " }.second)
    }

    private fun png(vararg chunks: Pair<String, ByteArray>): ByteArray = ByteArrayOutputStream().apply {
        write(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a))
        chunks.forEach { (type, payload) ->
            writeBigEndianInt(payload.size)
            val typeBytes = type.toByteArray(Charsets.US_ASCII)
            write(typeBytes)
            write(payload)
            val crc = CRC32().apply {
                update(typeBytes)
                update(payload)
            }.value.toInt()
            writeBigEndianInt(crc)
        }
    }.toByteArray()

    private fun readPngChunks(bytes: ByteArray): List<Pair<String, ByteArray>> {
        val result = mutableListOf<Pair<String, ByteArray>>()
        var offset = 8
        while (offset < bytes.size) {
            val length = bytes.readBigEndianInt(offset)
            val type = bytes.copyOfRange(offset + 4, offset + 8).toString(Charsets.US_ASCII)
            val payload = bytes.copyOfRange(offset + 8, offset + 8 + length)
            result += type to payload
            offset += 12 + length
            if (type == "IEND") break
        }
        return result
    }

    private fun jpeg(vararg segments: Pair<Int, ByteArray>, scan: ByteArray): ByteArray =
        ByteArrayOutputStream().apply {
            write(byteArrayOf(0xff.toByte(), 0xd8.toByte()))
            segments.forEach { (marker, payload) ->
                write(0xff)
                write(marker)
                write((payload.size + 2) ushr 8)
                write(payload.size + 2)
                write(payload)
            }
            write(scan)
            write(byteArrayOf(0xff.toByte(), 0xd9.toByte()))
        }.toByteArray()

    private fun webP(vararg chunks: Pair<String, ByteArray>): ByteArray {
        val body = ByteArrayOutputStream().apply {
            write("WEBP".toByteArray(Charsets.US_ASCII))
            chunks.forEach { (id, payload) ->
                write(id.toByteArray(Charsets.US_ASCII))
                writeLittleEndianInt(payload.size)
                write(payload)
                if (payload.size % 2 != 0) write(0)
            }
        }.toByteArray()
        return ByteArrayOutputStream().apply {
            write("RIFF".toByteArray(Charsets.US_ASCII))
            writeLittleEndianInt(body.size)
            write(body)
        }.toByteArray()
    }

    private fun readWebPChunks(bytes: ByteArray): List<Pair<String, ByteArray>> {
        val result = mutableListOf<Pair<String, ByteArray>>()
        var offset = 12
        while (offset < bytes.size) {
            val id = bytes.copyOfRange(offset, offset + 4).toString(Charsets.US_ASCII)
            val length = bytes.readLittleEndianInt(offset + 4)
            result += id to bytes.copyOfRange(offset + 8, offset + 8 + length)
            offset += 8 + length + (length and 1)
        }
        return result
    }

    private fun ByteArray.containsBytes(needle: ByteArray): Boolean =
        indices.any { start -> start + needle.size <= size && needle.indices.all { this[start + it] == needle[it] } }

    private fun ByteArray.readBigEndianInt(offset: Int): Int =
        ((this[offset].toInt() and 0xff) shl 24) or
            ((this[offset + 1].toInt() and 0xff) shl 16) or
            ((this[offset + 2].toInt() and 0xff) shl 8) or
            (this[offset + 3].toInt() and 0xff)

    private fun ByteArray.readLittleEndianInt(offset: Int): Int =
        (this[offset].toInt() and 0xff) or
            ((this[offset + 1].toInt() and 0xff) shl 8) or
            ((this[offset + 2].toInt() and 0xff) shl 16) or
            ((this[offset + 3].toInt() and 0xff) shl 24)

    private fun ByteArrayOutputStream.writeBigEndianInt(value: Int) {
        write(value ushr 24)
        write(value ushr 16)
        write(value ushr 8)
        write(value)
    }

    private fun ByteArrayOutputStream.writeLittleEndianInt(value: Int) {
        write(value)
        write(value ushr 8)
        write(value ushr 16)
        write(value ushr 24)
    }
}
