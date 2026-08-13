package com.example.chatbar.domain.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import com.bumptech.glide.gifdecoder.GifDecoder
import com.bumptech.glide.gifdecoder.GifHeader
import com.bumptech.glide.gifdecoder.GifHeaderParser
import com.bumptech.glide.gifdecoder.StandardGifDecoder
import com.bumptech.glide.gifencoder.AnimatedGifEncoder
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.currentCoroutineContext
import java.io.File
import java.nio.ByteBuffer
import java.util.UUID

data class ImportedProcessImage(
    val path: String,
    val displayName: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val frameCount: Int
) {
    val isAnimatedGif: Boolean get() = mimeType == "image/gif" && frameCount > 1
}

data class ProcessedImage(
    val path: String,
    val mimeType: String,
    val width: Int,
    val height: Int,
    val frameCount: Int
)

class ImageProcessingService(private val context: Context) {
    private val workDirectory = File(context.filesDir, "images/image-processing")

    suspend fun importImage(uri: Uri): ImportedProcessImage {
        cleanupStaleWorkFiles()
        workDirectory.mkdirs()
        val resolver = context.contentResolver
        val displayName = queryDisplayName(uri) ?: "image"
        val declaredMimeType = resolver.getType(uri)
        val extension = extensionFor(displayName, declaredMimeType)
        val target = File(workDirectory, "source_${UUID.randomUUID()}.$extension")
        try {
            resolver.openInputStream(uri)?.use { input ->
                target.outputStream().use { output -> input.copyTo(output) }
            } ?: error("无法打开所选图片")
            require(target.length() in 1..MAX_INPUT_BYTES) { "图片为空或超过 100 MB" }
            return inspectImportedImage(target, displayName)
        } catch (error: Throwable) {
            target.delete()
            throw error
        }
    }

    suspend fun process(
        sourcePath: String,
        operation: FullImagePatchOperation,
        onProgress: (Float) -> Unit = {}
    ): ProcessedImage {
        val source = File(sourcePath)
        require(source.isFile) { "原图文件不存在" }
        return if (source.hasGifSignature()) {
            processGif(source, operation, onProgress)
        } else {
            processStaticImage(source, operation, onProgress)
        }
    }

