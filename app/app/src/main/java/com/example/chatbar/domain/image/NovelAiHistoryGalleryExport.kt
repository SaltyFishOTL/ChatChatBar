package com.example.chatbar.domain.image

import android.app.RecoverableSecurityException
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.content.IntentSender
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class NovelAiGalleryExportSource(
    val key: String,
    val path: String,
    val createdAt: Long,
    val selectionIndex: Int
)

data class NovelAiGalleryExportConflict(
    val key: String,
    val displayName: String,
    val duplicateCount: Int
)

data class NovelAiGalleryExportItem(
    val source: NovelAiGalleryExportSource,
    val displayName: String,
    internal val existing: List<NovelAiGalleryExistingTarget>
)

data class NovelAiGalleryExportPlan(
    val items: List<NovelAiGalleryExportItem>
) {
    val conflicts: List<NovelAiGalleryExportConflict>
        get() = items.mapNotNull { item ->
            item.existing.takeIf(List<*>::isNotEmpty)?.let {
                NovelAiGalleryExportConflict(
                    key = item.source.key,
                    displayName = item.displayName,
                    duplicateCount = item.existing.size
                )
            }
        }
}

enum class NovelAiGalleryConflictDecision { OVERWRITE, SKIP }

data class NovelAiGalleryExportResult(
    val savedCount: Int,
    val skippedCount: Int,
    val remainingDuplicateCount: Int
)

sealed interface NovelAiGalleryExportExecution {
    data class Completed(val result: NovelAiGalleryExportResult) : NovelAiGalleryExportExecution
    data class AuthorizationRequired(val intentSender: IntentSender) : NovelAiGalleryExportExecution
}

object NovelAiHistoryExportNaming {
    private val invalidCharacters = Regex("[\\\\/:*?\"<>|\\p{Cc}]")
    private val imageExtension = Regex("(?i)\\.(png|jpe?g|webp|gif)$")

    fun displayName(
        sourcePath: String,
        createdAt: Long,
        selectionIndex: Int,
        customTitle: String,
        locale: Locale = Locale.US,
        timeZone: TimeZone = TimeZone.getDefault()
    ): String {
        val extension = File(sourcePath).extension
            .lowercase(Locale.ROOT)
            .takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
            ?: "png"
        val cleanedTitle = customTitle
            .trim()
            .replace(imageExtension, "")
            .replace(invalidCharacters, "_")
            .trim(' ', '.')
            .take(120)
        val base = cleanedTitle.ifBlank {
            val timestamp = SimpleDateFormat("yyyyMMdd_HHmmss_SSS", locale).apply {
                this.timeZone = timeZone
            }.format(Date(createdAt))
            "CCBImage_$timestamp"
        }
        return "${base}_${selectionIndex}.$extension"
    }

    fun sameName(first: String, second: String): Boolean =
        first.trim().equals(second.trim(), ignoreCase = true)
}

class NovelAiHistoryGalleryExporter(private val context: Context) {
    suspend fun prepare(
        sources: List<NovelAiGalleryExportSource>,
        customTitle: String
    ): NovelAiGalleryExportPlan = withContext(Dispatchers.IO) {
        require(sources.isNotEmpty()) { "未选择图片" }
        NovelAiGalleryExportPlan(
            items = sources.map { source ->
                val file = File(source.path)
                require(file.isFile) { "图片文件不存在：${file.name}" }
                val displayName = NovelAiHistoryExportNaming.displayName(
                    sourcePath = source.path,
                    createdAt = source.createdAt,
                    selectionIndex = source.selectionIndex,
                    customTitle = customTitle
                )
                NovelAiGalleryExportItem(
                    source = source,
                    displayName = displayName,
                    existing = findExisting(displayName)
                )
            }
        )
    }

