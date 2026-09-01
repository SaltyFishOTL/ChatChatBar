package com.example.chatbar.domain.image

import android.graphics.Bitmap
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.io.RandomAccessFile
import java.util.zip.CRC32
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.math.abs

internal enum class ApngDisguiseContentKind {
    STATIC,
    ANIMATED
}

internal data class ApngDisguiseMetadata(
    val version: Int,
    val contentKind: ApngDisguiseContentKind,
    val contentFrameCount: Int
)

internal data class ApngFrameControl(
    val width: Int,
    val height: Int,
    val xOffset: Int,
    val yOffset: Int,
    val delayNumerator: Int,
    val delayDenominator: Int,
    val disposeOperation: Int,
    val blendOperation: Int
)

internal data class ApngDisguiseInspection(
    val width: Int,
    val height: Int,
    val animationFrameCount: Int,
    val playCount: Int,
    val metadata: ApngDisguiseMetadata
)

internal object ApngDisguiseCodec {
    const val MIME_TYPE = "image/png"
    const val MARKER_KEYWORD = "ChatBarApngDisguise"
    const val FORMAT_VERSION = 1
    private const val MAX_SUPPORTED_FORMAT_VERSION = 2
    const val MAX_OUTPUT_BYTES = 100L * 1024 * 1024
    private const val DATA_CHUNK_BYTES = 64 * 1024

    private val signature = byteArrayOf(
        0x89.toByte(),
        'P'.code.toByte(),
        'N'.code.toByte(),
        'G'.code.toByte(),
        0x0D,
        0x0A,
        0x1A,
        0x0A
    )

    fun hasPngSignature(file: File): Boolean = runCatching {
        RandomAccessFile(file, "r").use { input ->
            if (input.length() < signature.size) return@use false
            val actual = ByteArray(signature.size)
            input.readFully(actual)
            actual.contentEquals(signature)
        }
    }.getOrDefault(false)

    fun containsAnimationControl(file: File): Boolean {
        if (!hasPngSignature(file)) return false
        return runCatching {
            RandomAccessFile(file, "r").use { input ->
                input.seek(signature.size.toLong())
                while (input.filePointer + 12 <= input.length()) {
                    val length = input.readInt()
                    if (length < 0 || input.filePointer + 4L + length + 4L > input.length()) return@use false
                    val type = ByteArray(4).also(input::readFully).toString(Charsets.US_ASCII)
                    if (type == "acTL") return@use true
                    if (type == "IEND") return@use false
                    input.seek(input.filePointer + length + 4L)
                }
                false
            }
        }.getOrDefault(false)
    }

    fun inspectDisguise(file: File): ApngDisguiseInspection? = runCatching {
        inspectParsed(parse(file))
    }.getOrNull()

    private fun inspectParsed(parsed: ParsedPng): ApngDisguiseInspection {
        val metadata = parsed.metadata ?: error("APNG 缺少 ChatBar 伪装标记")
        require(metadata.version in FORMAT_VERSION..MAX_SUPPORTED_FORMAT_VERSION) { "APNG 伪装版本不受支持" }
        require(parsed.animationControl != null) { "APNG 缺少动画控制块" }
        require(!parsed.defaultImageIsAnimationFrame) { "Logo 默认图意外进入动画" }
        require(parsed.frames.size == parsed.animationControl.frameCount) { "APNG 帧数不一致" }
        require(metadata.contentFrameCount > 0) { "APNG 真图帧数无效" }
        require(parsed.bitDepth == 8 && parsed.colorType == 6 && parsed.interlaceMethod == 0) {
            "APNG 像素格式不受支持"
        }
        require(parsed.hasCanonicalDisguiseChunkOrder()) { "APNG 伪装 chunk 顺序无效" }

        when (metadata.contentKind) {
            ApngDisguiseContentKind.STATIC -> {
                require(metadata.contentFrameCount == 1 && parsed.frames.size == 2) { "静态伪装帧结构无效" }
                require(parsed.frames[0].control.isFullCanvas(parsed.width, parsed.height)) { "静态真图帧尺寸无效" }
                require(parsed.frames[0].control.disposeOperation == 0 && parsed.frames[0].control.blendOperation == 0) {
                    "静态真图合成方式无效"
                }
                val activityFrame = parsed.frames[1].control
                require(activityFrame.width == 1 && activityFrame.height == 1 && activityFrame.disposeOperation == 0) {
                    "静态伪装保活帧无效"
                }
                if (metadata.version == FORMAT_VERSION) {
                    require(
                        activityFrame.xOffset == 0 && activityFrame.yOffset == 0 && activityFrame.blendOperation == 1
                    ) { "旧版静态伪装哨兵帧无效" }
                } else {
                    require(activityFrame.blendOperation == 0) { "静态伪装保活帧未产生真实像素差异" }
                }
            }

            ApngDisguiseContentKind.ANIMATED -> {
                require(metadata.contentFrameCount >= 2 && parsed.frames.size == metadata.contentFrameCount) {
                    "动态伪装帧结构无效"
                }
                parsed.frames.forEach { frame ->
                    require(frame.control.isFullCanvas(parsed.width, parsed.height)) { "动态真图帧尺寸无效" }
                    require(frame.control.disposeOperation == 0 && frame.control.blendOperation == 0) {
                        "动态真图合成方式无效"
                    }
                }
            }
        }
        require(parsed.frames.all { frame -> frame.dataChunks.isNotEmpty() && frame.dataChunks.all { it.type == "fdAT" } }) {
            "伪装真图数据块无效"
        }
        return ApngDisguiseInspection(
            width = parsed.width,
            height = parsed.height,
            animationFrameCount = metadata.contentFrameCount,
            playCount = parsed.animationControl.playCount,
            metadata = metadata
        )
    }

