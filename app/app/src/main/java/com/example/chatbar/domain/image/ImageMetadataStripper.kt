package com.example.chatbar.domain.image

import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.DataInputStream
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.UUID
import java.util.zip.CRC32

internal object ImageMetadataStripper {
    fun stripToCopy(source: File, outputDirectory: File): File {
        require(source.isFile && source.length() > 0) { "图片文件不存在或为空" }
        val format = detectFormat(source)
        check(outputDirectory.exists() || outputDirectory.mkdirs()) { "无法创建图片处理目录" }
        val target = File(
            outputDirectory,
            "metadata_stripped_${UUID.randomUUID()}.${format.extension}"
        )
        val temporary = File(outputDirectory, "${target.name}.tmp")
        try {
            when (format) {
                ImageFormat.Png -> stripPng(source, temporary)
                ImageFormat.Jpeg -> stripJpeg(source, temporary)
                ImageFormat.WebP -> stripWebP(source, temporary)
                ImageFormat.Gif -> stripGif(source, temporary)
            }
            check(temporary.isFile && temporary.length() > 0) { "去除元数据后的图片为空" }
            check(temporary.renameTo(target)) { "无法保存无元数据图片" }
            return target
        } catch (error: Throwable) {
            temporary.delete()
            target.delete()
            throw error
        }
    }

    private fun stripPng(source: File, target: File) {
        DataInputStream(BufferedInputStream(source.inputStream())).use { input ->
            BufferedOutputStream(target.outputStream()).use { output ->
                val signature = ByteArray(PNG_SIGNATURE.size)
                input.readFully(signature)
                require(signature.contentEquals(PNG_SIGNATURE)) { "PNG 文件头无效" }
                output.write(signature)
                var reachedEnd = false
                while (!reachedEnd) {
                    val length = input.readUnsignedInt()
                    require(length <= source.length()) { "PNG 数据块长度无效" }
                    val typeBytes = ByteArray(4)
                    input.readFully(typeBytes)
                    val type = typeBytes.toString(Charsets.US_ASCII)
                    when {
                        type == PNG_EXIF_CHUNK -> {
                            require(length <= MAX_EXIF_BYTES) { "图片 EXIF 元数据过大" }
                            val payload = ByteArray(length.toInt())
                            input.readFully(payload)
                            input.skipFully(PNG_CRC_BYTES)
                            parseExifOrientation(payload)?.takeIf { it != NORMAL_ORIENTATION }?.let { orientation ->
                                writePngChunk(output, type, buildMinimalTiffOrientation(orientation))
                            }
                        }
                        isPngImageChunk(typeBytes, type) -> {
                            output.writeUnsignedInt(length)
                            output.write(typeBytes)
                            input.copyExactlyTo(output, length)
                            input.copyExactlyTo(output, PNG_CRC_BYTES)
                        }
                        else -> input.skipFully(length + PNG_CRC_BYTES)
                    }
                    reachedEnd = type == PNG_END_CHUNK
                }
            }
        }
    }

