package com.example.chatbar.domain.image

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.media.ExifInterface
import android.net.Uri
import android.util.Base64
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import kotlin.math.abs

class NovelAiStudioAssetStorage(private val context: Context) {
    private val root: File get() = File(context.filesDir, "images/studio-guidance")

    fun importUri(uri: Uri, tier: NovelAiSizeTier, fitToGeneration: Boolean): NovelAiStudioAssetRef {
        val temporary = File(root.also(File::mkdirs), ".import-${UUID.randomUUID()}")
        try {
            context.contentResolver.openInputStream(uri)?.use { input ->
                temporary.outputStream().buffered().use(input::copyTo)
            } ?: error("无法打开所选图片")
            require(temporary.length() in 1..MAX_INPUT_BYTES) { "图片为空或超过 100 MB" }
            return materializeFile(temporary, tier, fitToGeneration)
        } finally {
            temporary.delete()
        }
    }

    fun copyExisting(path: String, tier: NovelAiSizeTier, fitToGeneration: Boolean): NovelAiStudioAssetRef {
        val source = File(path)
        require(source.isFile) { "图片文件不存在" }
        require(source.length() in 1..MAX_INPUT_BYTES) { "图片为空或超过 100 MB" }
        return materializeFile(source, tier, fitToGeneration)
    }

    fun importBase64(encoded: String, tier: NovelAiSizeTier, fitToGeneration: Boolean): NovelAiStudioAssetRef {
        val bytes = runCatching { Base64.decode(encoded, Base64.DEFAULT) }
            .getOrElse { throw IllegalArgumentException("元数据图片编码无效", it) }
        require(bytes.size.toLong() in 1..MAX_INPUT_BYTES) { "元数据图片为空或超过 100 MB" }
        val temporary = File(root.also(File::mkdirs), ".metadata-${UUID.randomUUID()}")
        return try {
            temporary.writeBytes(bytes)
            materializeFile(temporary, tier, fitToGeneration)
        } finally {
            temporary.delete()
        }
    }

    fun createEmptyMask(width: Int, height: Int): NovelAiStudioAssetRef {
        require(width > 0 && height > 0) { "蒙版尺寸无效" }
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bitmap.eraseColor(Color.BLACK)
        return try {
            saveBitmap(bitmap, "mask").copy(containsPaint = false)
        } finally {
            bitmap.recycle()
        }
    }

    fun saveBitmap(bitmap: Bitmap, prefix: String = "edited"): NovelAiStudioAssetRef {
        root.mkdirs()
        val target = File(root, "$prefix-${UUID.randomUUID()}.png")
        val temporary = File(root, ".${target.name}.tmp")
        try {
            temporary.outputStream().buffered().use { output ->
                check(bitmap.compress(Bitmap.CompressFormat.PNG, 100, output)) { "PNG 编码失败" }
            }
            check(temporary.length() > 0L && temporary.renameTo(target)) { "无法保存图片" }
            return NovelAiStudioAssetRef(
                path = target.absolutePath,
                sha256 = sha256(target),
                width = bitmap.width,
                height = bitmap.height
            )
        } finally {
            temporary.delete()
        }
    }

    fun deleteIfOwned(asset: NovelAiStudioAssetRef?): Boolean {
        val file = asset?.path?.takeIf(String::isNotBlank)?.let(::File) ?: return true
        val owned = file.canonicalPath.startsWith(root.canonicalPath + File.separator)
        return owned && (!file.exists() || file.delete())
    }

    fun isOwned(asset: NovelAiStudioAssetRef?): Boolean = runCatching {
        val file = File(asset?.path.orEmpty())
        file.canonicalPath.startsWith(root.canonicalPath + File.separator)
    }.getOrDefault(false)