    suspend fun restoreDisguise(
        source: File,
        target: File,
        onProgress: (Float) -> Unit = {}
    ): ApngDisguiseInspection {
        val parsed = parse(source)
        val inspection = runCatching { inspectParsed(parsed) }
            .getOrElse { error("不是可还原的 ChatBar APNG 伪装图") }
        val temporary = File(target.parentFile, "${target.name}.tmp")
        try {
            temporary.parentFile?.mkdirs()
            RandomAccessFile(source, "r").use { input ->
                BufferedOutputStream(LimitedOutputStream(FileOutputStream(temporary), MAX_OUTPUT_BYTES)).use { raw ->
                    val output = PngChunkOutput(raw)
                    output.writeSignature()
                    output.writeChunk("IHDR", parsed.ihdrData)
                    var completedFrames = 0
                    fun updateProgress() {
                        completedFrames++
                        onProgress(completedFrames.toFloat() / inspection.animationFrameCount)
                    }
                    when (inspection.metadata.contentKind) {
                        ApngDisguiseContentKind.STATIC -> {
                            parsed.frames.first().dataChunks.forEach { chunk ->
                                currentCoroutineContext().ensureActive()
                                output.writeChunk("IDAT", readFramePayload(input, chunk))
                            }
                            updateProgress()
                        }

                        ApngDisguiseContentKind.ANIMATED -> {
                            output.writeChunk(
                                "acTL",
                                ByteArray(8).apply {
                                    writeInt(0, inspection.animationFrameCount)
                                    writeInt(4, inspection.playCount)
                                }
                            )
                            var sequence = 0
                            val first = parsed.frames.first()
                            output.writeChunk("fcTL", frameControlBytes(sequence++, first.control))
                            first.dataChunks.forEach { chunk ->
                                currentCoroutineContext().ensureActive()
                                output.writeChunk("IDAT", readFramePayload(input, chunk))
                            }
                            updateProgress()
                            parsed.frames.drop(1).forEach { frame ->
                                currentCoroutineContext().ensureActive()
                                output.writeChunk("fcTL", frameControlBytes(sequence++, frame.control))
                                frame.dataChunks.forEach { chunk ->
                                    val compressed = readFramePayload(input, chunk)
                                    output.writeChunk(
                                        "fdAT",
                                        ByteArray(compressed.size + 4).also { data ->
                                            data.writeInt(0, sequence++)
                                            compressed.copyInto(data, destinationOffset = 4)
                                        }
                                    )
                                }
                                updateProgress()
                            }
                        }
                    }
                    output.writeChunk("IEND", ByteArray(0))
                }
            }
            require(temporary.isFile && temporary.length() in 1..MAX_OUTPUT_BYTES) { "还原结果为空或超过 100 MB" }
            if (inspection.metadata.contentKind == ApngDisguiseContentKind.ANIMATED) {
                require(containsAnimationControl(temporary)) { "还原后的 APNG 结构无效" }
                require(inspectDisguise(temporary) == null) { "还原结果仍带有伪装标记" }
            } else {
                require(!containsAnimationControl(temporary)) { "还原后的 PNG 不应包含动画" }
            }
            require(temporary.renameTo(target)) { "无法保存还原结果" }
            return inspection
        } finally {
            temporary.delete()
        }
    }

