package com.example.chatbar.domain.image

import android.app.Application
import android.database.sqlite.SQLiteDatabase
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import java.io.File
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class DanbooruTagCatalogTest {
    private lateinit var app: Application
    private lateinit var catalog: DanbooruTagCatalog
    private lateinit var catalogDirectory: File

    @Before
    fun setUp() {
        app = ApplicationProvider.getApplicationContext()
        catalog = DanbooruTagCatalog(app, minimumExpectedRowCount = 1L)
        catalogDirectory = catalog.downloadStagingFile().parentFile!!
        catalogDirectory.deleteRecursively()
    }

    @After
    fun tearDown() {
        catalogDirectory.deleteRecursively()
    }

    @Test
    fun localCatalogRanksFiltersTranslatesAndPreservesInstalledDatabaseOnValidationFailure() = runBlocking {
        val staged = catalog.downloadStagingFile()
        staged.parentFile?.mkdirs()
        createFixture(staged)
        val validation = catalog.validateDownloadedDatabase(
            file = staged,
            expectedSizeBytes = staged.length(),
            expectedSourceSha = gitBlobSha(staged)
        )
        catalog.installDownloadedDatabase(staged, "2026-08-28T00:00:00Z", validation)

        assertEquals(
            listOf("blue_hair", "blue_eyes", "dark_blue_eyes"),
            catalog.search("blue").candidates.map { it.name }
        )
        assertEquals("blue_eyes", catalog.search("blue eyes").candidates.first().name)
        assertEquals("blue_eyes", catalog.search("蓝眼睛").candidates.first().name)
        assertEquals("50%_off", catalog.search("50%").candidates.single().name)
        assertTrue(catalog.search("artist").candidates.isEmpty())
        assertEquals(
            "蓝眼睛",
            catalog.exactChineseTranslations(listOf("BLUE EYES"))["blue_eyes"]
        )

        val malformed = catalog.downloadStagingFile()
        malformed.writeText("not sqlite")
        val failure = runCatching {
            catalog.validateDownloadedDatabase(
                malformed,
                malformed.length(),
                gitBlobSha(malformed)
            )
        }
        assertTrue(failure.isFailure)
        assertEquals("blue_eyes", catalog.search("blue eyes").candidates.first().name)
    }

    @Test
    fun bundledCatalogSatisfiesStableDatabaseContract() = runBlocking {
        val metadata = catalog.catalogMetadata()

        assertEquals(40, metadata.sourceSha.length)
        assertTrue(metadata.rowCount >= 10_000L)
        assertTrue(metadata.tableName.isNotBlank())
    }

    private fun createFixture(file: File) {
        SQLiteDatabase.openOrCreateDatabase(file, null).use { database ->
            database.execSQL(
                "CREATE TABLE tags (" +
                    "name TEXT PRIMARY KEY, category INTEGER, cn_name TEXT, post_count INTEGER)"
            )
            database.beginTransaction()
            try {
                insert(database, "blue_eyes", 0, "蓝眼睛", 100)
                insert(database, "blue_hair", 0, "蓝色头发", 200)
                insert(database, "dark_blue_eyes", 0, "深蓝眼睛", 500)
                insert(database, "hatsune_miku", 4, "初音未来", 300)
                insert(database, "vocaloid", 3, "VOCALOID", 250)
                insert(database, "50%_off", 0, "五折", 50)
                insert(database, "artist_tag", 1, "画师标签", 999)
                database.setTransactionSuccessful()
            } finally {
                database.endTransaction()
            }
        }
    }

    private fun insert(
        database: SQLiteDatabase,
        name: String,
        category: Int,
        chineseName: String,
        postCount: Long
    ) {
        database.execSQL(
            "INSERT INTO tags(name, category, cn_name, post_count) VALUES (?, ?, ?, ?)",
            arrayOf<Any?>(name, category, chineseName, postCount)
        )
    }
}