    private fun stripJpeg(source: File, target: File) {
        DataInputStream(BufferedInputStream(source.inputStream())).use { input ->
            BufferedOutputStream(target.outputStream()).use { output ->
                require(input.readUnsignedByte() == JPEG_MARKER_PREFIX && input.readUnsignedByte() == JPEG_START) {
                    "JPEG 文件头无效"
                }
                output.write(JPEG_MARKER_PREFIX)
                output.write(JPEG_START)
                var pendingMarker: Int? = null
                var orientationWritten = false
                var jfifWritten = false
                while (true) {
                    val marker = pendingMarker ?: input.readJpegMarker()
                    pendingMarker = null
                    when {
                        marker == JPEG_END -> {
                            output.writeJpegMarker(marker)
                            return
                        }
                        marker in JPEG_STANDALONE_MARKERS -> output.writeJpegMarker(marker)
                        else -> {
                            val segmentLength = input.readUnsignedShort()
                            require(segmentLength >= 2) { "JPEG 数据段长度无效" }
                            val payload = ByteArray(segmentLength - 2)
                            input.readFully(payload)
                            when {
                                marker == JPEG_JFIF_APP -> {
                                    if (!jfifWritten && payload.startsWith(JPEG_JFIF_PREFIX)) {
                                        output.writeJpegSegment(marker, MINIMAL_JFIF_PAYLOAD)
                                        jfifWritten = true
                                    }
                                }
                                marker == JPEG_EXIF_APP -> {
                                    val orientation = parseExifOrientation(payload)
                                    if (!orientationWritten && orientation != null && orientation != NORMAL_ORIENTATION) {
                                        output.writeJpegSegment(
                                            marker,
                                            buildMinimalExifOrientation(orientation, includeExifPrefix = true)
                                        )
                                        orientationWritten = true
                                    }
                                }
                                shouldKeepJpegSegment(marker, payload) -> output.writeJpegSegment(marker, payload)
                            }
                            if (marker == JPEG_SCAN) pendingMarker = input.copyJpegScanTo(output)
                        }
                    }
                }
            }
        }
    }

    private fun stripWebP(source: File, target: File) {
        val preservedOrientation = findWebPOrientation(source)?.takeIf { it.value != NORMAL_ORIENTATION }
        DataInputStream(BufferedInputStream(source.inputStream())).use { input ->
            val header = ByteArray(WEBP_HEADER_BYTES)
            input.readFully(header)
            require(
                header.copyOfRange(0, 4).toString(Charsets.US_ASCII) == RIFF_ID &&
                    header.copyOfRange(8, 12).toString(Charsets.US_ASCII) == WEBP_ID
            ) { "WebP 文件头无效" }
            val declaredEnd = input.readLittleEndianUnsignedInt(header, 4) + RIFF_HEADER_BYTES
            require(declaredEnd <= source.length()) { "WebP 文件长度无效" }
            RandomAccessFile(target, "rw").use { output ->
                output.setLength(0)
                output.write(RIFF_ID.toByteArray(Charsets.US_ASCII))
                output.writeLittleEndianUnsignedInt(0)
                output.write(WEBP_ID.toByteArray(Charsets.US_ASCII))
                var sourceOffset = WEBP_HEADER_BYTES.toLong()
                var orientationWritten = false
                while (sourceOffset < declaredEnd) {
                    val chunkHeader = ByteArray(WEBP_CHUNK_HEADER_BYTES)
                    input.readFully(chunkHeader)
                    sourceOffset += WEBP_CHUNK_HEADER_BYTES
                    val chunkId = chunkHeader.copyOfRange(0, 4).toString(Charsets.US_ASCII)
                    val chunkLength = input.readLittleEndianUnsignedInt(chunkHeader, 4)
                    val paddedLength = chunkLength + (chunkLength and 1L)
                    require(sourceOffset + paddedLength <= declaredEnd) { "WebP 数据块长度无效" }
                    when (chunkId) {
                        WEBP_EXIF_CHUNK -> {
                            if (!orientationWritten && preservedOrientation != null) {
                                input.skipFully(paddedLength)
                                val payload = buildMinimalExifOrientation(
                                    preservedOrientation.value,
                                    preservedOrientation.includeExifPrefix
                                )
                                output.writeWebPChunk(chunkId, payload)
                                orientationWritten = true
                            } else {
                                input.skipFully(paddedLength)
                            }
                        }
                        WEBP_XMP_CHUNK -> input.skipFully(paddedLength)
                        WEBP_EXTENDED_CHUNK -> {
                            require(chunkLength >= 1) { "WebP VP8X 数据块无效" }
                            output.write(chunkHeader)
                            val flags = input.readUnsignedByte()
                            val metadataFlags = if (preservedOrientation == null) {
                                WEBP_EXIF_FLAG or WEBP_XMP_FLAG
                            } else {
                                WEBP_XMP_FLAG
                            }
                            output.write(flags and metadataFlags.inv())
                            input.copyExactlyTo(output, chunkLength - 1)
                            input.copyExactlyTo(output, paddedLength - chunkLength)
                        }
                        else -> {
                            output.write(chunkHeader)
                            input.copyExactlyTo(output, paddedLength)
                        }
                    }
                    sourceOffset += paddedLength
                }
                val outputRiffSize = output.length() - RIFF_HEADER_BYTES
                output.seek(4)
                output.writeLittleEndianUnsignedInt(outputRiffSize)
            }
        }
    }