    fun gifLoopCountToApngPlayCount(netscapeLoopCount: Int): Int = when {
        netscapeLoopCount < 0 -> 1
        netscapeLoopCount == 0 -> 0
        else -> netscapeLoopCount + 1
    }

    internal fun markerBytes(
        kind: ApngDisguiseContentKind,
        contentFrameCount: Int,
        version: Int = FORMAT_VERSION
    ): ByteArray {
        require(contentFrameCount > 0)
        require(version in FORMAT_VERSION..MAX_SUPPORTED_FORMAT_VERSION)
        val keyword = MARKER_KEYWORD.toByteArray(Charsets.US_ASCII)
        val value = "$version;${kind.name};$contentFrameCount".toByteArray(Charsets.US_ASCII)
        return ByteArray(keyword.size + 1 + value.size).also { data ->
            keyword.copyInto(data)
            value.copyInto(data, destinationOffset = keyword.size + 1)
        }
    }

    internal fun signatureBytes(): ByteArray = signature.copyOf()

    private fun parse(file: File): ParsedPng {
        require(file.isFile && file.length() in 1..MAX_OUTPUT_BYTES) { "PNG 文件不存在、为空或超过 100 MB" }
        RandomAccessFile(file, "r").use { input ->
            val actualSignature = ByteArray(signature.size).also(input::readFully)
            require(actualSignature.contentEquals(signature)) { "不是 PNG 文件" }
            var ihdrData: ByteArray? = null
            var width = 0
            var height = 0
            var bitDepth = -1
            var colorType = -1
            var interlaceMethod = -1
            var animationControl: AnimationControl? = null
            var metadata: ApngDisguiseMetadata? = null
            var markerSeen = false
            var seenIdat = false
            var seenIend = false
            var defaultImageIsAnimationFrame = false
            var expectedSequence = 0
            val frames = mutableListOf<MutableFrame>()
            val chunkTypes = mutableListOf<String>()
            var currentFrame: MutableFrame? = null

            while (input.filePointer + 12 <= input.length()) {
                val length = input.readInt()
                require(length >= 0) { "PNG chunk 长度无效" }
                val typeBytes = ByteArray(4).also(input::readFully)
                val type = typeBytes.toString(Charsets.US_ASCII)
                val dataOffset = input.filePointer
                require(dataOffset + length + 4L <= input.length()) { "PNG chunk 越界" }
                val capture = if (type in setOf("IHDR", "acTL", "fcTL", "tEXt") && length <= 4096) {
                    ByteArray(length)
                } else {
                    null
                }
                val crc = CRC32().apply { update(typeBytes) }
                val buffer = ByteArray(DATA_CHUNK_BYTES)
                var remaining = length
                var captureOffset = 0
                while (remaining > 0) {
                    val count = minOf(remaining, buffer.size)
                    input.readFully(buffer, 0, count)
                    crc.update(buffer, 0, count)
                    capture?.let {
                        buffer.copyInto(it, destinationOffset = captureOffset, startIndex = 0, endIndex = count)
                        captureOffset += count
                    }
                    remaining -= count
                }
                val storedCrc = input.readInt().toLong() and 0xFFFF_FFFFL
                require(crc.value == storedCrc) { "PNG $type chunk CRC 无效" }
                val ref = ChunkRef(type, dataOffset, length)
                chunkTypes += type

                when (type) {
                    "IHDR" -> {
                        require(ihdrData == null && capture?.size == 13) { "PNG IHDR 无效" }
                        ihdrData = capture
                        width = capture.readInt(0)
                        height = capture.readInt(4)
                        bitDepth = capture[8].toInt() and 0xFF
                        colorType = capture[9].toInt() and 0xFF
                        interlaceMethod = capture[12].toInt() and 0xFF
                        require(width > 0 && height > 0) { "PNG 尺寸无效" }
                    }

                    "acTL" -> {
                        require(!seenIdat && animationControl == null && capture?.size == 8) { "APNG acTL 位置或长度无效" }
                        animationControl = AnimationControl(capture.readInt(0), capture.readInt(4))
                        require(animationControl.frameCount > 0) { "APNG 帧数无效" }
                    }

                    "tEXt" -> if (isMarkerChunk(capture)) {
                        require(!markerSeen) { "APNG 伪装标记重复" }
                        markerSeen = true
                        metadata = parseMarker(capture) ?: error("APNG 伪装标记无效")
                    }

                    "fcTL" -> {
                        require(animationControl != null && capture?.size == 26) { "APNG fcTL 无效" }
                        val sequence = capture.readInt(0)
                        require(sequence == expectedSequence++) { "APNG 序号不连续" }
                        val control = parseFrameControl(capture)
                        require(control.width > 0 && control.height > 0) { "APNG 帧尺寸无效" }
                        require(control.xOffset >= 0 && control.yOffset >= 0) { "APNG 帧偏移无效" }
                        require(control.xOffset.toLong() + control.width <= width.toLong()) { "APNG 帧宽越界" }
                        require(control.yOffset.toLong() + control.height <= height.toLong()) { "APNG 帧高越界" }
                        require(control.disposeOperation in 0..2 && control.blendOperation in 0..1) { "APNG 合成参数无效" }
                        currentFrame = MutableFrame(control, usesIdat = !seenIdat).also(frames::add)
                        if (!seenIdat) defaultImageIsAnimationFrame = true
                    }

                    "IDAT" -> {
                        require(ihdrData != null) { "PNG IDAT 缺少 IHDR" }
                        if (seenIdat && currentFrame != null && !currentFrame.usesIdat) {
                            error("APNG IDAT 不连续")
                        }
                        seenIdat = true
                        currentFrame?.takeIf { it.usesIdat }?.dataChunks?.add(ref)
                    }

                    "fdAT" -> {
                        require(seenIdat && currentFrame != null && !currentFrame.usesIdat && length in 5..(DATA_CHUNK_BYTES + 4)) {
                            "APNG fdAT 无效"
                        }
                        input.seek(dataOffset)
                        val sequence = input.readInt()
                        require(sequence == expectedSequence++) { "APNG 序号不连续" }
                        currentFrame.dataChunks.add(ref)
                        input.seek(dataOffset + length + 4L)
                    }

                    "IEND" -> {
                        require(length == 0) { "PNG IEND 无效" }
                        seenIend = true
                        require(input.filePointer == input.length()) { "PNG IEND 后存在多余数据" }
                    }
                }
                if (seenIend) break
            }
            require(ihdrData != null && seenIdat && seenIend) { "PNG 结构不完整" }
            animationControl?.let { control ->
                require(frames.size == control.frameCount) { "APNG 声明帧数与实际不一致" }
                require(frames.all { it.dataChunks.isNotEmpty() }) { "APNG 帧缺少数据" }
            }
            return ParsedPng(
                ihdrData = ihdrData,
                width = width,
                height = height,
                bitDepth = bitDepth,
                colorType = colorType,
                interlaceMethod = interlaceMethod,
                animationControl = animationControl,
                metadata = metadata,
                defaultImageIsAnimationFrame = defaultImageIsAnimationFrame,
                frames = frames.map { FrameInfo(it.control, it.usesIdat, it.dataChunks.toList()) },
                chunkTypes = chunkTypes
            )
        }
    }

