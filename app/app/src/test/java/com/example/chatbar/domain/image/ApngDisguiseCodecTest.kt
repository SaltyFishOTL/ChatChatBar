package com.example.chatbar.domain.image

import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.CRC32
import java.util.zip.DeflaterOutputStream
import java.util.zip.InflaterInputStream
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class ApngDisguiseCodecTest {
    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun gifLoopIntentMapsToApngTotalPlayCount() {
        assertEquals(1, ApngDisguiseCodec.gifLoopCountToApngPlayCount(-1))
        assertEquals(0, ApngDisguiseCodec.gifLoopCountToApngPlayCount(0))
        assertEquals(2, ApngDisguiseCodec.gifLoopCountToApngPlayCount(1))
        assertEquals(8, ApngDisguiseCodec.gifLoopCountToApngPlayCount(7))
    }

    @Test
    fun canonicalStaticDisguiseHasMarkerCrcSequenceNoChangeSentinelAndSeparatedLogo() {
        val file = temporaryFolder.newFile("canonical.apng")
        val truth = byteArrayOf(0, 0x11, 0x22, 0x33, 0x44)
        file.writeBytes(staticDisguise(truth))

        val inspection = requireNotNull(ApngDisguiseCodec.inspectDisguise(file))
        assertEquals(1, inspection.metadata.version)
        assertEquals(ApngDisguiseContentKind.STATIC, inspection.metadata.contentKind)
        assertEquals(1, inspection.metadata.contentFrameCount)
        assertEquals(1, inspection.animationFrameCount)
        assertEquals(0, inspection.playCount)

        val chunks = readChunks(file.readBytes())
        assertEquals(
            listOf("IHDR", "acTL", "tEXt", "IDAT", "fcTL", "fdAT", "fcTL", "fdAT", "IEND"),
            chunks.map(Chunk::type)
        )
        chunks.forEach { chunk -> assertTrue(chunk.crcValid) }
        assertEquals(listOf(0, 1, 2, 3), chunks.sequenceNumbers())
        assertTrue(chunks.first { it.type == "tEXt" }.data.startsWith(ApngDisguiseCodec.MARKER_KEYWORD))

        val logoRaw = inflate(chunks.first { it.type == "IDAT" }.data)
        val truthRaw = inflate(chunks.first { it.type == "fdAT" }.data.copyOfRange(4, chunks.first { it.type == "fdAT" }.data.size))
        val heartbeatControl = chunks.last { it.type == "fcTL" }.data
        val heartbeatRaw = inflate(chunks.last { it.type == "fdAT" }.data.copyOfRange(4, chunks.last { it.type == "fdAT" }.data.size))
        assertFalse(logoRaw.contentEquals(truthRaw))
        assertArrayEquals(truth, truthRaw)
        assertEquals(1, heartbeatControl[25].toInt())
        assertArrayEquals(byteArrayOf(0, 0, 0, 0, 0), heartbeatRaw)
    }

    @Test
    fun staticRestorePromotesFirstTruthFrameAndCannotBeRestoredAgain() = runBlocking {
        val source = temporaryFolder.newFile("source.apng")
        val target = File(temporaryFolder.root, "restored.png")
        val truth = byteArrayOf(0, 0x10, 0x20, 0x30, 0x40)
        source.writeBytes(staticDisguise(truth))

        val inspection = ApngDisguiseCodec.restoreDisguise(source, target)

        assertEquals(ApngDisguiseContentKind.STATIC, inspection.metadata.contentKind)
        assertFalse(ApngDisguiseCodec.containsAnimationControl(target))
        assertNull(ApngDisguiseCodec.inspectDisguise(target))
        val chunks = readChunks(target.readBytes())
        assertEquals(listOf("IHDR", "IDAT", "IEND"), chunks.map(Chunk::type))
        assertArrayEquals(truth, inflate(chunks.single { it.type == "IDAT" }.data))
    }

    @Test
    fun v2HeartbeatRemainsRestorable() = runBlocking {
        val source = temporaryFolder.newFile("v2.apng")
        val target = File(temporaryFolder.root, "v2-restored.png")
        val truth = byteArrayOf(0, 0x41, 0x42, 0x43, 0x44)
        source.writeBytes(staticDisguise(truth, version = 2))

        val inspection = requireNotNull(ApngDisguiseCodec.inspectDisguise(source))
        assertEquals(2, inspection.metadata.version)
        ApngDisguiseCodec.restoreDisguise(source, target)

        val restored = readChunks(target.readBytes()).single { it.type == "IDAT" }
        assertArrayEquals(truth, inflate(restored.data))
    }

    @Test
    fun thirdPartyMissingMarkerForgedMarkerBrokenCrcAndSequenceAreRejected() {
        val canonical = staticDisguise(byteArrayOf(0, 1, 2, 3, 4))

        val missingMarker = rewriteChunks(canonical) { chunk -> chunk.takeUnless { it.type == "tEXt" } }
        assertNull(ApngDisguiseCodec.inspectDisguise(write("missing.apng", missingMarker)))

        val forgedMarker = rewriteChunks(canonical) { chunk ->
            if (chunk.type == "tEXt") chunk.copy(data = ApngDisguiseCodec.markerBytes(ApngDisguiseContentKind.ANIMATED, 2)) else chunk
        }
        assertNull(ApngDisguiseCodec.inspectDisguise(write("forged.apng", forgedMarker)))

        var frameControlIndex = 0
        val ineffectiveV2 = rewriteChunks(staticDisguise(byteArrayOf(0, 1, 2, 3, 4), version = 2)) { chunk ->
            if (chunk.type == "fcTL" && frameControlIndex++ == 1) {
                chunk.copy(data = chunk.data.copyOf().also { it[25] = 1 })
            } else chunk
        }
        assertNull(ApngDisguiseCodec.inspectDisguise(write("ineffective-v2.apng", ineffectiveV2)))

        val brokenCrc = canonical.copyOf().also { bytes -> bytes[bytes.lastIndex] = (bytes.last().toInt() xor 1).toByte() }
        assertNull(ApngDisguiseCodec.inspectDisguise(write("crc.apng", brokenCrc)))

        var changedSequence = false
        val brokenSequence = rewriteChunks(canonical) { chunk ->
            if (!changedSequence && chunk.type == "fdAT") {
                changedSequence = true
                chunk.copy(data = chunk.data.copyOf().also { it.writeInt(0, 99) })
            } else chunk
        }
        assertNull(ApngDisguiseCodec.inspectDisguise(write("sequence.apng", brokenSequence)))
    }

    private fun staticDisguise(
        truthRaw: ByteArray,
        version: Int = ApngDisguiseCodec.FORMAT_VERSION
    ): ByteArray = ByteArrayOutputStream().apply {
        write(ApngDisguiseCodec.signatureBytes())
        writeChunk("IHDR", ByteArray(13).apply {
            writeInt(0, 1)
            writeInt(4, 1)
            this[8] = 8
            this[9] = 6
        })
        writeChunk("acTL", ByteArray(8).apply {
            writeInt(0, 2)
            writeInt(4, 0)
        })
        writeChunk("tEXt", ApngDisguiseCodec.markerBytes(ApngDisguiseContentKind.STATIC, 1, version))
        writeChunk("IDAT", deflate(byteArrayOf(0, 0x2f, 0x8e.toByte(), 0x7b, 0xff.toByte())))
        writeChunk("fcTL", frameControl(sequence = 0, blend = 0))
        writeChunk("fdAT", ByteArray(4).apply { writeInt(0, 1) } + deflate(truthRaw))
        writeChunk("fcTL", frameControl(sequence = 2, blend = if (version == 1) 1 else 0))
        writeChunk("fdAT", ByteArray(4).apply { writeInt(0, 3) } + deflate(byteArrayOf(0, 0, 0, 0, 0)))
        writeChunk("IEND", ByteArray(0))
    }.toByteArray()

    private fun frameControl(sequence: Int, blend: Int): ByteArray = ByteArray(26).apply {
        writeInt(0, sequence)
        writeInt(4, 1)
        writeInt(8, 1)
        writeUnsignedShort(20, 10)
        writeUnsignedShort(22, 100)
        this[24] = 0
        this[25] = blend.toByte()
    }

    private fun rewriteChunks(bytes: ByteArray, transform: (Chunk) -> Chunk?): ByteArray =
        ByteArrayOutputStream().apply {
            write(ApngDisguiseCodec.signatureBytes())
            readChunks(bytes).forEach { chunk -> transform(chunk)?.let { writeChunk(it.type, it.data) } }
        }.toByteArray()

    private fun readChunks(bytes: ByteArray): List<Chunk> {
        var offset = ApngDisguiseCodec.signatureBytes().size
        val chunks = mutableListOf<Chunk>()
        while (offset + 12 <= bytes.size) {
            val length = bytes.readInt(offset)
            val typeBytes = bytes.copyOfRange(offset + 4, offset + 8)
            val data = bytes.copyOfRange(offset + 8, offset + 8 + length)
            val storedCrc = bytes.readInt(offset + 8 + length).toLong() and 0xffff_ffffL
            val actualCrc = CRC32().apply {
                update(typeBytes)
                update(data)
            }.value
            chunks += Chunk(typeBytes.toString(Charsets.US_ASCII), data, storedCrc == actualCrc)
            offset += 12 + length
        }
        return chunks
    }

    private fun List<Chunk>.sequenceNumbers(): List<Int> = flatMap { chunk ->
        when (chunk.type) {
            "fcTL", "fdAT" -> listOf(chunk.data.readInt(0))
            else -> emptyList()
        }
    }

    private fun write(name: String, bytes: ByteArray): File =
        File(temporaryFolder.root, name).apply { writeBytes(bytes) }

    private fun deflate(raw: ByteArray): ByteArray = ByteArrayOutputStream().use { output ->
        DeflaterOutputStream(output).use { it.write(raw) }
        output.toByteArray()
    }

    private fun inflate(compressed: ByteArray): ByteArray =
        InflaterInputStream(compressed.inputStream()).use { it.readBytes() }

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

    private fun ByteArray.writeInt(offset: Int, value: Int) {
        this[offset] = (value ushr 24).toByte()
        this[offset + 1] = (value ushr 16).toByte()
        this[offset + 2] = (value ushr 8).toByte()
        this[offset + 3] = value.toByte()
    }

    private fun ByteArray.writeUnsignedShort(offset: Int, value: Int) {
        this[offset] = (value ushr 8).toByte()
        this[offset + 1] = value.toByte()
    }

    private fun ByteArray.startsWith(prefix: String): Boolean =
        copyOfRange(0, prefix.length).toString(Charsets.US_ASCII) == prefix

    private data class Chunk(val type: String, val data: ByteArray, val crcValid: Boolean = true)
}