    private fun stripGif(source: File, target: File) {
        DataInputStream(BufferedInputStream(source.inputStream())).use { input ->
            BufferedOutputStream(target.outputStream()).use { output ->
                val header = ByteArray(GIF_HEADER_BYTES)
                input.readFully(header)
                val signature = header.copyOfRange(0, 6).toString(Charsets.US_ASCII)
                require(signature == GIF_87A || signature == GIF_89A) { "GIF 文件头无效" }
                output.write(header)
                val globalColorTableSize = gifColorTableBytes(header[10].toInt())
                input.copyExactlyTo(output, globalColorTableSize)
                while (true) {
                    when (val introducer = input.readUnsignedByte()) {
                        GIF_TRAILER -> {
                            output.write(introducer)
                            return
                        }
                        GIF_IMAGE_SEPARATOR -> {
                            output.write(introducer)
                            val descriptor = ByteArray(GIF_IMAGE_DESCRIPTOR_BYTES)
                            input.readFully(descriptor)
                            output.write(descriptor)
                            input.copyExactlyTo(output, gifColorTableBytes(descriptor[8].toInt()))
                            output.write(input.readUnsignedByte())
                            input.copyGifSubBlocksTo(output)
                        }
                        GIF_EXTENSION -> {
                            val label = input.readUnsignedByte()
                            when (label) {
                                GIF_COMMENT_EXTENSION -> input.skipGifSubBlocks()
                                GIF_APPLICATION_EXTENSION -> {
                                    val firstBlockSize = input.readUnsignedByte()
                                    val firstBlock = ByteArray(firstBlockSize)
                                    input.readFully(firstBlock)
                                    val applicationId = firstBlock.toString(Charsets.US_ASCII)
                                    if (applicationId in GIF_RENDERING_APPLICATION_IDS) {
                                        output.write(introducer)
                                        output.write(label)
                                        output.write(firstBlockSize)
                                        output.write(firstBlock)
                                        input.copyGifSubBlocksTo(output)
                                    } else {
                                        input.skipGifSubBlocks()
                                    }
                                }
                                else -> {
                                    output.write(introducer)
                                    output.write(label)
                                    input.copyGifSubBlocksTo(output)
                                }
                            }
                        }
                        else -> error("GIF 数据块类型无效: $introducer")
                    }
                }
            }
        }
    }

    private fun findWebPOrientation(source: File): ExifOrientation? {
        DataInputStream(BufferedInputStream(source.inputStream())).use { input ->
            val header = ByteArray(WEBP_HEADER_BYTES)
            input.readFully(header)
            val declaredEnd = input.readLittleEndianUnsignedInt(header, 4) + RIFF_HEADER_BYTES
            var sourceOffset = WEBP_HEADER_BYTES.toLong()
            while (sourceOffset < declaredEnd) {
                val chunkHeader = ByteArray(WEBP_CHUNK_HEADER_BYTES)
                input.readFully(chunkHeader)
                sourceOffset += WEBP_CHUNK_HEADER_BYTES
                val chunkId = chunkHeader.copyOfRange(0, 4).toString(Charsets.US_ASCII)
                val chunkLength = input.readLittleEndianUnsignedInt(chunkHeader, 4)
                val paddedLength = chunkLength + (chunkLength and 1L)
                require(sourceOffset + paddedLength <= declaredEnd) { "WebP 数据块长度无效" }
                if (chunkId == WEBP_EXIF_CHUNK) {
                    require(chunkLength <= MAX_EXIF_BYTES) { "图片 EXIF 元数据过大" }
                    val payload = ByteArray(chunkLength.toInt())
                    input.readFully(payload)
                    input.skipFully(paddedLength - chunkLength)
                    parseExifOrientation(payload)?.let { value ->
                        return ExifOrientation(value, payload.startsWith(EXIF_PREFIX))
                    }
                } else {
                    input.skipFully(paddedLength)
                }
                sourceOffset += paddedLength
            }
        }
        return null
    }