    private fun isMarkerChunk(data: ByteArray?): Boolean {
        data ?: return false
        val keyword = MARKER_KEYWORD.toByteArray(Charsets.US_ASCII)
        return data.size > keyword.size &&
            data.copyOfRange(0, keyword.size).contentEquals(keyword) &&
            data[keyword.size] == 0.toByte()
    }

    private fun parseMarker(data: ByteArray?): ApngDisguiseMetadata? {
        data ?: return null
        val separator = data.indexOf(0)
        if (separator <= 0) return null
        val keyword = data.copyOfRange(0, separator).toString(Charsets.US_ASCII)
        if (keyword != MARKER_KEYWORD) return null
        val parts = data.copyOfRange(separator + 1, data.size).toString(Charsets.US_ASCII).split(';')
        if (parts.size != 3) return null
        return ApngDisguiseMetadata(
            version = parts[0].toIntOrNull() ?: return null,
            contentKind = runCatching { ApngDisguiseContentKind.valueOf(parts[1]) }.getOrNull() ?: return null,
            contentFrameCount = parts[2].toIntOrNull() ?: return null
        )
    }

    private fun parseFrameControl(data: ByteArray): ApngFrameControl = ApngFrameControl(
        width = data.readInt(4),
        height = data.readInt(8),
        xOffset = data.readInt(12),
        yOffset = data.readInt(16),
        delayNumerator = data.readUnsignedShort(20),
        delayDenominator = data.readUnsignedShort(22).takeIf { it != 0 } ?: 100,
        disposeOperation = data[24].toInt() and 0xFF,
        blendOperation = data[25].toInt() and 0xFF
    )