    private suspend fun processStaticImage(
        source: File,
        operation: FullImagePatchOperation,
        onProgress: (Float) -> Unit
    ): ProcessedImage {
        val options = BitmapFactory.Options().apply {
            inPreferredConfig = Bitmap.Config.ARGB_8888
            inMutable = true
        }
        val bitmap = BitmapFactory.decodeFile(source.absolutePath, options)
            ?: error("无法解码图片；暂不支持此格式")
        val target = outputFile(operation, "png")
        val temporary = File(target.parentFile, "${target.name}.tmp")
        try {
            currentCoroutineContext().ensureActive()
            transformFullImageAdversarialPatch(bitmap, operation)
            onProgress(0.8f)
            temporary.outputStream().buffered().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "PNG 编码失败" }
            }
            currentCoroutineContext().ensureActive()
            replaceTemporaryFile(temporary, target)
            onProgress(1f)
            return ProcessedImage(
                path = target.absolutePath,
                mimeType = PNG_MIME_TYPE,
                width = bitmap.width,
                height = bitmap.height,
                frameCount = 1
            )
        } finally {
            bitmap.recycle()
            temporary.delete()
        }
    }

    private suspend fun processGif(
        source: File,
        operation: FullImagePatchOperation,
        onProgress: (Float) -> Unit
    ): ProcessedImage {
        val bytes = source.readBytes()
        val header = parseGifHeader(bytes)
        validateImageSize(header.width, header.height, header.numFrames)
        val decoder = StandardGifDecoder(SimpleBitmapProvider(), header, ByteBuffer.wrap(bytes))
        val target = outputFile(operation, "gif")
        val temporary = File(target.parentFile, "${target.name}.tmp")
        try {
            val encoder = AnimatedGifEncoder().apply {
                setSize(header.width, header.height)
                setQuality(GIF_ENCODER_QUALITY)
                decoder.netscapeLoopCount.takeIf { it >= 0 }?.let(::setRepeat)
            }
            temporary.outputStream().buffered().use { output ->
                check(encoder.start(output)) { "GIF 编码器启动失败" }
                repeat(decoder.frameCount) { frameIndex ->
                    currentCoroutineContext().ensureActive()
                    decoder.advance()
                    val delayMillis = decoder.nextDelay.coerceAtLeast(MIN_GIF_FRAME_DELAY_MS)
                    val frame = decoder.nextFrame ?: error("GIF 第 ${frameIndex + 1} 帧解码失败")
                    try {
                        transformFullImageAdversarialPatch(frame, operation, frameIndex)
                        encoder.setDelay(delayMillis)
                        check(encoder.addFrame(frame)) { "GIF 第 ${frameIndex + 1} 帧编码失败" }
                    } finally {
                        frame.recycle()
                    }
                    onProgress((frameIndex + 1f) / decoder.frameCount)
                }
                check(encoder.finish()) { "GIF 编码收尾失败" }
            }
            currentCoroutineContext().ensureActive()
            replaceTemporaryFile(temporary, target)
            return ProcessedImage(
                path = target.absolutePath,
                mimeType = GIF_MIME_TYPE,
                width = header.width,
                height = header.height,
                frameCount = header.numFrames
            )
        } finally {
            decoder.clear()
            temporary.delete()
        }
    }

    private fun inspectImportedImage(file: File, displayName: String): ImportedProcessImage {
        if (file.hasGifSignature()) {
            val header = parseGifHeader(file.readBytes())
            validateImageSize(header.width, header.height, header.numFrames)
            return ImportedProcessImage(
                path = file.absolutePath,
                displayName = displayName,
                mimeType = GIF_MIME_TYPE,
                width = header.width,
                height = header.height,
                frameCount = header.numFrames
            )
        }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        require(options.outWidth > 0 && options.outHeight > 0) { "无法识别图片格式" }
        validateImageSize(options.outWidth, options.outHeight, 1)
        return ImportedProcessImage(
            path = file.absolutePath,
            displayName = displayName,
            mimeType = PNG_MIME_TYPE,
            width = options.outWidth,
            height = options.outHeight,
            frameCount = 1
        )
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
        require(framePixels * frameCount <= MAX_TOTAL_FRAME_PIXELS) { "GIF 总像素量过大；请减少尺寸或帧数" }
    }

    private fun outputFile(operation: FullImagePatchOperation, extension: String): File {
        workDirectory.mkdirs()
        val prefix = if (operation == FullImagePatchOperation.Apply) "patched" else "restored"
        return File(workDirectory, "${prefix}_${UUID.randomUUID()}.$extension")
    }

    private fun replaceTemporaryFile(temporary: File, target: File) {
        check(temporary.isFile && temporary.length() > 0) { "处理结果为空" }
        check(temporary.renameTo(target)) { "无法保存处理结果" }
    }

    private fun queryDisplayName(uri: Uri): String? = runCatching {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) cursor.getString(0) else null
        }
    }.getOrNull()

    private fun cleanupStaleWorkFiles() {
        val cutoff = System.currentTimeMillis() - WORK_FILE_RETENTION_MS
        workDirectory.listFiles()?.forEach { file ->
            if (file.isFile && file.lastModified() < cutoff) file.delete()
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
        const val MIN_GIF_FRAME_DELAY_MS = 10
        // 越低调色板采样越密、颜色越准（Glide AnimatedGifEncoder 直接以该值作为 NeuQuant 采样间隔），
        // 量化噪声随之降低，贴片信号（单通道均幅约 ±30）相对噪声的裕度更大
        const val GIF_ENCODER_QUALITY = 1
        const val WORK_FILE_RETENTION_MS = 7L * 24 * 60 * 60 * 1000
    }
}

private fun File.hasGifSignature(): Boolean = inputStream().buffered().use { input ->
    val signature = ByteArray(6)
    input.read(signature) == signature.size &&
        (signature.contentEquals("GIF87a".toByteArray()) || signature.contentEquals("GIF89a".toByteArray()))
}

private fun extensionFor(displayName: String, mimeType: String?): String = when {
    mimeType == "image/gif" -> "gif"
    mimeType == "image/png" -> "png"
    mimeType == "image/webp" -> "webp"
    mimeType == "image/jpeg" -> "jpg"
    displayName.substringAfterLast('.', "").lowercase() in setOf("gif", "png", "webp", "jpg", "jpeg") ->
        displayName.substringAfterLast('.').lowercase()
    else -> "img"
}