    suspend fun execute(
        plan: NovelAiGalleryExportPlan,
        decisions: Map<String, NovelAiGalleryConflictDecision>,
        authorizationGranted: Boolean = false
    ): NovelAiGalleryExportExecution = withContext(Dispatchers.IO) {
        val unresolved = plan.conflicts.firstOrNull { it.key !in decisions }
        require(unresolved == null) { "仍有重名图片未处理：${unresolved?.displayName}" }
        val overwriteItems = plan.items.filter {
            decisions[it.source.key] == NovelAiGalleryConflictDecision.OVERWRITE
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && !authorizationGranted) {
            val foreignUris = overwriteItems
                .mapNotNull { it.existing.maxByOrNull(NovelAiGalleryExistingTarget::modifiedAt) }
                .filter { it.uri != null && it.ownerPackageName != null && it.ownerPackageName != context.packageName }
                .mapNotNull(NovelAiGalleryExistingTarget::uri)
            if (foreignUris.isNotEmpty()) {
                return@withContext NovelAiGalleryExportExecution.AuthorizationRequired(
                    MediaStore.createWriteRequest(context.contentResolver, foreignUris).intentSender
                )
            }
        }

        val created = mutableListOf<NovelAiGalleryCreatedTarget>()
        val backups = mutableListOf<NovelAiGalleryBackup>()
        var saved = 0
        var skipped = 0
        var remainingDuplicates = 0
        var deleteBackups = true
        try {
            plan.items.forEach { item ->
                val decision = decisions[item.source.key]
                if (decision == NovelAiGalleryConflictDecision.SKIP) {
                    skipped++
                    return@forEach
                }
                val source = File(item.source.path)
                val target = item.existing.maxByOrNull(NovelAiGalleryExistingTarget::modifiedAt)
                if (target == null) {
                    created += createNew(source, item.displayName)
                } else {
                    val backup = backup(target)
                    backups += backup
                    overwrite(target, source)
                    remainingDuplicates += (item.existing.size - 1).coerceAtLeast(0)
                }
                saved++
            }
        } catch (error: RecoverableSecurityException) {
            val rollbackFailures = rollback(created, backups)
            if (rollbackFailures > 0) {
                deleteBackups = false
                throw IllegalStateException(
                    "图库授权前回滚失败 $rollbackFailures 项；备份已保留",
                    error
                )
            }
            return@withContext NovelAiGalleryExportExecution.AuthorizationRequired(
                error.userAction.actionIntent.intentSender
            )
        } catch (error: Throwable) {
            val rollbackFailures = rollback(created, backups)
            if (rollbackFailures > 0) {
                deleteBackups = false
                error.addSuppressed(IllegalStateException("图库回滚失败 $rollbackFailures 项"))
            }
            throw error
        } finally {
            if (deleteBackups) backups.forEach { it.backup.delete() }
        }
        NovelAiGalleryExportExecution.Completed(
            NovelAiGalleryExportResult(saved, skipped, remainingDuplicates)
        )
    }