    private fun frameControlBytes(sequence: Int, control: ApngFrameControl): ByteArray = ByteArray(26).apply {
        writeInt(0, sequence)
        writeInt(4, control.width)
        writeInt(8, control.height)
        writeInt(12, control.xOffset)
        writeInt(16, control.yOffset)
        writeUnsignedShort(20, control.delayNumerator)
        writeUnsignedShort(22, control.delayDenominator)
        this[24] = control.disposeOperation.toByte()
        this[25] = control.blendOperation.toByte()
    }

    private fun readFramePayload(input: RandomAccessFile, chunk: ChunkRef): ByteArray {
        require(chunk.type == "fdAT" && chunk.length >= 5) { "真图数据块无效" }
        input.seek(chunk.dataOffset + 4L)
        return ByteArray(chunk.length - 4).also(input::readFully)
    }

    private fun ApngFrameControl.isFullCanvas(canvasWidth: Int, canvasHeight: Int): Boolean =
        width == canvasWidth && height == canvasHeight && xOffset == 0 && yOffset == 0

    private data class AnimationControl(val frameCount: Int, val playCount: Int)

    private data class ChunkRef(val type: String, val dataOffset: Long, val length: Int)

    private data class MutableFrame(
        val control: ApngFrameControl,
        val usesIdat: Boolean,
        val dataChunks: MutableList<ChunkRef> = mutableListOf()
    )

    private data class FrameInfo(
        val control: ApngFrameControl,
        val usesIdat: Boolean,
        val dataChunks: List<ChunkRef>
    )

    private data class ParsedPng(
        val ihdrData: ByteArray,
        val width: Int,
        val height: Int,
        val bitDepth: Int,
        val colorType: Int,
        val interlaceMethod: Int,
        val animationControl: AnimationControl?,
        val metadata: ApngDisguiseMetadata?,
        val defaultImageIsAnimationFrame: Boolean,
        val frames: List<FrameInfo>,
        val chunkTypes: List<String>
    )

    private fun ParsedPng.hasCanonicalDisguiseChunkOrder(): Boolean {
        var index = 0
        fun take(type: String): Boolean {
            if (chunkTypes.getOrNull(index) != type) return false
            index++
            return true
        }
        if (!take("IHDR") || !take("acTL") || !take("tEXt")) return false
        var defaultDataChunks = 0
        while (chunkTypes.getOrNull(index) == "IDAT") {
            index++
            defaultDataChunks++
        }
        if (defaultDataChunks == 0) return false
        repeat(frames.size) {
            if (!take("fcTL")) return false
            var frameDataChunks = 0
            while (chunkTypes.getOrNull(index) == "fdAT") {
                index++
                frameDataChunks++
            }
            if (frameDataChunks == 0) return false
        }
        return take("IEND") && index == chunkTypes.size
    }

