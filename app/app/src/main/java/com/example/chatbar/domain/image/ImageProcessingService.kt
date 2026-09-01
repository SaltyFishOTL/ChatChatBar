package com.example.chatbar.domain.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.provider.OpenableColumns
import com.bumptech.glide.gifdecoder.GifDecoder
import com.bumptech.glide.gifdecoder.GifHeader
import com.bumptech.glide.gifdecoder.GifHeaderParser
import com.bumptech.glide.gifdecoder.StandardGifDecoder
import com.example.chatbar.R
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID
import kotlin.math.min
import kotlin.math.roundToInt

enum class ProcessImageKind {
    STATIC,
    GIF,
    CHATBAR_DISGUISE_APNG,
    OTHER_APNG
}

enum class ProcessedImageOperation {
    APNG_DISGUISE,
    APNG_RESTORE
}

data class ImportedProcessImage(
    val path: String,
    val displayName: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val frameCount: Int,
    val kind: ProcessImageKind = ProcessImageKind.STATIC
) {
    val isAnimatedGif: Boolean get() = kind == ProcessImageKind.GIF
    val isApng: Boolean get() = kind == ProcessImageKind.CHATBAR_DISGUISE_APNG || kind == ProcessImageKind.OTHER_APNG
    val canRestoreApng: Boolean get() = kind == ProcessImageKind.CHATBAR_DISGUISE_APNG
}

data class ProcessedImage(
    val path: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val frameCount: Int,
    val isAnimated: Boolean = frameCount > 1,
    val operation: ProcessedImageOperation = ProcessedImageOperation.APNG_DISGUISE
)

class ImageProcessingService(private val context: Context) {
    private val workDirectory = File(context.filesDir, "images/image-processing")

