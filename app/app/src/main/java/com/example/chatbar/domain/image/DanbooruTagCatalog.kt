package com.example.chatbar.domain.image

import android.app.Application
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.LinkedHashMap
import java.util.Locale
import java.util.zip.GZIPInputStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

@Serializable
data class DanbooruCatalogMetadata(
    val sourceSha: String,
    val sourceCommitTime: String,
    val sourceSizeBytes: Long,
    val rowCount: Long = 0L,
    val tableName: String = "tags"
)

data class DanbooruCatalogValidation(
    val tableName: String,
    val rowCount: Long,
    val sourceSizeBytes: Long,
    val sourceSha: String
)

interface NovelAiTagLookup : NovelAiTagSearchClient {
    suspend fun exactChineseTranslations(names: Collection<String>): Map<String, String>
    suspend fun catalogMetadata(): DanbooruCatalogMetadata
}

class DanbooruTagCatalog(
    private val app: Application,
    private val json: Json = Json { ignoreUnknownKeys = true; encodeDefaults = true },
    private val minimumExpectedRowCount: Long = MIN_EXPECTED_ROW_COUNT
) : NovelAiTagLookup {
    private data class CacheEntry(
        val version: String,
        val candidates: List<NovelAiTagCandidate>
    )

    private data class OpenCatalog(
        val database: SQLiteDatabase,
        val metadata: DanbooruCatalogMetadata
    )

    private val mutex = Mutex()
    private val cache = LinkedHashMap<String, CacheEntry>(16, 0.75f, true)
    private var openCatalog: OpenCatalog? = null

    override suspend fun search(query: String): NovelAiTagSearchOutcome = withContext(Dispatchers.IO) {
        val normalized = query.normalizeDanbooruTagQuery()
        require(normalized.length in MIN_DANBOORU_QUERY_LENGTH..MAX_DANBOORU_QUERY_LENGTH) {
            "Danbooru 词条查询长度必须在 $MIN_DANBOORU_QUERY_LENGTH..$MAX_DANBOORU_QUERY_LENGTH 之间"
        }
        mutex.withLock {
            val catalog = ensureReadyLocked()
            val cacheKey = normalized.lowercase(Locale.ROOT)
            cached(cacheKey, catalog.metadata.sourceSha)?.let { candidates ->
                return@withLock NovelAiTagSearchOutcome(normalized, candidates, fromCache = true)
            }
            val candidates = queryCandidates(
                database = catalog.database,
                tableName = catalog.metadata.tableName,
                query = normalized,
                limit = MAX_TAG_CANDIDATES_PER_QUERY
            )
            cache(cacheKey, catalog.metadata.sourceSha, candidates)
            NovelAiTagSearchOutcome(normalized, candidates)
        }
    }

    override suspend fun exactChineseTranslations(
        names: Collection<String>
    ): Map<String, String> = withContext(Dispatchers.IO) {
        val normalizedNames = names.asSequence()
            .map(String::normalizedTagQuery)
            .map { it.lowercase(Locale.ROOT) }
            .filter(String::isNotBlank)
            .distinct()
            .toList()
        if (normalizedNames.isEmpty()) return@withContext emptyMap()
        mutex.withLock {
            val catalog = ensureReadyLocked()
            buildMap {
                normalizedNames.chunked(SQLITE_BIND_LIMIT).forEach { chunk ->
                    val placeholders = List(chunk.size) { "?" }.joinToString(",")
                    val sql = "SELECT name, cn_name FROM ${quotedIdentifier(catalog.metadata.tableName)} " +
                        "WHERE lower(name) IN ($placeholders)"
                    catalog.database.rawQuery(sql, chunk.toTypedArray()).use { cursor ->
                        while (cursor.moveToNext()) {
                            val name = cursor.getString(0).orEmpty().lowercase(Locale.ROOT)
                            val translated = cursor.getString(1).orEmpty().normalizeChineseName()
                            if (translated.isNotBlank()) put(name, translated)
                        }
                    }
                }
            }
        }
    }

    override suspend fun catalogMetadata(): DanbooruCatalogMetadata = withContext(Dispatchers.IO) {
        mutex.withLock { ensureReadyLocked().metadata }
    }

    suspend fun validateDownloadedDatabase(
        file: File,
        expectedSizeBytes: Long,
        expectedSourceSha: String
    ): DanbooruCatalogValidation = withContext(Dispatchers.IO) {
        require(file.isFile && file.length() > 0L) { "词库下载文件无效" }
        if (expectedSizeBytes > 0L && file.length() != expectedSizeBytes) {
            throw IOException("词库大小不完整：已下载 ${file.length()} 字节，应为 $expectedSizeBytes 字节")
        }
        val actualSha = gitBlobSha(file)
        if (!actualSha.equals(expectedSourceSha, ignoreCase = true)) {
            throw IOException("词库完整性校验失败")
        }
        val structure = validateStructure(file)
        DanbooruCatalogValidation(
            tableName = structure.tableName,
            rowCount = structure.rowCount,
            sourceSizeBytes = file.length(),
            sourceSha = actualSha
        )
    }

    suspend fun installDownloadedDatabase(
        stagedFile: File,
        sourceCommitTime: String,
        validation: DanbooruCatalogValidation
    ): DanbooruCatalogMetadata = withContext(Dispatchers.IO) {
        mutex.withLock {
            val directory = catalogDirectory()
            directory.mkdirsOrThrow()
            val activeFile = activeDatabaseFile()
            val manifestFile = installedManifestFile()
            val backupFile = File(directory, "$ACTIVE_DATABASE_NAME.backup")
            val backupManifest = File(directory, "$INSTALLED_MANIFEST_NAME.backup")
            val metadata = DanbooruCatalogMetadata(
                sourceSha = validation.sourceSha,
                sourceCommitTime = sourceCommitTime,
                sourceSizeBytes = validation.sourceSizeBytes,
                rowCount = validation.rowCount,
                tableName = validation.tableName
            )

            openCatalog?.database?.close()
            openCatalog = null
            backupFile.delete()
            backupManifest.delete()
            var replacementStarted = false
            try {
                if (activeFile.exists()) copyReplacing(activeFile, backupFile)
                if (manifestFile.exists()) copyReplacing(manifestFile, backupManifest)
                replacementStarted = true
                moveReplacing(stagedFile, activeFile)
                writeManifestAtomically(manifestFile, metadata)
                val database = openReadOnly(activeFile)
                openCatalog = OpenCatalog(database, metadata)
                synchronized(cache) { cache.clear() }
                backupFile.delete()
                backupManifest.delete()
                metadata
            } catch (error: Throwable) {
                openCatalog?.database?.close()
                openCatalog = null
                if (replacementStarted) {
                    activeFile.delete()
                    manifestFile.delete()
                    if (backupFile.exists()) moveReplacing(backupFile, activeFile)
                    if (backupManifest.exists()) moveReplacing(backupManifest, manifestFile)
                }
                runCatching { ensureReadyLocked() }
                throw error
            } finally {
                stagedFile.delete()
            }
        }
    }

    fun downloadStagingFile(): File = File(catalogDirectory(), DOWNLOAD_PART_NAME)

    fun cleanupInterruptedDownload() {
        downloadStagingFile().delete()
    }

    private fun ensureReadyLocked(): OpenCatalog {
        openCatalog?.let { return it }
        val directory = catalogDirectory()
        directory.mkdirsOrThrow()
        cleanupRecoveryFiles(directory)
        val bundled = readBundledMetadata()
        val activeFile = activeDatabaseFile()
        val installed = readInstalledMetadata()

        val activeIsUsable = activeFile.isFile && runCatching {
            validateStructure(activeFile)
        }.isSuccess
        val shouldInstallBundle = !activeIsUsable || (
            installed != null &&
                bundled.sourceCommitTime.isNotBlank() &&
                installed.sourceCommitTime.isNotBlank() &&
                bundled.sourceCommitTime > installed.sourceCommitTime
            )
        if (shouldInstallBundle) installBundledLocked(bundled)

        val structure = validateStructure(activeFile)
        val metadata = readInstalledMetadata()?.copy(
            rowCount = structure.rowCount,
            tableName = structure.tableName,
            sourceSizeBytes = activeFile.length()
        ) ?: gitBlobSha(activeFile).let { actualSha ->
            if (actualSha.equals(bundled.sourceSha, ignoreCase = true)) {
                bundled.copy(
                    rowCount = structure.rowCount,
                    tableName = structure.tableName,
                    sourceSizeBytes = activeFile.length()
                )
            } else {
                DanbooruCatalogMetadata(
                    sourceSha = actualSha,
                    sourceCommitTime = "",
                    sourceSizeBytes = activeFile.length(),
                    rowCount = structure.rowCount,
                    tableName = structure.tableName
                )
            }
        }
        if (readInstalledMetadata() != metadata) writeManifestAtomically(installedManifestFile(), metadata)
        return OpenCatalog(openReadOnly(activeFile), metadata).also { openCatalog = it }
    }

    private fun installBundledLocked(metadata: DanbooruCatalogMetadata) {
        val directory = catalogDirectory()
        val staged = File(directory, BUNDLED_PART_NAME)
        val active = activeDatabaseFile()
        val manifest = installedManifestFile()
        val backup = File(directory, "$ACTIVE_DATABASE_NAME.backup")
        val backupManifest = File(directory, "$INSTALLED_MANIFEST_NAME.backup")
        staged.delete()
        backup.delete()
        backupManifest.delete()
        var replacementStarted = false
        try {
            app.assets.open(BUNDLED_DATABASE_ASSET).use { assetInput ->
                GZIPInputStream(assetInput.buffered()).use { input ->
                    FileOutputStream(staged).buffered().use { output -> input.copyTo(output) }
                }
            }
            if (metadata.sourceSizeBytes > 0L && staged.length() != metadata.sourceSizeBytes) {
                throw IOException("内置 Danbooru 词条库大小不匹配")
            }
            if (metadata.sourceSha.isNotBlank() &&
                !gitBlobSha(staged).equals(metadata.sourceSha, ignoreCase = true)
            ) {
                throw IOException("内置 Danbooru 词条库完整性校验失败")
            }
            val structure = validateStructure(staged)
            val installed = metadata.copy(
                sourceSizeBytes = staged.length(),
                rowCount = structure.rowCount,
                tableName = structure.tableName
            )
            if (active.exists()) copyReplacing(active, backup)
            if (manifest.exists()) copyReplacing(manifest, backupManifest)
            replacementStarted = true
            moveReplacing(staged, active)
            writeManifestAtomically(manifest, installed)
            backup.delete()
            backupManifest.delete()
        } catch (error: Throwable) {
            if (replacementStarted) {
                active.delete()
                manifest.delete()
                if (backup.exists()) moveReplacing(backup, active)
                if (backupManifest.exists()) moveReplacing(backupManifest, manifest)
            }
            throw error
        } finally {
            staged.delete()
        }
    }

    private fun validateStructure(file: File): CatalogStructure {
        requireSqliteHeader(file)
        val database = openReadOnly(file)
        return try {
            database.rawQuery("PRAGMA quick_check", null).use { cursor ->
                if (!cursor.moveToFirst() || cursor.getString(0) != "ok") {
                    throw IOException("词库 SQLite 完整性检查失败")
                }
            }
            val tableName = discoverTagTable(database)
            val rowCount = database.rawQuery(
                "SELECT COUNT(*) FROM ${quotedIdentifier(tableName)}",
                null
            ).use { cursor ->
                if (!cursor.moveToFirst()) 0L else cursor.getLong(0)
            }
            if (rowCount < minimumExpectedRowCount) {
                throw IOException("词库数据量异常：$rowCount 条")
            }
            val supportedCount = database.rawQuery(
                "SELECT COUNT(*) FROM ${quotedIdentifier(tableName)} WHERE category IN (0, 3, 4)",
                null
            ).use { cursor ->
                if (!cursor.moveToFirst()) 0L else cursor.getLong(0)
            }
            if (supportedCount <= 0L) throw IOException("词库缺少可用 Danbooru 分类")
            CatalogStructure(tableName, rowCount)
        } finally {
            database.close()
        }
    }

    private fun discoverTagTable(database: SQLiteDatabase): String {
        val candidates = mutableListOf<String>()
        database.rawQuery(
            "SELECT name FROM sqlite_master WHERE type='table' AND name NOT LIKE 'sqlite_%'",
            null
        ).use { cursor ->
            while (cursor.moveToNext()) {
                val table = cursor.getString(0).orEmpty()
                if (table.isBlank()) continue
                val columns = mutableSetOf<String>()
                database.rawQuery("PRAGMA table_info(${quotedIdentifier(table)})", null).use { info ->
                    while (info.moveToNext()) columns += info.getString(1).lowercase(Locale.ROOT)
                }
                if (columns.containsAll(REQUIRED_COLUMNS)) candidates += table
            }
        }
        return candidates.firstOrNull { it.equals("tags", ignoreCase = true) }
            ?: candidates.singleOrNull()
            ?: throw IOException("词库结构不兼容：未找到唯一标签表")
    }

    private fun queryCandidates(
        database: SQLiteDatabase,
        tableName: String,
        query: String,
        limit: Int
    ): List<NovelAiTagCandidate> {
        val lowercaseQuery = query.lowercase(Locale.ROOT)
        val escaped = escapeLike(lowercaseQuery)
        val exactChinese = query.replace(" ", "")
        val prefix = "$escaped%"
        val contains = "%$escaped%"
        val sql = """
            SELECT name, cn_name, post_count, category
            FROM ${quotedIdentifier(tableName)}
            WHERE category IN (0, 3, 4)
              AND (
                lower(name) LIKE ? ESCAPE '\'
                OR replace(lower(cn_name), ' ', '') LIKE ? ESCAPE '\'
              )
            ORDER BY CASE
                WHEN lower(name) = ? THEN 0
                WHEN replace(lower(cn_name), ' ', '') = ? THEN 0
                WHEN lower(name) LIKE ? ESCAPE '\' THEN 1
                WHEN replace(lower(cn_name), ' ', '') LIKE ? ESCAPE '\' THEN 1
                ELSE 2
              END,
              post_count DESC,
              lower(name) ASC
            LIMIT ?
        """.trimIndent()
        val args = arrayOf(
            contains,
            "%${escapeLike(exactChinese.lowercase(Locale.ROOT))}%",
            lowercaseQuery,
            exactChinese.lowercase(Locale.ROOT),
            prefix,
            "${escapeLike(exactChinese.lowercase(Locale.ROOT))}%",
            limit.coerceIn(1, MAX_TAG_CANDIDATES_PER_QUERY).toString()
        )
        return database.rawQuery(sql, args).use { cursor ->
            buildList {
                while (cursor.moveToNext()) cursor.toCandidate()?.let(::add)
            }.distinctBy { it.name.lowercase(Locale.ROOT) }
        }
    }

    private fun Cursor.toCandidate(): NovelAiTagCandidate? {
        val name = getString(0).orEmpty().trim()
        val category = NovelAiTagCategory.fromCode(getInt(3)) ?: return null
        if (!name.isValidDanbooruTagName()) return null
        return NovelAiTagCandidate(
            name = name,
            translatedName = getString(1).orEmpty().normalizeChineseName().take(MAX_TRANSLATED_TAG_CHARS),
            count = getLong(2).coerceAtLeast(0L),
            category = category
        )
    }

    private fun readBundledMetadata(): DanbooruCatalogMetadata = app.assets
        .open(BUNDLED_MANIFEST_ASSET)
        .bufferedReader(Charsets.UTF_8)
        .use { json.decodeFromString(DanbooruCatalogMetadata.serializer(), it.readText()) }

    private fun readInstalledMetadata(): DanbooruCatalogMetadata? {
        val file = installedManifestFile()
        if (!file.isFile) return null
        return runCatching {
            json.decodeFromString(DanbooruCatalogMetadata.serializer(), file.readText(Charsets.UTF_8))
        }.getOrNull()
    }

    private fun writeManifestAtomically(file: File, metadata: DanbooruCatalogMetadata) {
        val staged = File(file.parentFile, ".${file.name}.part")
        try {
            staged.writeText(json.encodeToString(DanbooruCatalogMetadata.serializer(), metadata), Charsets.UTF_8)
            moveReplacing(staged, file)
        } finally {
            staged.delete()
        }
    }

    private fun cleanupRecoveryFiles(directory: File) {
        File(directory, BUNDLED_PART_NAME).delete()
        val active = activeDatabaseFile()
        val manifest = installedManifestFile()
        val backup = File(directory, "$ACTIVE_DATABASE_NAME.backup")
        val backupManifest = File(directory, "$INSTALLED_MANIFEST_NAME.backup")
        if (!active.exists() && backup.exists()) moveReplacing(backup, active) else backup.delete()
        if (!manifest.exists() && backupManifest.exists()) moveReplacing(backupManifest, manifest) else backupManifest.delete()
    }

    private fun cached(key: String, version: String): List<NovelAiTagCandidate>? = synchronized(cache) {
        cache[key]?.takeIf { it.version == version }?.candidates
    }

    private fun cache(key: String, version: String, candidates: List<NovelAiTagCandidate>) = synchronized(cache) {
        cache[key] = CacheEntry(version, candidates)
        while (cache.size > CACHE_MAX_ENTRIES) cache.entries.iterator().run {
            if (hasNext()) next().also { remove() }
        }
    }

    private fun catalogDirectory(): File = File(app.filesDir, CATALOG_DIRECTORY)
    private fun activeDatabaseFile(): File = File(catalogDirectory(), ACTIVE_DATABASE_NAME)
    private fun installedManifestFile(): File = File(catalogDirectory(), INSTALLED_MANIFEST_NAME)

    private data class CatalogStructure(val tableName: String, val rowCount: Long)

    companion object {
        const val SOURCE_OWNER = "ffdkj"
        const val SOURCE_REPOSITORY = "ffdkj-Danbooru_Tag-Chinese-English-Translation-Table"
        const val SOURCE_PATH = "tag.sqlite"
        const val SOURCE_BRANCH = "main"
        const val SOURCE_PAGE_URL = "https://github.com/$SOURCE_OWNER/$SOURCE_REPOSITORY"

        private const val BUNDLED_DATABASE_ASSET = "danbooru/tag.sqlite.bundle"
        private const val BUNDLED_MANIFEST_ASSET = "danbooru/catalog.json"
        private const val CATALOG_DIRECTORY = "danbooru_catalog"
        private const val ACTIVE_DATABASE_NAME = "tag.sqlite"
        private const val INSTALLED_MANIFEST_NAME = "catalog.json"
        private const val DOWNLOAD_PART_NAME = "tag.sqlite.download.part"
        private const val BUNDLED_PART_NAME = "tag.sqlite.bundle.part"
        private const val CACHE_MAX_ENTRIES = 128
        private const val SQLITE_BIND_LIMIT = 500
        private const val MIN_EXPECTED_ROW_COUNT = 10_000L
        private const val MIN_DANBOORU_QUERY_LENGTH = 2
        private const val MAX_DANBOORU_QUERY_LENGTH = 80
        private const val MAX_TAG_NAME_CHARS = 200
        private const val MAX_TRANSLATED_TAG_CHARS = 200
        private val REQUIRED_COLUMNS = setOf("name", "category", "cn_name", "post_count")
    }
}