    internal class Writer(
        output: OutputStream,
        private val width: Int,
        private val height: Int,
        animationFrameCount: Int,
        playCount: Int,
        contentKind: ApngDisguiseContentKind,
        contentFrameCount: Int
    ) {
        private val output = PngChunkOutput(output)
        private var sequence = 0
        private var writtenFrames = 0
        private val declaredFrames = animationFrameCount

        init {
            require(width > 0 && height > 0 && animationFrameCount > 0)
            this.output.writeSignature()
            this.output.writeChunk(
                "IHDR",
                ByteArray(13).apply {
                    writeInt(0, width)
                    writeInt(4, height)
                    this[8] = 8
                    this[9] = 6
                    this[10] = 0
                    this[11] = 0
                    this[12] = 0
                }
            )
            this.output.writeChunk(
                "acTL",
                ByteArray(8).apply {
                    writeInt(0, animationFrameCount)
                    writeInt(4, playCount)
                }
            )
            this.output.writeChunk("tEXt", markerBytes(contentKind, contentFrameCount))
        }

        suspend fun writeDefaultImage(bitmap: Bitmap) {
            require(bitmap.width == width && bitmap.height == height)
            writeBitmapData(bitmap, FrameDataType.IDAT)
        }

        suspend fun writeFrame(bitmap: Bitmap, delayNumerator: Int, delayDenominator: Int) {
            writeFrame(
                bitmap = bitmap,
                control = ApngFrameControl(
                    width = bitmap.width,
                    height = bitmap.height,
                    xOffset = 0,
                    yOffset = 0,
                    delayNumerator = delayNumerator.coerceIn(0, 0xFFFF),
                    delayDenominator = delayDenominator.coerceIn(1, 0xFFFF),
                    disposeOperation = 0,
                    blendOperation = 0
                )
            )
        }

        suspend fun writeStaticHeartbeatFrame() {
            val heartbeat = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888).apply { eraseColor(0) }
            try {
                writeFrame(
                    bitmap = heartbeat,
                    control = ApngFrameControl(
                        width = 1,
                        height = 1,
                        xOffset = 0,
                        yOffset = 0,
                        delayNumerator = 10,
                        delayDenominator = 100,
                        disposeOperation = 0,
                        blendOperation = 1
                    )
                )
            } finally {
                heartbeat.recycle()
            }
        }

        fun finish() {
            require(writtenFrames == declaredFrames) { "APNG 实际帧数与声明不一致" }
            output.writeChunk("IEND", ByteArray(0))
        }

        private suspend fun writeFrame(bitmap: Bitmap, control: ApngFrameControl) {
            require(writtenFrames < declaredFrames) { "APNG 帧数超过声明" }
            output.writeChunk("fcTL", frameControlBytes(sequence++, control))
            writeBitmapData(bitmap, FrameDataType.FDAT)
            writtenFrames++
        }