    private fun detectFormat(source: File): ImageFormat {
        val header = ByteArray(WEBP_HEADER_BYTES)
        val bytesRead = source.inputStream().buffered().use { it.readUpTo(header) }
        return when {
            bytesRead >= PNG_SIGNATURE.size && header.copyOf(PNG_SIGNATURE.size).contentEquals(PNG_SIGNATURE) -> ImageFormat.Png
            bytesRead >= 2 && header[0].toInt() and 0xff == JPEG_MARKER_PREFIX && header[1].toInt() and 0xff == JPEG_START -> ImageFormat.Jpeg
            bytesRead >= 6 && header.copyOfRange(0, 6).toString(Charsets.US_ASCII) in setOf(GIF_87A, GIF_89A) -> ImageFormat.Gif
            bytesRead >= WEBP_HEADER_BYTES &&
                header.copyOfRange(0, 4).toString(Charsets.US_ASCII) == RIFF_ID &&
                header.copyOfRange(8, 12).toString(Charsets.US_ASCII) == WEBP_ID -> ImageFormat.WebP
            else -> error("暂不支持此图片格式无损去除元数据")
        }
    }

    private fun isPngImageChunk(typeBytes: ByteArray, type: String): Boolean =
        typeBytes[0].toInt() and PNG_ANCILLARY_BIT == 0 || type in PNG_APPEARANCE_CHUNKS

    private fun shouldKeepJpegSegment(marker: Int, payload: ByteArray): Boolean = when {
        marker == JPEG_COMMENT -> false
        marker == JPEG_ICC_APP -> payload.startsWith(JPEG_ICC_PREFIX)
        marker == JPEG_ADOBE_APP -> true
        marker in JPEG_APP_MARKERS -> false
        else -> true
    }

    private fun parseExifOrientation(payload: ByteArray): Int? {
        val tiffStart = if (payload.startsWith(EXIF_PREFIX)) EXIF_PREFIX.size else 0
        if (payload.size < tiffStart + TIFF_HEADER_BYTES) return null
        val littleEndian = when {
            payload[tiffStart] == 'I'.code.toByte() && payload[tiffStart + 1] == 'I'.code.toByte() -> true
            payload[tiffStart] == 'M'.code.toByte() && payload[tiffStart + 1] == 'M'.code.toByte() -> false
            else -> return null
        }
        if (payload.readUnsignedShort(tiffStart + 2, littleEndian) != TIFF_MAGIC) return null
        val ifdOffset = payload.readUnsignedInt(tiffStart + 4, littleEndian)
        if (ifdOffset > Int.MAX_VALUE) return null
        val ifdStart = tiffStart + ifdOffset.toInt()
        if (ifdStart < tiffStart || ifdStart + 2 > payload.size) return null
        val entryCount = payload.readUnsignedShort(ifdStart, littleEndian)
        repeat(entryCount) { index ->
            val entryOffset = ifdStart + 2 + index * TIFF_ENTRY_BYTES
            if (entryOffset < 0 || entryOffset + TIFF_ENTRY_BYTES > payload.size) return null
            val tag = payload.readUnsignedShort(entryOffset, littleEndian)
            if (tag == TIFF_ORIENTATION_TAG) {
                val type = payload.readUnsignedShort(entryOffset + 2, littleEndian)
                val count = payload.readUnsignedInt(entryOffset + 4, littleEndian)
                if (type != TIFF_SHORT_TYPE || count < 1) return null
                return payload.readUnsignedShort(entryOffset + 8, littleEndian)
                    .takeIf { it in MIN_ORIENTATION..MAX_ORIENTATION }
            }
        }
        return null
    }