internal fun String.normalizeDanbooruTagQuery(): String {
    val collapsed = replace(Regex("\\s+"), " ").trim().take(80)
    val containsCjk = collapsed.any { char ->
        char.code in 0x3400..0x9FFF || char.code in 0xF900..0xFAFF
    }
    return if (containsCjk) collapsed.replace(" ", "") else collapsed.replace(" ", "_")
}

private fun String.normalizeChineseName(): String = replace(Regex("\\s+"), " ").trim()

private fun String.isValidDanbooruTagName(): Boolean =
    length in 1..200 && none { char -> char.isWhitespace() || char == ',' || char.code !in 0x21..0x7E }

private fun escapeLike(value: String): String = value
    .replace("\\", "\\\\")
    .replace("%", "\\%")
    .replace("_", "\\_")

private fun quotedIdentifier(value: String): String = "\"${value.replace("\"", "\"\"")}\""

private fun requireSqliteHeader(file: File) {
    val expected = "SQLite format 3\u0000".toByteArray(Charsets.US_ASCII)
    val actual = ByteArray(expected.size)
    FileInputStream(file).use { input ->
        if (input.read(actual) != actual.size || !actual.contentEquals(expected)) {
            throw IOException("下载内容不是 SQLite 数据库")
        }
    }
}