        private suspend fun writeBitmapData(bitmap: Bitmap, type: FrameDataType) {
            val sink = ChunkedCompressedOutput(output, type) { sequence++ }
            val deflater = Deflater(Deflater.DEFAULT_COMPRESSION, false)
            try {
                DeflaterOutputStream(sink, deflater, DATA_CHUNK_BYTES).use { compressed ->
                    writeFilteredRows(bitmap, compressed)
                }
            } finally {
                deflater.end()
            }
        }
    }

    private enum class FrameDataType { IDAT, FDAT }

    private class ChunkedCompressedOutput(
        private val output: PngChunkOutput,
        private val type: FrameDataType,
        private val nextSequence: () -> Int
    ) : OutputStream() {
        private val buffer = ByteArray(DATA_CHUNK_BYTES)
        private var size = 0

        override fun write(value: Int) {
            if (size == buffer.size) flushChunk()
            buffer[size++] = value.toByte()
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            var sourceOffset = offset
            var remaining = length
            while (remaining > 0) {
                if (size == buffer.size) flushChunk()
                val count = minOf(remaining, buffer.size - size)
                bytes.copyInto(buffer, destinationOffset = size, startIndex = sourceOffset, endIndex = sourceOffset + count)
                sourceOffset += count
                size += count
                remaining -= count
            }
        }

        override fun flush() {
            flushChunk()
        }

        override fun close() {
            flushChunk()
        }

        private fun flushChunk() {
            if (size == 0) return
            when (type) {
                FrameDataType.IDAT -> output.writeChunk("IDAT", buffer.copyOf(size))
                FrameDataType.FDAT -> output.writeChunk(
                    "fdAT",
                    ByteArray(size + 4).also { data ->
                        data.writeInt(0, nextSequence())
                        buffer.copyInto(data, destinationOffset = 4, startIndex = 0, endIndex = size)
                    }
                )
            }
            size = 0
        }
    }

    private class PngChunkOutput(private val output: OutputStream) {
        fun writeSignature() {
            output.write(signature)
        }

        fun writeChunk(type: String, data: ByteArray) {
            val typeBytes = type.toByteArray(Charsets.US_ASCII)
            require(typeBytes.size == 4)
            output.writeInt(data.size)
            output.write(typeBytes)
            output.write(data)
            val crc = CRC32().apply {
                update(typeBytes)
                update(data)
            }
            output.writeInt(crc.value.toInt())
        }
    }

    private class LimitedOutputStream(
        private val delegate: OutputStream,
        private val maxBytes: Long
    ) : OutputStream() {
        private var written = 0L

        override fun write(value: Int) {
            reserve(1)
            delegate.write(value)
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            reserve(length)
            delegate.write(bytes, offset, length)
        }

        override fun flush() = delegate.flush()

        override fun close() = delegate.close()

        private fun reserve(count: Int) {
            require(count >= 0 && written + count <= maxBytes) { "APNG 输出超过 100 MB" }
            written += count
        }
    }

    internal fun limitedFileOutput(file: File): OutputStream =
        BufferedOutputStream(LimitedOutputStream(FileOutputStream(file), MAX_OUTPUT_BYTES))

    private suspend fun writeFilteredRows(bitmap: Bitmap, output: OutputStream) {
        val width = bitmap.width
        val raw = ByteArray(width * 4)
        val previous = ByteArray(raw.size)
        val candidates = Array(4) { ByteArray(raw.size + 1) }
        val pixels = IntArray(width)
        for (y in 0 until bitmap.height) {
            currentCoroutineContext().ensureActive()
            bitmap.getPixels(pixels, 0, width, 0, y, width, 1)
            for (x in 0 until width) {
                val color = pixels[x]
                val offset = x * 4
                raw[offset] = ((color ushr 16) and 0xFF).toByte()
                raw[offset + 1] = ((color ushr 8) and 0xFF).toByte()
                raw[offset + 2] = (color and 0xFF).toByte()
                raw[offset + 3] = ((color ushr 24) and 0xFF).toByte()
            }
            val filters = intArrayOf(0, 1, 2, 4)
            var bestIndex = 0
            var bestScore = Long.MAX_VALUE
            filters.forEachIndexed { candidateIndex, filter ->
                val target = candidates[candidateIndex]
                target[0] = filter.toByte()
                var score = 0L
                for (index in raw.indices) {
                    val current = raw[index].toInt() and 0xFF
                    val left = if (index >= 4) raw[index - 4].toInt() and 0xFF else 0
                    val up = previous[index].toInt() and 0xFF
                    val upperLeft = if (index >= 4) previous[index - 4].toInt() and 0xFF else 0
                    val filtered = when (filter) {
                        1 -> current - left
                        2 -> current - up
                        4 -> current - paeth(left, up, upperLeft)
                        else -> current
                    }
                    target[index + 1] = filtered.toByte()
                    score += abs(target[index + 1].toInt()).toLong()
                }
                if (score < bestScore) {
                    bestScore = score
                    bestIndex = candidateIndex
                }
            }
            output.write(candidates[bestIndex])
            raw.copyInto(previous)
        }
    }

    private fun paeth(left: Int, up: Int, upperLeft: Int): Int {
        val prediction = left + up - upperLeft
        val leftDistance = abs(prediction - left)
        val upDistance = abs(prediction - up)
        val upperLeftDistance = abs(prediction - upperLeft)
        return when {
            leftDistance <= upDistance && leftDistance <= upperLeftDistance -> left
            upDistance <= upperLeftDistance -> up
            else -> upperLeft
        }
    }
}

private fun ByteArray.readInt(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 24) or
        ((this[offset + 1].toInt() and 0xFF) shl 16) or
        ((this[offset + 2].toInt() and 0xFF) shl 8) or
        (this[offset + 3].toInt() and 0xFF)

private fun ByteArray.readUnsignedShort(offset: Int): Int =
    ((this[offset].toInt() and 0xFF) shl 8) or (this[offset + 1].toInt() and 0xFF)

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

private fun OutputStream.writeInt(value: Int) {
    write((value ushr 24) and 0xFF)
    write((value ushr 16) and 0xFF)
    write((value ushr 8) and 0xFF)
    write(value and 0xFF)
}