    private fun buildMinimalExifOrientation(orientation: Int, includeExifPrefix: Boolean): ByteArray {
        val tiff = buildMinimalTiffOrientation(orientation)
        return if (includeExifPrefix) EXIF_PREFIX + tiff else tiff
    }

    private fun buildMinimalTiffOrientation(orientation: Int): ByteArray = ByteArray(MINIMAL_TIFF_BYTES).apply {
        this[0] = 'I'.code.toByte()
        this[1] = 'I'.code.toByte()
        writeUnsignedShort(2, TIFF_MAGIC)
        writeUnsignedInt(4, TIFF_FIRST_IFD_OFFSET.toLong())
        writeUnsignedShort(8, 1)
        writeUnsignedShort(10, TIFF_ORIENTATION_TAG)
        writeUnsignedShort(12, TIFF_SHORT_TYPE)
        writeUnsignedInt(14, 1)
        writeUnsignedShort(18, orientation)
    }

    private fun writePngChunk(output: OutputStream, type: String, payload: ByteArray) {
        val typeBytes = type.toByteArray(Charsets.US_ASCII)
        output.writeUnsignedInt(payload.size.toLong())
        output.write(typeBytes)
        output.write(payload)
        val crc = CRC32().apply {
            update(typeBytes)
            update(payload)
        }.value
        output.writeUnsignedInt(crc)
    }

    private fun DataInputStream.readJpegMarker(): Int {
        require(readUnsignedByte() == JPEG_MARKER_PREFIX) { "JPEG 标记无效" }
        var marker = readUnsignedByte()
        while (marker == JPEG_MARKER_PREFIX) marker = readUnsignedByte()
        require(marker != 0) { "JPEG 标记无效" }
        return marker
    }

    private fun DataInputStream.copyJpegScanTo(output: OutputStream): Int {
        while (true) {
            val value = readUnsignedByte()
            if (value != JPEG_MARKER_PREFIX) {
                output.write(value)
                continue
            }
            var prefixCount = 1
            var marker = readUnsignedByte()
            while (marker == JPEG_MARKER_PREFIX) {
                prefixCount++
                marker = readUnsignedByte()
            }
            when {
                marker == 0 || marker in JPEG_RESTART_MARKERS -> {
                    repeat(prefixCount) { output.write(JPEG_MARKER_PREFIX) }
                    output.write(marker)
                }
                else -> {
                    repeat(prefixCount - 1) { output.write(JPEG_MARKER_PREFIX) }
                    return marker
                }
            }
        }
    }

    private fun OutputStream.writeJpegMarker(marker: Int) {
        write(JPEG_MARKER_PREFIX)
        write(marker)
    }

    private fun OutputStream.writeJpegSegment(marker: Int, payload: ByteArray) {
        writeJpegMarker(marker)
        val length = payload.size + 2
        write(length ushr 8)
        write(length)
        write(payload)
    }

    private fun DataInputStream.copyGifSubBlocksTo(output: OutputStream) {
        while (true) {
            val length = readUnsignedByte()
            output.write(length)
            if (length == 0) return
            copyExactlyTo(output, length.toLong())
        }
    }

    private fun DataInputStream.skipGifSubBlocks() {
        while (true) {
            val length = readUnsignedByte()
            if (length == 0) return
            skipFully(length.toLong())
        }
    }

    private fun gifColorTableBytes(packed: Int): Long =
        if (packed and GIF_COLOR_TABLE_FLAG == 0) 0 else 3L * (1 shl ((packed and 0x07) + 1))