    private fun findExisting(displayName: String): List<NovelAiGalleryExistingTarget> {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val file = File(legacyDirectory(), displayName)
            return if (file.isFile) {
                listOf(NovelAiGalleryExistingTarget(file = file, modifiedAt = file.lastModified()))
            } else {
                emptyList()
            }
        }
        val resolver = context.contentResolver
        val path = galleryRelativePath()
        val projection = buildList {
            add(MediaStore.Images.Media._ID)
            add(MediaStore.Images.Media.DISPLAY_NAME)
            add(MediaStore.Images.Media.DATE_MODIFIED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                add(MediaStore.Images.Media.OWNER_PACKAGE_NAME)
            }
        }.toTypedArray()
        val results = mutableListOf<NovelAiGalleryExistingTarget>()
        resolver.query(
            MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
            projection,
            "${MediaStore.Images.Media.RELATIVE_PATH}=? OR ${MediaStore.Images.Media.RELATIVE_PATH}=?",
            arrayOf(path, "$path/"),
            null
        )?.use { cursor ->
            val idColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media._ID)
            val nameColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DISPLAY_NAME)
            val modifiedColumn = cursor.getColumnIndexOrThrow(MediaStore.Images.Media.DATE_MODIFIED)
            val ownerColumn = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                cursor.getColumnIndex(MediaStore.Images.Media.OWNER_PACKAGE_NAME)
            } else -1
            while (cursor.moveToNext()) {
                val candidate = cursor.getString(nameColumn).orEmpty()
                if (!NovelAiHistoryExportNaming.sameName(candidate, displayName)) continue
                results += NovelAiGalleryExistingTarget(
                    uri = ContentUris.withAppendedId(
                        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                        cursor.getLong(idColumn)
                    ),
                    modifiedAt = cursor.getLong(modifiedColumn) * 1000L,
                    ownerPackageName = ownerColumn.takeIf { it >= 0 }?.let(cursor::getString)
                )
            }
        }
        return results
    }

    private fun createNew(source: File, displayName: String): NovelAiGalleryCreatedTarget {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            val target = File(legacyDirectory().also(File::mkdirs), displayName)
            source.copyTo(target, overwrite = false)
            android.media.MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), null, null)
            return NovelAiGalleryCreatedTarget(file = target)
        }
        val values = ContentValues().apply {
            put(MediaStore.Images.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Images.Media.MIME_TYPE, imageMimeType(source))
            put(MediaStore.Images.Media.RELATIVE_PATH, galleryRelativePath())
            put(MediaStore.Images.Media.IS_PENDING, 1)
        }
        val uri = checkNotNull(
            context.contentResolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values)
        ) { "无法创建图库图片 $displayName" }
        try {
            context.contentResolver.openOutputStream(uri, "w")?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: error("无法写入图库图片 $displayName")
            values.clear()
            values.put(MediaStore.Images.Media.IS_PENDING, 0)
            context.contentResolver.update(uri, values, null, null)
            return NovelAiGalleryCreatedTarget(uri = uri)
        } catch (error: Throwable) {
            context.contentResolver.delete(uri, null, null)
            throw error
        }
    }

    private fun backup(target: NovelAiGalleryExistingTarget): NovelAiGalleryBackup {
        val directory = File(context.cacheDir, "gallery-export-backups").also(File::mkdirs)
        val backup = File(directory, UUID.randomUUID().toString())
        when {
            target.uri != null -> context.contentResolver.openInputStream(target.uri)?.use { input ->
                backup.outputStream().use { output -> input.copyTo(output) }
            } ?: error("无法读取待覆盖图库图片")
            target.file != null -> target.file.copyTo(backup, overwrite = false)
            else -> error("无效图库目标")
        }
        return NovelAiGalleryBackup(target, backup)
    }

    private fun overwrite(target: NovelAiGalleryExistingTarget, source: File) {
        when {
            target.uri != null -> context.contentResolver.openOutputStream(target.uri, "rwt")?.use { output ->
                source.inputStream().use { input -> input.copyTo(output) }
            } ?: error("无法覆盖图库图片")
            target.file != null -> source.copyTo(target.file, overwrite = true)
            else -> error("无效图库目标")
        }
    }

    private fun rollback(
        created: List<NovelAiGalleryCreatedTarget>,
        backups: List<NovelAiGalleryBackup>
    ): Int {
        var failures = 0
        created.asReversed().forEach { target ->
            val success = runCatching {
                when {
                    target.uri != null -> context.contentResolver.delete(target.uri, null, null) > 0
                    target.file != null -> !target.file.exists() || target.file.delete()
                    else -> false
                }
            }.getOrDefault(false)
            if (!success) failures++
        }
        backups.asReversed().forEach { item ->
            val success = runCatching {
                when {
                    item.target.uri != null -> context.contentResolver.openOutputStream(item.target.uri, "rwt")
                        ?.use { output -> item.backup.inputStream().use { input -> input.copyTo(output) } } != null
                    item.target.file != null -> {
                        item.backup.copyTo(item.target.file, overwrite = true)
                        true
                    }
                    else -> false
                }
            }.getOrDefault(false)
            if (!success) failures++
        }
        return failures
    }

    private fun galleryRelativePath(): String = Environment.DIRECTORY_PICTURES + "/ChatBar"

    private fun legacyDirectory(): File =
        File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "ChatBar")

    private fun imageMimeType(file: File): String = when (file.extension.lowercase(Locale.ROOT)) {
        "jpg", "jpeg" -> "image/jpeg"
        "webp" -> "image/webp"
        "gif" -> "image/gif"
        else -> "image/png"
    }
}

data class NovelAiGalleryExistingTarget(
    val uri: Uri? = null,
    val file: File? = null,
    val modifiedAt: Long,
    val ownerPackageName: String? = null
)

private data class NovelAiGalleryCreatedTarget(val uri: Uri? = null, val file: File? = null)

private data class NovelAiGalleryBackup(
    val target: NovelAiGalleryExistingTarget,
    val backup: File
)