    fun cleanupOrphans(referencedPaths: Set<String>) {
        val referenced = referencedPaths.mapNotNullTo(mutableSetOf()) { path ->
            runCatching { File(path).canonicalPath }.getOrNull()
        }
        root.listFiles().orEmpty().forEach { file ->
            if (file.isFile && !file.name.startsWith(".") && file.extension.equals("png", ignoreCase = true)) {
                val canonical = runCatching { file.canonicalPath }.getOrNull()
                if (canonical != null && canonical !in referenced) file.delete()
            }
        }
    }

    private fun materializeFile(
        source: File,
        tier: NovelAiSizeTier,
        fitToGeneration: Boolean
    ): NovelAiStudioAssetRef {
        val decoded = decodeOriented(source)
        require(decoded.width.toLong() * decoded.height <= MAX_PIXELS) { "图片尺寸过大；最多约 1600 万像素" }
        val output = if (fitToGeneration) {
            val size = closestSize(decoded.width, decoded.height, tier)
            centerCrop(decoded, size.width, size.height)
        } else {
            decoded.copy(Bitmap.Config.ARGB_8888, false)
        }
        return try {
            saveBitmap(output, "source")
        } finally {
            output.recycle()
            decoded.recycle()
        }
    }

    private fun decodeOriented(file: File): Bitmap {
        val signature = file.inputStream().buffered().use { input -> ByteArray(6).also { input.read(it) } }
        require(!signature.toString(Charsets.US_ASCII).startsWith("GIF")) {
            "动画 GIF 不能用于图像引导"
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath)
            ?: error("无法识别图片；动画图片不能用于图像引导")
        val orientation = runCatching {
            file.inputStream().buffered().use { ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL) }
        }.getOrDefault(ExifInterface.ORIENTATION_NORMAL)
        val matrix = Matrix()
        when (orientation) {
            ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> matrix.setScale(-1f, 1f)
            ExifInterface.ORIENTATION_ROTATE_180 -> matrix.setRotate(180f)
            ExifInterface.ORIENTATION_FLIP_VERTICAL -> matrix.setScale(1f, -1f)
            ExifInterface.ORIENTATION_TRANSPOSE -> {
                matrix.setRotate(90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_90 -> matrix.setRotate(90f)
            ExifInterface.ORIENTATION_TRANSVERSE -> {
                matrix.setRotate(-90f)
                matrix.postScale(-1f, 1f)
            }
            ExifInterface.ORIENTATION_ROTATE_270 -> matrix.setRotate(-90f)
            else -> return bitmap
        }
        val transformed = Bitmap.createBitmap(
            bitmap,
            0,
            0,
            bitmap.width,
            bitmap.height,
            matrix,
            true
        )
        if (transformed !== bitmap) bitmap.recycle()
        return transformed
    }

    private fun centerCrop(source: Bitmap, targetWidth: Int, targetHeight: Int): Bitmap {
        val scale = maxOf(targetWidth.toFloat() / source.width, targetHeight.toFloat() / source.height)
        val left = (targetWidth - source.width * scale) / 2f
        val top = (targetHeight - source.height * scale) / 2f
        return Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888).also { target ->
            Canvas(target).apply {
                drawColor(Color.BLACK)
                drawBitmap(source, Matrix().apply { setScale(scale, scale); postTranslate(left, top) }, null)
            }
        }
    }

    private fun closestSize(width: Int, height: Int, tier: NovelAiSizeTier): NovelAiImageSize {
        val sourceRatio = width.toDouble() / height
        return NovelAiAspectRatio.entries
            .filterNot { tier == NovelAiSizeTier.WALLPAPER && it == NovelAiAspectRatio.SQUARE }
            .map { aspect -> NovelAiGenerationSettings(sizeTier = tier, aspectRatio = aspect).imageSize() }
            .minBy { size -> abs(sourceRatio - size.width.toDouble() / size.height) }
    }

    private fun sha256(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().buffered().use { input ->
            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
            while (true) {
                val count = input.read(buffer)
                if (count < 0) break
                digest.update(buffer, 0, count)
            }
        }
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private companion object {
        const val MAX_INPUT_BYTES = 100L * 1024 * 1024
        const val MAX_PIXELS = 16_000_000L
    }
}