    private fun InputStream.copyExactlyTo(output: OutputStream, byteCount: Long) {
        var remaining = byteCount
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        while (remaining > 0) {
            val read = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            check(read >= 0) { "图片文件提前结束" }
            if (read == 0) continue
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun InputStream.skipFully(byteCount: Long) {
        var remaining = byteCount
        while (remaining > 0) {
            val skipped = skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
            } else {
                check(read() >= 0) { "图片文件提前结束" }
                remaining--
            }
        }
    }

    private fun InputStream.readUpTo(buffer: ByteArray): Int {
        var offset = 0
        while (offset < buffer.size) {
            val read = read(buffer, offset, buffer.size - offset)
            if (read < 0) break
            if (read == 0) continue
            offset += read
        }
        return offset
    }

    private fun DataInputStream.readUnsignedInt(): Long = readInt().toLong() and UINT_MASK

    private fun DataInputStream.readLittleEndianUnsignedInt(bytes: ByteArray, offset: Int): Long =
        (bytes[offset].toLong() and 0xff) or
            ((bytes[offset + 1].toLong() and 0xff) shl 8) or
            ((bytes[offset + 2].toLong() and 0xff) shl 16) or
            ((bytes[offset + 3].toLong() and 0xff) shl 24)

    private fun OutputStream.writeUnsignedInt(value: Long) {
        write((value ushr 24).toInt())
        write((value ushr 16).toInt())
        write((value ushr 8).toInt())
        write(value.toInt())
    }

    private fun RandomAccessFile.writeLittleEndianUnsignedInt(value: Long) {
        require(value in 0..UINT_MASK) { "WebP 文件过大" }
        write(value.toInt())
        write((value ushr 8).toInt())
        write((value ushr 16).toInt())
        write((value ushr 24).toInt())
    }

    private fun RandomAccessFile.writeWebPChunk(id: String, payload: ByteArray) {
        write(id.toByteArray(Charsets.US_ASCII))
        writeLittleEndianUnsignedInt(payload.size.toLong())
        write(payload)
        if (payload.size % 2 != 0) write(0)
    }

    private fun DataInputStream.copyExactlyTo(output: RandomAccessFile, byteCount: Long) {
        var remaining = byteCount
        val buffer = ByteArray(COPY_BUFFER_BYTES)
        while (remaining > 0) {
            val read = read(buffer, 0, minOf(buffer.size.toLong(), remaining).toInt())
            check(read >= 0) { "图片文件提前结束" }
            if (read == 0) continue
            output.write(buffer, 0, read)
            remaining -= read
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray): Boolean =
        size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }

    private fun ByteArray.readUnsignedShort(offset: Int, littleEndian: Boolean): Int = if (littleEndian) {
        (this[offset].toInt() and 0xff) or ((this[offset + 1].toInt() and 0xff) shl 8)
    } else {
        ((this[offset].toInt() and 0xff) shl 8) or (this[offset + 1].toInt() and 0xff)
    }

    private fun ByteArray.readUnsignedInt(offset: Int, littleEndian: Boolean): Long = if (littleEndian) {
        (this[offset].toLong() and 0xff) or
            ((this[offset + 1].toLong() and 0xff) shl 8) or
            ((this[offset + 2].toLong() and 0xff) shl 16) or
            ((this[offset + 3].toLong() and 0xff) shl 24)
    } else {
        ((this[offset].toLong() and 0xff) shl 24) or
            ((this[offset + 1].toLong() and 0xff) shl 16) or
            ((this[offset + 2].toLong() and 0xff) shl 8) or
            (this[offset + 3].toLong() and 0xff)
    }

    private fun ByteArray.writeUnsignedShort(offset: Int, value: Int) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
    }

    private fun ByteArray.writeUnsignedInt(offset: Int, value: Long) {
        this[offset] = value.toByte()
        this[offset + 1] = (value ushr 8).toByte()
        this[offset + 2] = (value ushr 16).toByte()
        this[offset + 3] = (value ushr 24).toByte()
    }

    private enum class ImageFormat(val extension: String) {
        Png("png"),
        Jpeg("jpg"),
        WebP("webp"),
        Gif("gif")
    }

    private data class ExifOrientation(val value: Int, val includeExifPrefix: Boolean)