private fun openReadOnly(file: File): SQLiteDatabase = SQLiteDatabase.openDatabase(
    file.absolutePath,
    null,
    SQLiteDatabase.OPEN_READONLY or SQLiteDatabase.NO_LOCALIZED_COLLATORS
)

private fun File.mkdirsOrThrow() {
    if (!exists() && !mkdirs()) throw IOException("无法创建 Danbooru 词条库目录")
}

private fun moveReplacing(source: File, target: File) {
    target.parentFile?.mkdirsOrThrow()
    try {
        Files.move(
            source.toPath(),
            target.toPath(),
            StandardCopyOption.ATOMIC_MOVE,
            StandardCopyOption.REPLACE_EXISTING
        )
    } catch (_: AtomicMoveNotSupportedException) {
        Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING)
    }
}

private fun copyReplacing(source: File, target: File) {
    target.parentFile?.mkdirsOrThrow()
    Files.copy(
        source.toPath(),
        target.toPath(),
        StandardCopyOption.REPLACE_EXISTING,
        StandardCopyOption.COPY_ATTRIBUTES
    )
}

internal fun gitBlobSha(file: File): String {
    val digest = MessageDigest.getInstance("SHA-1")
    digest.update("blob ${file.length()}\u0000".toByteArray(Charsets.UTF_8))
    FileInputStream(file).buffered().use { input ->
        val buffer = ByteArray(64 * 1024)
        while (true) {
            val count = input.read(buffer)
            if (count < 0) break
            digest.update(buffer, 0, count)
        }
    }
    return digest.digest().joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
}