    suspend fun importImage(uri: Uri): ImportedProcessImage {
        cleanupStaleTemporaryFiles()
        workDirectory.mkdirs()
        val resolver = context.contentResolver
        val displayName = queryDisplayName(uri) ?: "image"
        val declaredMimeType = resolver.getType(uri)
        val extension = extensionFor(displayName, declaredMimeType)
        val target = File(workDirectory, "source_${UUID.randomUUID()}.$extension")
        try {
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().buffered().use { output -> input.copyTo(output) }
            } ?: error("无法打开所选图片")
            require(target.length() in 1..MAX_INPUT_BYTES) { "图片为空或超过 100 MB" }
            return inspectFile(target.absolutePath, displayName)
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    suspend fun importFile(path: String, displayName: String): ImportedProcessImage {
        cleanupStaleTemporaryFiles()
        workDirectory.mkdirs()
        val source = File(path)
        require(source.isFile) { "共享图片文件不存在" }
        require(source.length() in 1..MAX_INPUT_BYTES) { "图片为空或超过 100 MB" }
        val extension = extensionFor(displayName, null)
        val target = File(workDirectory, "source_${UUID.randomUUID()}.$extension")
        try {
            source.inputStream().buffered().use { input ->
                target.outputStream().buffered().use { output -> input.copyTo(output) }
            }
            return inspectFile(target.absolutePath, displayName)
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    suspend fun inspectFile(path: String, displayName: String = File(path).name): ImportedProcessImage {
        val file = File(path)
        require(file.isFile) { "图片文件不存在" }
        require(file.length() in 1..MAX_INPUT_BYTES) { "图片为空或超过 100 MB" }
        currentCoroutineContext().ensureActive()

        if (file.hasGifSignature()) {
            val header = parseGifHeader(file.readBytes())
            validateImageSize(header.width, header.height, header.numFrames)
            return ImportedProcessImage(
                path = file.absolutePath,
                displayName = displayName,
                mimeType = GIF_MIME_TYPE,
                width = header.width,
                height = header.height,
                frameCount = header.numFrames,
                kind = ProcessImageKind.GIF
            )
        }

        val bounds = decodeBounds(file)
        validateImageSize(bounds.first, bounds.second, 1)
        if (ApngDisguiseCodec.hasPngSignature(file) && ApngDisguiseCodec.containsAnimationControl(file)) {
            val disguise = ApngDisguiseCodec.inspectDisguise(file)
            disguise?.let { validateImageSize(it.width, it.height, it.animationFrameCount) }
            return ImportedProcessImage(
                path = file.absolutePath,
                displayName = displayName,
                mimeType = ApngDisguiseCodec.MIME_TYPE,
                width = disguise?.width ?: bounds.first,
                height = disguise?.height ?: bounds.second,
                frameCount = disguise?.animationFrameCount ?: 1,
                kind = if (disguise != null) {
                    ProcessImageKind.CHATBAR_DISGUISE_APNG
                } else {
                    ProcessImageKind.OTHER_APNG
                }
            )
        }

        return ImportedProcessImage(
            path = file.absolutePath,
            displayName = displayName,
            mimeType = PNG_MIME_TYPE,
            width = bounds.first,
            height = bounds.second,
            frameCount = 1,
            kind = ProcessImageKind.STATIC
        )
    }

    suspend fun createApngDisguise(
        sourcePath: String,
        onProgress: (Float) -> Unit = {}
    ): ProcessedImage {
        cleanupStaleTemporaryFiles()
        val source = File(sourcePath)
        require(source.isFile) { "原图文件不存在" }
        require(source.length() in 1..MAX_INPUT_BYTES) { "图片为空或超过 100 MB" }
        if (source.hasGifSignature()) {
            return createGifDisguise(source, onProgress)
        }
        require(!ApngDisguiseCodec.containsAnimationControl(source)) { "APNG 不能再次伪装" }
        val bitmap = BitmapFactory.decodeFile(source.absolutePath)
            ?: error("无法解码图片；暂不支持此格式")
        return try {
            createStaticDisguise(bitmap, onProgress)
        } finally {
            bitmap.recycle()
        }
    }

    suspend fun createApngDisguise(
        bitmap: Bitmap,
        onProgress: (Float) -> Unit = {}
    ): ProcessedImage {
        cleanupStaleTemporaryFiles()
        return createStaticDisguise(bitmap, onProgress)
    }

    suspend fun restoreApngDisguise(
        sourcePath: String,
        onProgress: (Float) -> Unit = {}
    ): ProcessedImage {
        cleanupStaleTemporaryFiles()
        val source = File(sourcePath)
        require(source.isFile) { "APNG 文件不存在" }
        require(source.length() in 1..MAX_INPUT_BYTES) { "APNG 为空或超过 100 MB" }
        val inspection = ApngDisguiseCodec.inspectDisguise(source)
            ?: error("不是可还原的 ChatBar APNG 伪装图")
        onProgress(0.1f)
        currentCoroutineContext().ensureActive()
        val animated = inspection.metadata.contentKind == ApngDisguiseContentKind.ANIMATED
        val target = outputFile("restored", "png")
        try {
            ApngDisguiseCodec.restoreDisguise(source, target) { codecProgress ->
                onProgress(0.1f + codecProgress * 0.85f)
            }
            currentCoroutineContext().ensureActive()
            onProgress(1f)
            return ProcessedImage(
                path = target.absolutePath,
                mimeType = PNG_MIME_TYPE,
                width = inspection.width,
                height = inspection.height,
                frameCount = inspection.animationFrameCount,
                isAnimated = animated,
                operation = ProcessedImageOperation.APNG_RESTORE
            )
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    private suspend fun createStaticDisguise(
        bitmap: Bitmap,
        onProgress: (Float) -> Unit
    ): ProcessedImage {
        validateImageSize(bitmap.width, bitmap.height, 1)
        val target = outputFile("disguise", "png")
        val temporary = File(target.parentFile, "${target.name}.tmp")
        val cover = createBrandCover(bitmap.width, bitmap.height)
        try {
            ApngDisguiseCodec.limitedFileOutput(temporary).use { output ->
                val writer = ApngDisguiseCodec.Writer(
                    output = output,
                    width = bitmap.width,
                    height = bitmap.height,
                    animationFrameCount = 2,
                    playCount = 0,
                    contentKind = ApngDisguiseContentKind.STATIC,
                    contentFrameCount = 1
                )
                writer.writeDefaultImage(cover)
                onProgress(0.3f)
                writer.writeFrame(bitmap, delayNumerator = 10, delayDenominator = 100)
                onProgress(0.85f)
                writer.writeStaticHeartbeatFrame()
                writer.finish()
            }
            currentCoroutineContext().ensureActive()
            require(ApngDisguiseCodec.inspectDisguise(temporary) != null) { "APNG 伪装结构校验失败" }
            replaceTemporaryFile(temporary, target)
            onProgress(1f)
            return ProcessedImage(
                path = target.absolutePath,
                mimeType = PNG_MIME_TYPE,
                width = bitmap.width,
                height = bitmap.height,
                frameCount = 1,
                isAnimated = false,
                operation = ProcessedImageOperation.APNG_DISGUISE
            )
        } catch (error: Throwable) {
            target.delete()
            throw error
        } finally {
            cover.recycle()
            temporary.delete()
        }
    }

    private suspend fun createGifDisguise(
        source: File,
        onProgress: (Float) -> Unit
    ): ProcessedImage {
        val bytes = source.readBytes()
        val header = parseGifHeader(bytes)
        validateImageSize(header.width, header.height, header.numFrames)
        val decoder = StandardGifDecoder(SimpleBitmapProvider(), header, ByteBuffer.wrap(bytes))
        if (decoder.frameCount <= 1) {
            try {
                decoder.advance()
                val frame = decoder.nextFrame ?: error("GIF 首帧解码失败")
                return try {
                    createStaticDisguise(frame, onProgress)
                } finally {
                    frame.recycle()
                }
            } finally {
                decoder.clear()
            }
        }

        val target = outputFile("disguise", "png")
        val temporary = File(target.parentFile, "${target.name}.tmp")
        val cover = createBrandCover(header.width, header.height)
        try {
            ApngDisguiseCodec.limitedFileOutput(temporary).use { output ->
                val writer = ApngDisguiseCodec.Writer(
                    output = output,
                    width = header.width,
                    height = header.height,
                    animationFrameCount = decoder.frameCount,
                    playCount = ApngDisguiseCodec.gifLoopCountToApngPlayCount(decoder.netscapeLoopCount),
                    contentKind = ApngDisguiseContentKind.ANIMATED,
                    contentFrameCount = decoder.frameCount
                )
                writer.writeDefaultImage(cover)
                onProgress(0.15f)
                repeat(decoder.frameCount) { frameIndex ->
                    currentCoroutineContext().ensureActive()
                    decoder.advance()
                    val delayMillis = decoder.nextDelay.coerceAtLeast(0)
                    val frame = decoder.nextFrame ?: error("GIF 第 ${frameIndex + 1} 帧解码失败")
                    try {
                        writer.writeFrame(
                            frame,
                            delayNumerator = (delayMillis / 10).coerceIn(1, 0xFFFF),
                            delayDenominator = 100
                        )
                    } finally {
                        frame.recycle()
                    }
                    onProgress(0.15f + 0.8f * (frameIndex + 1f) / decoder.frameCount)
                }
                writer.finish()
            }
            currentCoroutineContext().ensureActive()
            require(ApngDisguiseCodec.inspectDisguise(temporary) != null) { "APNG 伪装结构校验失败" }
            replaceTemporaryFile(temporary, target)
            onProgress(1f)
            return ProcessedImage(
                path = target.absolutePath,
                mimeType = PNG_MIME_TYPE,
                width = header.width,
                height = header.height,
                frameCount = header.numFrames,
                isAnimated = true,
                operation = ProcessedImageOperation.APNG_DISGUISE
            )
        } catch (error: Throwable) {
            target.delete()
            throw error
        } finally {
            cover.recycle()
            decoder.clear()
            temporary.delete()
        }
    }

    private fun createBrandCover(width: Int, height: Int): Bitmap {
        val cover = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(cover)
        canvas.drawColor(BRAND_COVER_COLOR)
        val logo = BitmapFactory.decodeResource(context.resources, R.mipmap.ic_launcher)
            ?: error("无法读取 ChatBar Logo")
        try {
            val size = (min(width, height) * LOGO_SHORT_EDGE_FRACTION).roundToInt().coerceAtLeast(1)
            val left = (width - size) / 2
            val top = (height - size) / 2
            canvas.drawBitmap(
                logo,
                Rect(0, 0, logo.width, logo.height),
                Rect(left, top, left + size, top + size),
                Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
            )
        } finally {
            logo.recycle()
        }
        return cover
    }

    private fun decodeBounds(file: File): Pair<Int, Int> {
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        require(options.outWidth > 0 && options.outHeight > 0) { "无法识别图片格式" }
        return options.outWidth to options.outHeight
    }

    private fun parseGifHeader(bytes: ByteArray): GifHeader {
        val header = GifHeaderParser().setData(bytes).parseHeader()
        require(header.status == GifDecoder.STATUS_OK && header.numFrames > 0) { "GIF 文件损坏或无法解析" }
        return header
    }

    private fun validateImageSize(width: Int, height: Int, frameCount: Int) {
        require(width > 0 && height > 0 && frameCount > 0) { "图片尺寸或帧数无效" }
        val framePixels = width.toLong() * height
        require(framePixels <= MAX_FRAME_PIXELS) { "图片尺寸过大；单帧最多约 800 万像素" }
        require(framePixels * frameCount <= MAX_TOTAL_FRAME_PIXELS) { "图片总像素量过大；请减少尺寸或帧数" }
    }

    private fun outputFile(prefix: String, extension: String): File {
        workDirectory.mkdirs()
        return File(workDirectory, "${prefix}_${UUID.randomUUID()}.$extension")
    }

    private fun replaceTemporaryFile(temporary: File, target: File) {
        check(temporary.isFile && temporary.length() in 1..ApngDisguiseCodec.MAX_OUTPUT_BYTES) {
            "处理结果为空或超过 100 MB"
        }
        check(temporary.renameTo(target)) { "无法保存处理结果" }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun cleanupStaleTemporaryFiles() {
        val cutoff = System.currentTimeMillis() - WORK_FILE_RETENTION_MS
        workDirectory.listFiles()?.forEach { file ->
            if (file.isFile && file.name.endsWith(".tmp") && file.lastModified() < cutoff) file.delete()
        }
    }

    private class SimpleBitmapProvider : GifDecoder.BitmapProvider {
        override fun obtain(width: Int, height: Int, config: Bitmap.Config): Bitmap =
            Bitmap.createBitmap(width, height, config)

        override fun release(bitmap: Bitmap) {
            if (!bitmap.isRecycled) bitmap.recycle()
        }

        override fun obtainByteArray(size: Int): ByteArray = ByteArray(size)

        override fun release(bytes: ByteArray) = Unit

        override fun obtainIntArray(size: Int): IntArray = IntArray(size)

        override fun release(array: IntArray) = Unit
    }

    private companion object {
        const val GIF_MIME_TYPE = "image/gif"
        const val PNG_MIME_TYPE = "image/png"
        const val MAX_INPUT_BYTES = 100L * 1024 * 1024
        const val MAX_FRAME_PIXELS = 8_000_000L
        const val MAX_TOTAL_FRAME_PIXELS = 300_000_000L
        const val WORK_FILE_RETENTION_MS = 7L * 24 * 60 * 60 * 1000
        const val LOGO_SHORT_EDGE_FRACTION = 0.4f
        val BRAND_COVER_COLOR: Int = Color.rgb(0x2F, 0x8E, 0x7B)
    }
}

private fun File.hasGifSignature(): Boolean = inputStream().buffered().use { input ->
    val signature = ByteArray(6)
    input.read(signature) == signature.size &&
        (signature.contentEquals("GIF87a".toByteArray()) || signature.contentEquals("GIF89a".toByteArray()))
}

private fun extensionFor(displayName: String, mimeType: String?): String = when {
    displayName.substringAfterLast('.', "").equals("apng", ignoreCase = true) -> "apng"
    mimeType == "image/gif" -> "gif"
    mimeType == "image/png" -> "png"
    mimeType == "image/webp" -> "webp"
    mimeType == "image/jpeg" -> "jpg"
    displayName.substringAfterLast('.', "").lowercase() in setOf("gif", "png", "webp", "jpg", "jpeg") ->
        displayName.substringAfterLast('.').lowercase()
    else -> "img"
}