    private val PNG_SIGNATURE = byteArrayOf(
        0x89.toByte(), 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    )
    private val PNG_APPEARANCE_CHUNKS = setOf(
        "acTL", "bKGD", "cHRM", "fcTL", "fdAT", "gAMA", "iCCP", "sBIT", "sRGB", "tRNS"
    )
    private val EXIF_PREFIX = byteArrayOf('E'.code.toByte(), 'x'.code.toByte(), 'i'.code.toByte(), 'f'.code.toByte(), 0, 0)
    private val JPEG_JFIF_PREFIX = "JFIF\u0000".toByteArray(Charsets.US_ASCII)
    private val JPEG_ICC_PREFIX = "ICC_PROFILE\u0000".toByteArray(Charsets.US_ASCII)
    private val MINIMAL_JFIF_PAYLOAD = byteArrayOf(
        'J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0,
        1, 1, 0, 0, 1, 0, 1, 0, 0
    )
    private val GIF_RENDERING_APPLICATION_IDS = setOf("NETSCAPE2.0", "ANIMEXTS1.0", "ICCRGBG1012")

    private const val PNG_EXIF_CHUNK = "eXIf"
    private const val PNG_END_CHUNK = "IEND"
    private const val PNG_ANCILLARY_BIT = 0x20
    private const val PNG_CRC_BYTES = 4L
    private const val JPEG_MARKER_PREFIX = 0xff
    private const val JPEG_START = 0xd8
    private const val JPEG_END = 0xd9
    private const val JPEG_SCAN = 0xda
    private const val JPEG_COMMENT = 0xfe
    private const val JPEG_JFIF_APP = 0xe0
    private const val JPEG_EXIF_APP = 0xe1
    private const val JPEG_ICC_APP = 0xe2
    private const val JPEG_ADOBE_APP = 0xee
    private val JPEG_APP_MARKERS = 0xe0..0xef
    private val JPEG_RESTART_MARKERS = 0xd0..0xd7
    private val JPEG_STANDALONE_MARKERS = JPEG_RESTART_MARKERS + setOf(JPEG_START, 0x01)
    private const val RIFF_ID = "RIFF"
    private const val WEBP_ID = "WEBP"
    private const val WEBP_EXIF_CHUNK = "EXIF"
    private const val WEBP_XMP_CHUNK = "XMP "
    private const val WEBP_EXTENDED_CHUNK = "VP8X"
    private const val WEBP_EXIF_FLAG = 0x08
    private const val WEBP_XMP_FLAG = 0x04
    private const val WEBP_HEADER_BYTES = 12
    private const val WEBP_CHUNK_HEADER_BYTES = 8
    private const val RIFF_HEADER_BYTES = 8L
    private const val GIF_87A = "GIF87a"
    private const val GIF_89A = "GIF89a"
    private const val GIF_HEADER_BYTES = 13
    private const val GIF_IMAGE_DESCRIPTOR_BYTES = 9
    private const val GIF_TRAILER = 0x3b
    private const val GIF_IMAGE_SEPARATOR = 0x2c
    private const val GIF_EXTENSION = 0x21
    private const val GIF_COMMENT_EXTENSION = 0xfe
    private const val GIF_APPLICATION_EXTENSION = 0xff
    private const val GIF_COLOR_TABLE_FLAG = 0x80
    private const val TIFF_HEADER_BYTES = 8
    private const val TIFF_ENTRY_BYTES = 12
    private const val TIFF_MAGIC = 42
    private const val TIFF_FIRST_IFD_OFFSET = 8
    private const val TIFF_ORIENTATION_TAG = 0x0112
    private const val TIFF_SHORT_TYPE = 3
    private const val MINIMAL_TIFF_BYTES = 26
    private const val NORMAL_ORIENTATION = 1
    private const val MIN_ORIENTATION = 1
    private const val MAX_ORIENTATION = 8
    private const val MAX_EXIF_BYTES = 16L * 1024 * 1024
    private const val COPY_BUFFER_BYTES = 64 * 1024
    private const val UINT_MASK = 0xffffffffL
}
